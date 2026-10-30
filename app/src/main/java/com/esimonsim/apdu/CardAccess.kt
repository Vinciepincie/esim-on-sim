package com.esimonsim.apdu

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.esimonsim.lpa.Lpa
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

/** How the app reaches the card. */
enum class AccessMethod {
    /** The card itself lets this app in (Android's OMAPI): no setup at all. */
    DIRECT,
    /** su, on rooted phones. */
    ROOT,
    /** The helper started by Shizuku, where that's installed and running. */
    SHIZUKU,
    /** The helper started over ADB: Wireless debugging, or on Android 10 and older the debugging port a computer
     *  turned on. */
    ADB,
}

/** Shizuku on this phone, as far as this app goes. */
enum class ShizukuStatus {
    /** Not installed. */
    ABSENT,
    /** Installed but not running: it usually has to be started again after each restart. */
    STOPPED,
    /** Running, and this app hasn't been asked about yet. */
    NOT_ASKED,
    /** Running, and people said no to this app. Unlike root apps, Shizuku doesn't remember that: it would ask again. */
    DECLINED,
    ALLOWED,
}

/** Nothing reached the card without setup, and ADB isn't set up (or wasn't to be tried).
 *  [message] says why, when a way that worked before stopped working; null when there is nothing to report. */
class SetupNeededException(message: String?) : IOException(message)

/** Picks how the app reaches the card, best first: root, which needs no setup and can do everything; then
 *  [AccessMethod.DIRECT] when the card lets this app in; then Shizuku, where it's installed and running; then ADB
 *  (Wireless debugging, or on Android 10 and older a computer's help), the only one that needs setting up here. All
 *  but ADB are tried once each time the app starts, and whenever someone reconnects; the way that worked is kept for
 *  next time. [sdk] is the Android version's API level. */
class CardAccess internal constructor(
    private val prefs: SharedPreferences,
    private val direct: OmapiApduBackend,
    private val root: RootApduBackend,
    private val adb: Adb,
    private val findSu: () -> Boolean = {
        System.getenv("PATH").orEmpty().split(':').any { it.isNotEmpty() && runCatching { File(it, "su").exists() }.getOrDefault(false) }
    },
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val shizuku: ShizukuApduBackend = ShizukuApduBackend({ "" }, NoShizuku),
) : ApduBackend, AutoCloseable {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        OmapiApduBackend(context), RootApduBackend(context), Adb.of(AdbApduBackend(context.applicationContext)),
        shizuku = ShizukuApduBackend(context))

    /** What this uses of [AdbApduBackend], which see. */
    interface Adb : ApduBackend, AutoCloseable {
        val wasPaired: Boolean
        val hasHelper: Boolean
        val helperUnavailable: Boolean
        val inBackground: Boolean
        fun connect(port: Int?, authorize: Boolean)
        fun cancelConnect() {}
        fun useSim(slot: Int, iccid: String, data: Boolean, roaming: Boolean): SimSettingsResult
        fun <T> reading(block: () -> T): T
        fun stop()
        fun disconnect()

        companion object {
            fun of(b: AdbApduBackend): Adb = object : Adb, ApduBackend by b {
                override val wasPaired get() = b.wasPaired
                override val hasHelper get() = b.hasHelper
                override val helperUnavailable get() = b.helperUnavailable
                override val inBackground get() = b.inBackground
                override fun connect(port: Int?, authorize: Boolean) = b.connect(port, authorize)
                override fun cancelConnect() = b.cancelConnect()
                override fun useSim(slot: Int, iccid: String, data: Boolean, roaming: Boolean) = b.useSim(slot, iccid, data, roaming)
                override fun <T> reading(block: () -> T) = b.reading(block)
                override fun stop() = b.stop()
                override fun disconnect() = b.disconnect()
                override fun close() = b.close()
            }
        }
    }

    @Volatile private var active: ApduBackend? = null

    /** The way the card was reached last, kept across restarts; null before it ever was. */
    @Volatile var method: AccessMethod? = prefs.getString(METHOD, null)?.let { m -> AccessMethod.entries.firstOrNull { it.name == m } }
        private set

    // Tried since the app started, so not tried again by themselves; and failed, so not even the way that worked
    // last time is, until someone reconnects.
    @Volatile private var directChecked = false
    @Volatile private var rootChecked = false
    @Volatile private var shizukuChecked = false
    @Volatile private var directFailed = false
    @Volatile private var rootFailed = false
    @Volatile private var shizukuFailed = false

    /** Root was turned down for this app last time it was asked (or the root app refused it). Still asked each
     *  time the app starts: root apps remember the answer, so that's quick, and it picks up a grant made since. */
    val rootDeclined: Boolean get() = prefs.getBoolean(ROOT_DECLINED, false)

    /** An su is there that this app might be allowed to run; KernelSU only shows it to apps allowed in its app.
     *  False once running it failed: the su of test builds of Android only runs for ADB shell. */
    val rootFound: Boolean get() = suInPath && !notRooted
    @Volatile private var suInPath = findSu()
    @Volatile private var notRooted = false

    /** On return to the app: KernelSU and APatch only show su to apps allowed in them. True when it just appeared,
     *  so root is worth asking for again. */
    fun rootAppeared(): Boolean {
        val now = findSu()
        val appeared = now && !suInPath
        suInPath = now
        if (appeared) { notRooted = false; rootChecked = false; rootFailed = false }
        return appeared
    }

    /** Goes up whenever Shizuku may have started, stopped or answered: it hands this app its connection in its own
     *  time, so nothing else says when. */
    val shizukuChanges: StateFlow<Int> get() = shizuku.changes

    /** Unlike root apps, Shizuku doesn't remember a refusal: it would ask at every start, so this app does. */
    private val shizukuDeclined: Boolean get() = prefs.getBoolean(SHIZUKU_DECLINED, false)

    /** Goes over binder when Shizuku is running: not on the main thread. */
    val shizukuStatus: ShizukuStatus get() = when {
        shizuku.allowed -> ShizukuStatus.ALLOWED
        !shizuku.running -> if (shizuku.installed) ShizukuStatus.STOPPED else ShizukuStatus.ABSENT
        shizukuDeclined -> ShizukuStatus.DECLINED
        else -> ShizukuStatus.NOT_ASKED
    }

    private var shizukuWorthAsking = false
    /** As [shizukuAppeared] last saw it: asking Shizuku goes over binder, and [canSetUpSim] runs on the main thread. */
    @Volatile private var shizukuAllowed = false

    /** Shizuku's question is up, so the Connecting screen says what to do, and can stop waiting. */
    val shizukuAsking: Boolean get() = shizuku.asking

    /** A connection left Shizuku's question for when someone's in the app (see [connect]), so that's worth another try. */
    @Volatile var shizukuQuestionWaits = false
        private set

    /** When Shizuku changed, and on return to the app: it may have been started, or this app allowed in it, which
     *  sends no word when done from Shizuku's own app. True when it's now worth connecting. Goes over binder. */
    @Synchronized
    fun shizukuAppeared(): Boolean {
        val allowed = shizuku.allowed
        val now = allowed || (shizuku.running && !shizukuDeclined)
        // Allowed counts by itself: the wait for that answer may have ended long ago.
        val appeared = (now && !shizukuWorthAsking) || (allowed && !shizukuAllowed)
        shizukuWorthAsking = now
        shizukuAllowed = allowed
        if (allowed) prefs.edit { remove(SHIZUKU_DECLINED) }
        if (appeared) { shizukuChecked = false; shizukuFailed = false }
        return appeared
    }

    /** A way in that needs no setup hasn't been ruled out: worth connecting before anything is set up. */
    val withoutSetup: Boolean get() = !rootChecked || !directChecked || !shizukuChecked ||
        (method == AccessMethod.ROOT && !rootFailed) || (method == AccessMethod.DIRECT && !directFailed) ||
        (method == AccessMethod.SHIZUKU && !shizukuFailed)

    val wasPaired: Boolean get() = adb.wasPaired
    val hasHelper: Boolean get() = adb.hasHelper
    val helperUnavailable: Boolean get() = adb.helperUnavailable
    /** Connected through the background helper, which doesn't need Wireless debugging. */
    val inBackground: Boolean get() = active === adb && adb.inBackground
    /** The phone's SIM settings can follow a switch (mobile data, roaming). Android keeps them from ordinary apps,
     *  so with direct access only Shizuku, or ADB set up for just this, can change them. */
    val canSetUpSim: Boolean get() = active !== direct || adb.wasPaired || adb.hasHelper || shizukuAllowed

    override fun isReady(): Boolean = active?.isReady() == true

    /** Tries the ways in, best first. A [port] means ADB only. [viaAdb] false: only the ways that need no setup.
     *  [again]: someone asked, so ways ruled out earlier are tried again; on Android 10 and older ADB is tried even
     *  before it ever worked (setup ends with this), and the phone may ask whether to allow this app. [mayAsk] false:
     *  nobody is in the app, so Shizuku's question, which would open over whatever they're doing, has to wait. */
    fun connect(port: Int? = null, viaAdb: Boolean = true, again: Boolean = false, mayAsk: Boolean = true) {
        if (isReady()) return
        disconnect()
        // Why the way that worked last time failed now: news worth more than Wireless debugging being off.
        var failure: Exception? = null
        var directSlow = false
        if (port == null) {
            if (again || !rootChecked || (method == AccessMethod.ROOT && !rootFailed)) {
                rootChecked = true
                try { return connectAsRoot() }
                catch (e: RootApduBackend.NotRootedException) { if (method == AccessMethod.ROOT) failure = e }
                catch (e: RootApduBackend.DeniedException) { if (method == AccessMethod.ROOT) failure = e }
            }
            if (again || !directChecked || (method == AccessMethod.DIRECT && !directFailed)) {
                directChecked = true
                // Cards that refused this app before are only asked again when someone reconnects.
                val refused = if (again) emptySet() else prefs.getStringSet(REFUSED, null).orEmpty()
                try { direct.connect(refused); directFailed = false; return use(direct, AccessMethod.DIRECT) }
                catch (e: OmapiApduBackend.UnavailableException) {
                    if (!e.timedOut) prefs.edit { putStringSet(REFUSED, e.refused) }
                    // A slow start of Android's SIM service rules nothing out: tried again next time.
                    if (!e.timedOut) directFailed = true else directChecked = false
                    directSlow = e.timedOut
                    if (method == AccessMethod.DIRECT) failure = e
                }
            }
            // After a slow start of Android's SIM service the card may well let this app in: no question for that.
            val shizukuTurn = !directSlow || again || method == AccessMethod.SHIZUKU
            if (shizukuTurn && (again || !shizukuChecked || (method == AccessMethod.SHIZUKU && !shizukuFailed))) {
                if (again || !shizukuChecked) shizuku.awaitRunning(SHIZUKU_WAIT_MS)
                // Asked by itself only once: after a no, "Use Shizuku" asks, or Reconnect when it's the way in.
                val ask = !shizukuDeclined || (again && method == AccessMethod.SHIZUKU)
                shizukuQuestionWaits = ask && !mayAsk && !again && shizuku.running && !shizuku.allowed
                if (!shizukuQuestionWaits) {
                    shizukuChecked = true
                    try { return connectShizuku(ask) }
                    catch (e: ShizukuApduBackend.Failure) { if (method == AccessMethod.SHIZUKU) failure = e }
                }
            }
        }
        // Wireless debugging can't work before pairing, unless a helper started earlier is still running. Without it
        // (Android 10 and older) the first connection is the pairing: tried when someone asks, and refused at once
        // where no computer turned the debugging port on.
        val worthIt = adb.wasPaired || adb.hasHelper || (again && sdk < 30)
        if (port == null && (!viaAdb || !worthIt)) throw SetupNeededException(failure?.let(::reason))
        try { adb.connect(port, authorize = again && sdk < 30) } catch (e: IOException) {
            // What only people can fix, here and now, comes first. Not "Nearby devices" (Android 17): when root or
            // direct access worked before, why it failed now matters more, and their Reconnect screen can't ask.
            if (failure == null || e is PairingRequiredException || e is AllowNeededException) throw e
            throw SetupNeededException(reason(failure))
        }
        use(adb, AccessMethod.ADB)
    }

    /** With direct access, on Android 10 and older: connects the ADB helper that SIM settings need ([useSim]) now,
     *  while someone is there to allow it, since the phone asks. Card access itself stays direct. */
    fun setUpSimSettings() {
        if (!adb.isReady()) adb.connect(null, authorize = sdk < 30)
    }

    /** With direct access: asks Shizuku to allow this app, for the SIM settings ([useSim]). Card access itself stays
     *  direct, which would win again at the next start anyway. */
    fun allowShizuku() {
        try { shizuku.allow() } catch (e: ShizukuApduBackend.DeniedException) {
            if (e.answered) prefs.edit { putBoolean(SHIZUKU_DECLINED, true) }
            throw e
        }
        shizukuAllowed = true
        prefs.edit { remove(SHIZUKU_DECLINED) }
    }

    /** Stops waiting for the phone or Shizuku to allow this app; see [AdbApduBackend.cancelConnect]. Any thread. */
    fun cancelConnect() {
        adb.cancelConnect()
        shizuku.cancelAsk()
    }

    /** Within an operation, e.g. after a switch restarted the card: the way in that was just in use, and no other.
     *  Another one numbers slots differently (OMAPI's readers are Android's SIM slots, the helper's are physical),
     *  and asking for root or Shizuku midway could hold the card for a minute. */
    fun reconnect() {
        if (isReady()) return
        when (method) {
            AccessMethod.ROOT -> connectAsRoot()
            AccessMethod.DIRECT -> { direct.connect(); use(direct, AccessMethod.DIRECT) }
            AccessMethod.SHIZUKU -> connectShizuku(ask = false)
            AccessMethod.ADB, null -> { adb.connect(null, authorize = false); use(adb, AccessMethod.ADB) }
        }
    }

    /** "Use root", e.g. once it's allowed in the root app after a refusal: asks for it again, and nothing else.
     *  Card access that works now carries on until root does, and also if root is refused again. */
    fun connectRoot() {
        if (isReady() && active === root) return
        val before = active
        rootChecked = true
        connectAsRoot()
        if (before === direct) direct.disconnect() else if (before === shizuku) shizuku.disconnect()
        else if (before === adb) adb.disconnect()
    }

    /** "Use Shizuku": asks it to allow this app even after a refusal, and nothing else. As with [connectRoot], card
     *  access that works now carries on until this does. */
    fun useShizuku() {
        if (isReady() && active === shizuku) return
        val before = active
        shizukuChecked = true
        connectShizuku(ask = true)
        if (before === direct) direct.disconnect() else if (before === root) root.disconnect()
        else if (before === adb) adb.disconnect()
    }

    private fun connectAsRoot() {
        try { root.connect() }
        catch (e: RootApduBackend.NotRootedException) { notRooted = true; rootFailed = true; throw e }
        catch (e: RootApduBackend.DeniedException) {
            rootFailed = true
            prefs.edit { putBoolean(ROOT_DECLINED, true) }
            throw e
        }
        rootFailed = false
        prefs.edit { remove(ROOT_DECLINED) }
        use(root, AccessMethod.ROOT)
    }

    private fun connectShizuku(ask: Boolean) {
        try { shizuku.connect(ask) }
        catch (e: ShizukuApduBackend.NotRunningException) { shizukuFailed = true; throw e }
        catch (e: ShizukuApduBackend.DeniedException) {
            shizukuFailed = true
            if (e.answered) prefs.edit { putBoolean(SHIZUKU_DECLINED, true) }
            throw e
        }
        shizukuFailed = false
        prefs.edit { remove(SHIZUKU_DECLINED) }
        use(shizuku, AccessMethod.SHIZUKU)
    }

    private fun use(backend: ApduBackend, how: AccessMethod) {
        active = backend
        if (method != how) { method = how; prefs.edit { putString(METHOD, how.name) } }
        // These do all a helper left running over Wireless debugging did: nothing else keeps running for this app.
        // With direct access that helper may still be wanted, for SIM settings.
        if ((how == AccessMethod.ROOT || how == AccessMethod.SHIZUKU) && adb.hasHelper) runCatching { adb.stop() }
        Log.i(TAG, "Card access: $how")
    }

    private fun reason(e: Exception): String? {
        // Android 10 and older have no Wireless debugging; a computer can turn on what it would.
        val otherwise = if (sdk >= 30) " Otherwise, set up Wireless debugging." else " Otherwise, set it up with a computer."
        return when (e) {
            // Locked: an ordinary SIM in the other slot refuses this app too, so the card may just be out.
            is OmapiApduBackend.UnavailableException -> if (e.timedOut) "Android's SIM service didn't answer in " +
                "time. Tap \"Reconnect\" to try again." else if (e.locked) "Couldn't open your card directly. If it's " +
                "out, put it back and tap \"Reconnect\". A different card may not let this app in.$otherwise"
                else "Couldn't find your eSIM card. Check that it's inserted, then tap \"Reconnect\"." + Lpa.CARD_BUSY_TIP
            is RootApduBackend.NotRootedException -> "Root isn't there any more. If your root app hides it, allow " +
                "this app there, then tap \"Reconnect\".$otherwise"
            is ShizukuApduBackend.NotRunningException -> if (!shizuku.installed) "Shizuku isn't on this phone any " +
                "more.$otherwise" else "Shizuku isn't running. It stops when your phone restarts, and can stop when " +
                "Wi-Fi drops. Start it, and this app reconnects by itself.$otherwise"
            is ShizukuApduBackend.DeniedException -> "Shizuku doesn't allow this app. Tap \"Reconnect\" and allow " +
                "it.$otherwise"
            is ShizukuApduBackend.StartException -> "Shizuku couldn't start this app's card helper. Tap \"Reconnect\" " +
                "to try again.$otherwise"
            else -> e.message
        }
    }

    private fun current() = active ?: throw ApduException("Card access disconnected. Reconnect first.")

    override fun openChannel(slot: Int, port: Int, aidHex: String) = current().openChannel(slot, port, aidHex)

    override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String) =
        current().transmit(slot, port, channel, cla, ins, p1, p2, p3, dataHex)

    override fun closeChannel(slot: Int, port: Int, channel: Int) { active?.closeChannel(slot, port, channel) }

    override fun slots(): List<SlotInfo> = current().slots()

    /** Runs [block], which only reads the card, with shorter time limits (see [AdbApduBackend.reading]). */
    fun <T> reading(block: () -> T): T = adb.reading { root.reading { shizuku.reading(block) } }

    /** See [AdbApduBackend.useSim]. With direct access, through Shizuku or ADB (see [canSetUpSim]); throws
     *  [NoSimSettingsException] when neither can connect. */
    fun useSim(slot: Int, iccid: String, data: Boolean, roaming: Boolean): SimSettingsResult = when (current()) {
        root -> root.useSim(slot, iccid, data, roaming)
        shizuku -> shizuku.useSim(slot, iccid, data, roaming)
        adb -> adb.useSim(slot, iccid, data, roaming)
        else -> useSimFromDirect(slot, iccid, data, roaming)
    }

    private fun useSimFromDirect(slot: Int, iccid: String, data: Boolean, roaming: Boolean): SimSettingsResult {
        val allowed = shizuku.allowed
        // It was what made this possible, so it's what went wrong.
        var failure: ShizukuApduBackend.Failure? = if (shizukuAllowed && !allowed) ShizukuApduBackend.NotRunningException() else null
        // Never asks: nobody is looking at a question in the middle of a switch.
        if (allowed) try {
            shizuku.connect(ask = false)
            return shizuku.useSim(slot, iccid, data, roaming)
        } catch (e: ShizukuApduBackend.Failure) { failure = e }
        // No pairing any more (Android forgets it after 7 days unused): it has to be made again.
        if (!(adb.wasPaired || adb.hasHelper)) throw NoSimSettingsException(failure ?: PairingRequiredException())
        try { if (!adb.isReady()) adb.connect(null, authorize = false) } catch (e: Exception) { throw NoSimSettingsException(e) }
        return adb.useSim(slot, iccid, data, roaming)
    }

    /** Direct card access, and neither Shizuku nor ADB (which SIM settings need then) connected. A
     *  [PairingRequiredException] cause: it needs pairing again; an [AllowNeededException] one: the phone has to allow
     *  this app again; a [ShizukuApduBackend.Failure] one: Shizuku was the only way, and stopped working or, with
     *  [shizukuCantStart], is there but won't start this app's helper. */
    class NoSimSettingsException(cause: Throwable) : IOException("ADB didn't connect.", cause) {
        val needsPairing get() = cause is PairingRequiredException
        val needsAllow get() = cause is AllowNeededException
        val shizukuFailed get() = cause is ShizukuApduBackend.Failure
        val shizukuCantStart get() = cause is ShizukuApduBackend.StartException
    }

    /** Ends card access completely, a background helper included: nothing keeps running for this app. Shizuku itself
     *  stays: it isn't this app's to stop, and others may use it. */
    fun stop() {
        active = null
        direct.disconnect()
        root.disconnect()
        shizuku.disconnect()
        adb.stop()
    }

    /** Leaves a background helper running, for next time. */
    fun disconnect() {
        active = null
        direct.disconnect()
        root.disconnect()
        shizuku.disconnect()
        adb.disconnect()
    }

    override fun close() {
        active = null
        direct.close()
        root.close()
        shizuku.close()
        adb.close()
    }

    /** Shizuku on a phone that doesn't have it. */
    internal object NoShizuku : ShizukuApduBackend.Gate {
        override val installed = false
        override val running = false
        override val allowed = false
        override val asking = false
        override val changes: StateFlow<Int> = MutableStateFlow(0)
        override fun awaitRunning(ms: Long) {}
        override fun ask(seconds: Long): Boolean? = null
        override fun cancelAsk() {}
        override fun start(command: String) = throw IOException("Shizuku isn't installed.")
    }

    internal companion object {
        private const val TAG = "CardAccess"
        // Not "card-access": adb/Pairing keeps the pairing there.
        const val PREFS = "access-method"
        const val METHOD = "method"
        const val ROOT_DECLINED = "root-declined"
        const val SHIZUKU_DECLINED = "shizuku-declined"
        /** ATRs of cards that refused every eSIM manager ID last time. */
        const val REFUSED = "refused-cards"
        /** How long the first connection waits for Shizuku to hand over its connection, where it's installed. */
        const val SHIZUKU_WAIT_MS = 1000L
    }
}
