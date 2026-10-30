package com.esimonsim.apdu

import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.esimonsim.adb.AdbIdentity
import com.esimonsim.adb.DebugSettings
import com.esimonsim.adb.Pairing
import com.esimonsim.lpa.Hex
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Android 10 and older: the phone doesn't allow this app's debugging access. [asked]: it just asked "Allow USB
 *  debugging?" and no "Allow" came in time, or the app stopped waiting ([cancelled]). Otherwise it didn't know the
 *  app's key: never allowed with "Always allow", or forgotten (Android 10 forgets it after 7 days unused). */
class AllowNeededException(val asked: Boolean, val cancelled: Boolean = false, cause: Throwable? = null) :
    IOException(if (asked) "Your phone didn't allow this app. Tap \"Connect\" again. When your phone asks \"Allow USB " +
        "debugging?\", tick \"Always allow from this computer\" and tap \"${AdbIdentity.allowButton()}\"."
        else "Your phone no longer remembers allowing this app. Tap \"Connect\" and allow it again.", cause)

/** Android 10 and older: nothing listens on the debugging port. The phone restarted since a computer turned it
 *  on, or it never was. */
class DebuggingPortClosedException : IOException("Couldn't reach Android's debugging tool. After a restart, your " +
    "phone needs your computer again: connect it and type \"adb tcpip ${AdbApduBackend.TCP_PORT}\".")

/** Android 10 and older: something answered on [port] that couldn't prove it is Android's debugging tool (see
 *  [AdbApduBackend.proveShell]), so it got nothing: no helper key, no card request. Another app may hold the port,
 *  as it can after a restart; Android's tool then can't use it, but it can use another. */
class NotDebuggingToolException(port: Int) : IOException("Something other than Android's debugging tool answered " +
    "on port $port, so this app didn't connect to it. Another app may be using that port: on your computer, type " +
    "\"adb tcpip ${AdbApduBackend.SPARE_PORT}\" instead, then connect with port ${AdbApduBackend.SPARE_PORT}.")

/** What the helper's useSim did ([AdbApduBackend.useSim], [RootApduBackend.useSim]). [applied]: false while the phone
 *  hasn't picked the eSIM up yet. [roamingMayReset]: data roaming is on for now, but the phone may turn it back off by
 *  itself, e.g. after a restart: single-SIM Android 8.1 and 9 keep it only when it was set with their own switch. */
data class SimSettingsResult(val applied: Boolean, val roamingMayReset: Boolean = false) {
    internal companion object {
        fun request(slot: Int, iccid: String, data: Boolean, roaming: Boolean): JSONObject = JSONObject()
            .put("op", "useSim").put("slot", slot).put("iccid", iccid).put("data", data).put("roaming", roaming)

        /** Helpers from before roamingMayReset existed don't send it. */
        fun of(response: JSONObject) = SimSettingsResult(response.getBoolean("applied"), response.optBoolean("roamingMayReset"))
    }
}

/** Android 17: Wireless debugging didn't answer on the port that worked last (or none did yet), and finding its
 *  port needs "Nearby devices" (see [Pairing.canDiscover]), which only the app's screen can ask for. */
class NearbyDevicesNeededException : IOException("To find Wireless debugging's port, this app needs \"Nearby " +
    "devices\". Tap \"Reconnect\" to allow it, or enter the port below.")

/** Wireless debugging's port didn't turn up in a search that took its time, so another one straight after won't help. */
class PortNotFoundException : IOException(AdbApduBackend.NOT_CONNECTED)

/** ADB transport only; the helper in the APK owns all privileged telephony calls. Wireless debugging starts
 *  the helper in the background, and the app then talks to it over 127.0.0.1 (see [HelperLink]): card access
 *  keeps working without Wi-Fi until the phone restarts. Where that can't start, the ADB stream carries the
 *  requests instead, as long as Wireless debugging stays on.
 *
 *  Android 10 and older have no Wireless debugging. There Android's debugging service listens on a TCP port
 *  once a computer has run "adb tcpip 5555" (or LineageOS's "ADB over network" is on), on 127.0.0.1 too, until
 *  the phone restarts: plain adb, no pairing, no TLS and no Wi-Fi. The phone allows the app's key with its "Allow
 *  USB debugging?" question instead of a pairing, and the same helper starts. */
class AdbApduBackend(context: Context) : ApduBackend, AutoCloseable {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var manager: AdbIdentity? = null
    /** A connection waiting for the phone's "Allow USB debugging?" answer; [cancelConnect] ends it. */
    @Volatile private var asking: AdbIdentity? = null
    @Volatile private var cancelled = false
    @Volatile private var stream: AdbStream? = null
    @Volatile private var link: HelperLink? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val rpcWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "card-rpc").apply { isDaemon = true } }

    val wasPaired: Boolean get() = Pairing.isPaired(context)
    override fun isReady(): Boolean = link?.isOpen == true || stream?.isClosed == false

    /** A background helper was started and may still be running: worth trying without Wireless debugging. */
    val hasHelper: Boolean get() = saved() != null

    /** Connected through the background helper, which doesn't need Wireless debugging. */
    val inBackground: Boolean get() = link?.isOpen == true

    /** The background helper didn't start on this phone last time, so card access needs Wireless debugging. */
    val helperUnavailable: Boolean get() = prefs.getBoolean(HELPER_UNAVAILABLE, false)

    fun pair(port: Int, code: String) = Pairing.pair(context, port, code)

    /** Null port discovers this phone's TLS ADB service; pairing and connection ports differ.
     *  Always connects over loopback, so the link never touches the network. A background helper
     *  started earlier comes first: it answers without Wireless debugging or Wi-Fi.
     *  [authorize]: someone asked, so on Android 10 and older the phone may ask to allow this app (see [AdbIdentity]).
     *  Throws [AllowNeededException], [DebuggingPortClosedException] (Android 10 and older) and
     *  [NearbyDevicesNeededException] (Android 17) for what people can do something about. */
    fun connect(port: Int? = null, authorize: Boolean = false) {
        if (isReady()) return
        require(port == null || port in 1..65535) { "Connection port must be between 1 and 65535." }
        disconnect()
        if (port == null && openSaved()) return
        if (Build.VERSION.SDK_INT < 30) return connectOverTcp(port, authorize)
        val adb = AdbIdentity(context)
        manager = adb
        try {
            // The port that worked last time goes first: it holds until Wireless debugging is switched off, and a
            // closed port on this phone is refused at once, so only a new port costs the mDNS search.
            val last = if (port == null) Pairing.lastPort(context) else null
            val target = if (last != null && reuse(adb, last)) last else {
                // Android 17 only lets apps search with "Nearby devices", which the app asks for on screen. Only
                // worth asking while Wireless debugging may be on, not to find what isn't there (a saved helper
                // alone can lead here).
                if (port == null && !Pairing.canDiscover(context))
                    throw if (DebugSettings.read(context).mayConnect) NearbyDevicesNeededException() else PortNotFoundException()
                val found = port ?: Pairing.findConnectPort(context, 6000) ?: throw PortNotFoundException()
                check(adb.connect("127.0.0.1", found) || adb.isConnected) { "Could not connect to wireless debugging." }
                found
            }
            startOver(adb, target)
        } catch (e: Exception) {
            disconnect()
            if (e is NearbyDevicesNeededException || e is PortNotFoundException) throw e
            if (e.isPairingProblem()) {
                Pairing.forget(context) // e.g. "Forget" in Wireless debugging's paired devices list
                throw PairingRequiredException(e)
            }
            throw IOException(NOT_CONNECTED, e)
        }
    }

    private fun Throwable.isPairingProblem() = generateSequence(this) { it.cause }
        .any { it is AdbPairingRequiredException || it is AdbAuthenticationFailedException }

    /** Android 10 and older: the debugging port a computer turned on, tried on 127.0.0.1 (see the class comment). */
    private fun connectOverTcp(port: Int?, authorize: Boolean) {
        val adb = AdbIdentity(context, authorize)
        manager = adb
        cancelled = false
        try {
            // Refused at once on a closed port, so a phone that restarted since is told apart in milliseconds.
            val target = tcpPorts(port, Pairing.lastPort(context)).firstOrNull { listening(it) }
                ?: throw DebuggingPortClosedException()
            if (authorize) asking = adb
            val connected = try { adb.connect("127.0.0.1", target) || adb.isConnected } finally { asking = null }
            if (!connected) throw if (authorize) AllowNeededException(asked = true)
                else IOException("Android's debugging tool didn't answer.")
            // Plain adb has no TLS, and any app may listen on a free port, as 5555 is after a restart: whatever
            // answered gets the helper's key and every card request next. Only the shell can change Settings.Global.
            proveShell(target, run = { runToEnd(adb, it) },
                read = { Settings.Global.getString(context.contentResolver, PROBE_KEY) })
            startOver(adb, target)
        } catch (e: Exception) {
            disconnect()
            throw tcpFailure(e, cancelled)
        }
    }

    /** Starts the helper over the connected [adb], in the background if it can, and remembers [target] as the
     *  port that works. */
    private fun startOver(adb: AdbIdentity, target: Int) {
        val apk = context.applicationInfo.sourceDir
        try {
            link = startHelper(adb, apk)
            Pairing.markPaired(context, target)
            // The helper runs on its own now: ADB is only needed to start it again.
            runCatching { adb.close() }
            manager = null
            return
        } catch (e: Exception) {
            if (e.isPairingProblem()) throw e
            link?.close()
            link = null
            prefs.edit { putBoolean(HELPER_UNAVAILABLE, true) }
            Log.w(TAG, "Background card helper unavailable, using the ADB stream: ${e.javaClass.simpleName}")
        }
        val command = "CLASSPATH=${shellQuote(apk)} app_process /system/bin $HELPER_MAIN"
        val opened = adb.openStream(shellService(Build.VERSION.SDK_INT) + command)
        stream = opened
        reader = opened.openInputStream().bufferedReader(Charsets.UTF_8)
        writer = opened.openOutputStream().bufferedWriter(Charsets.UTF_8)
        check(isStreamHelper(rpc(JSONObject().put("op", "ping")))) { "Unexpected card helper." }
        Pairing.markPaired(context, target)
    }

    /** Runs the ADB [service] (a command) and waits up to [PROBE_SECONDS] for it to end; its output is dropped.
     *  Throws IOException when it can't start, or doesn't end in time: what answers may not be adbd, so nothing
     *  here waits on it for long. */
    private fun runToEnd(adb: AdbIdentity, service: String) {
        val task = rpcWorker.submit(Callable {
            adb.openStream(service).use { opened ->
                val input = opened.openInputStream()
                val buffer = ByteArray(512)
                var total = 0
                while (true) {
                    // libadb ends a stream with -1, or with "Stream closed." when nothing was left to read.
                    val n = try { input.read(buffer) } catch (e: IOException) { if (opened.isClosed) -1 else throw e }
                    if (n < 0) break
                    total += n
                    if (total > 65536 || Thread.interrupted()) throw IOException("Unexpected answer from adb")
                }
            }
        })
        try { task.get(PROBE_SECONDS, TimeUnit.SECONDS) } catch (e: Exception) {
            task.cancel(true) // libadb's waits end on the interrupt
            val cause = (e as? ExecutionException)?.cause ?: e
            throw cause as? IOException ?: IOException("Android's debugging tool didn't answer.", cause)
        }
    }

    /** Gives up waiting for the phone's "Allow USB debugging?" answer: [connect] then fails at once, with an
     *  [AllowNeededException]. Any thread; nothing happens when no connection is waiting. */
    fun cancelConnect() {
        val waiting = asking ?: return
        cancelled = true
        runCatching { waiting.close() } // closes its socket, which ends the wait
    }

    /** Starts the helper in the background over Wireless debugging, then connects to it. The ADB stream only
     *  carries the start: a fresh key goes in, the helper's port comes back, and the stream ends. The helper
     *  ignores the hang-up that follows (trap) and replaces any earlier one; it confirms both before answering. */
    private fun startHelper(adb: AdbIdentity, apk: String): HelperLink {
        val key = HelperLink.newKey()
        val command = "trap '' HUP PIPE; CLASSPATH=${shellQuote(apk)} exec app_process /system/bin " +
            "--nice-name=$HELPER_NAME $HELPER_MAIN serve"
        val opened = adb.openStream(shellService(Build.VERSION.SDK_INT) + command)
        val port = try {
            val input = opened.openInputStream().bufferedReader(Charsets.UTF_8)
            opened.openOutputStream().bufferedWriter(Charsets.UTF_8).apply {
                write(JSONObject().put("op", "serve").put("key", HelperLink.hex(key)).put("uid", Process.myUid()).toString())
                newLine()
                flush()
            }
            val answer = rpcWorker.submit(Callable {
                generateSequence { input.readLine() }.take(20).firstOrNull { it.startsWith("EOS:") }
            }).get(15, TimeUnit.SECONDS)?.let { JSONObject(it.substring(4)) } ?: throw IOException("Card helper didn't start")
            if (!answer.optBoolean("ok")) throw IOException(answer.optString("error", "Card helper didn't start"))
            answer.getInt("port")
        } finally { runCatching { opened.close() } }
        forgetHelper() // the new helper has replaced the saved one; it exits by itself if the app never reaches it
        val started = HelperLink.open(port, key)
        // Answers after its stream ended: the start held.
        val hello = started.call(JSONObject().put("op", "ping"), 10)
        if (hello.optInt("uid") != 2000) { started.close(); throw IOException("Unexpected card helper.") }
        prefs.edit { putInt(HELPER_PORT, port); putString(HELPER_KEY, HelperLink.hex(key)); putBoolean(HELPER_UNAVAILABLE, false) }
        return started
    }

    /** Connects to the background helper started earlier. Forgets it when nothing answers on its port any more
     *  (the phone restarted) or what answers can't prove it holds the key. Keeps it after a mere time-out, a hang-up
     *  (a helper with all its connections taken just closes new ones) or anything going wrong once it proved the key:
     *  losing a helper that still runs costs card access until Wireless debugging is back, and one that really
     *  went is told apart next time, as nothing accepts the connection then. */
    private fun openSaved(): Boolean {
        val (port, key) = saved() ?: return false
        var proven = false
        try {
            val opened = HelperLink.open(port, key)
            proven = true
            // Left from before an update of this app: it runs the old code and exits within 30 s, maybe in the
            // middle of a change. End it now; connect() starts the new one, over Wireless debugging.
            if (opened.apk != context.applicationInfo.sourceDir) {
                runCatching { opened.call(JSONObject().put("op", "exit"), 5) }
                opened.close()
                forgetHelper()
                return false
            }
            link = opened
            if (rpc(JSONObject().put("op", "ping")).optInt("uid") == 2000) return true
        } catch (e: Exception) {
            Log.i(TAG, "Background card helper not reachable: ${e.javaClass.simpleName}")
            if (proven || generateSequence<Throwable>(e) { it.cause }
                    .any { it is SocketTimeoutException || it is HelperLink.HungUpException }) { disconnect(); return false }
        }
        disconnect()
        forgetHelper()
        return false
    }

    private fun saved(): Pair<Int, ByteArray>? {
        val port = prefs.getInt(HELPER_PORT, 0)
        val key = prefs.getString(HELPER_KEY, null) ?: return null
        return if (port in 1..65535 && key.length == 64) port to HelperLink.unhex(key) else null
    }

    private fun forgetHelper() = prefs.edit { remove(HELPER_PORT); remove(HELPER_KEY) }

    /** Ends card access completely, the background helper included: nothing keeps running for this app. */
    fun stop() {
        val running = link ?: saved()?.let { (port, key) -> runCatching { HelperLink.open(port, key) }.getOrNull() }
        runCatching { running?.call(JSONObject().put("op", "exit"), 5) }
        running?.close()
        forgetHelper()
        disconnect()
    }

    /** Connects if Wireless debugging still listens on [port]. Pairing problems aren't IOExceptions, so they
     *  still reach [connect]'s own handling; anything else drops the half-open attempt before the search. */
    private fun reuse(adb: AdbIdentity, port: Int): Boolean {
        val ok = try { adb.connect("127.0.0.1", port) || adb.isConnected } catch (_: IOException) { false }
        if (!ok) runCatching { adb.disconnect() }
        return ok
    }

    fun ping() { rpc(JSONObject().put("op", "ping")) }

    /** Once Android has the eSIM [iccid] active in [slot]: makes it the mobile data SIM ([data]) and turns on
     *  its data roaming ([roaming]). Not applied while the phone hasn't picked it up yet, as just after a switch. */
    fun useSim(slot: Int, iccid: String, data: Boolean, roaming: Boolean): SimSettingsResult =
        SimSettingsResult.of(rpc(SimSettingsResult.request(slot, iccid, data, roaming)))

    /** How long one request may take. A card answers a read in well under a second, but installing and
     *  switching can take many: 45 s, except inside [reading]. Only touched on the card thread. */
    private var timeoutSeconds = 45L

    /** Runs [block], which only reads the card, with a 15 s limit per request: a card that stopped answering
     *  is noticed (and card access restarted) a lot sooner than during a change. */
    fun <T> reading(block: () -> T): T {
        val before = timeoutSeconds
        timeoutSeconds = 15
        try { return block() } finally { timeoutSeconds = before }
    }

    @Synchronized
    private fun rpc(request: JSONObject): JSONObject {
        val response = link?.let { helper ->
            try { helper.call(request, timeoutSeconds) } catch (e: Exception) {
                disconnect()
                // Never retry an APDU automatically: it may already have changed the card.
                throw ApduException("Card connection lost or timed out. Reconnect and refresh before trying again.", e)
            }
        } ?: overStream(request)
        if (!response.getBoolean("ok")) throw helperError(response.optString("error"))
        return response
    }

    private fun overStream(request: JSONObject): JSONObject {
        val input = reader ?: throw ApduException("Card access disconnected. Reconnect first.")
        val output = writer ?: throw ApduException("Card access disconnected. Reconnect first.")
        val future = rpcWorker.submit(Callable {
            output.write(request.toString())
            output.newLine()
            output.flush()
            var response: JSONObject? = null
            var skipped = 0
            while (response == null) {
                val line = input.readLine() ?: throw IOException("Card helper exited")
                if (line.startsWith("EOS:")) response = JSONObject(line.substring(4))
                else if (++skipped > 20) throw IOException("Unexpected card helper output")
            }
            response
        })
        return try { future.getWithGrace(timeoutSeconds) } catch (e: Exception) {
            future.cancel(true)
            disconnect()
            // Never retry an APDU automatically: it may already have changed the card.
            throw ApduException("Card connection lost or timed out. Reconnect and refresh before trying again.", e)
        }
    }

    override fun slots(): List<SlotInfo> = rpc(JSONObject().put("op", "slots")).getJSONArray("slots").let { all ->
        List(all.length()) { i -> all.getJSONObject(i).let { SlotInfo(it.optBoolean("euicc"), it.optBoolean("removable", true)) } }
    }

    private fun request(op: String, slot: Int, port: Int, channel: Int = 0) = JSONObject()
        .put("op", op).put("slot", slot).put("port", port).put("channel", channel)

    override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
        val response = rpc(request("open", slot, port).put("aid", aidHex))
        return OpenResult(response.getInt("channel"), response.getInt("status"),
            response.getString("select").takeIf { it.isNotEmpty() }?.let(Hex::decode))
    }

    override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String): String =
        rpc(request("transmit", slot, port, channel).put("cla", cla).put("ins", ins)
            .put("p1", p1).put("p2", p2).put("p3", p3).put("data", dataHex)).getString("response")

    override fun closeChannel(slot: Int, port: Int, channel: Int) {
        if (!isReady()) return
        // Switching eSIMs restarts the card, which drops its channels, so this close can fail. That
        // is no reason to end card access; rpc() already disconnects if the helper itself is gone.
        try { rpc(request("close", slot, port, channel)) } catch (_: Exception) { }
    }

    /** Leaves a background helper running, for next time. */
    fun disconnect() {
        link?.close()
        link = null
        val previous = stream
        stream = null
        reader = null
        writer = null
        runCatching { previous?.close() }
        runCatching { manager?.close() }
        manager = null
    }

    override fun close() { disconnect(); rpcWorker.shutdownNow() }

    companion object {
        private const val TAG = "CardAccess"
        private const val PREFS = "card-helper"
        private const val HELPER_PORT = "port"
        private const val HELPER_KEY = "key"
        private const val HELPER_UNAVAILABLE = "unavailable"
        /** adb/HelperServer.NAME: how the helper shows in ps, and how a new one finds the old one. */
        private const val HELPER_NAME = "esimonsim-card"
        internal const val NOT_CONNECTED = "Couldn't connect to Wireless debugging. Tap \"Reconnect\" to try again. If " +
            "it keeps failing, connect with its port number below."

        internal fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

        /** The grace of [HelperLink.call], for the ADB stream: a second wait, as a longer one wouldn't help a process
         *  Android froze, which wakes with any limit long gone and can run before the answer has been read. */
        internal fun <T> Future<T>.getWithGrace(timeoutSeconds: Long): T =
            try { get(timeoutSeconds, TimeUnit.SECONDS) }
            catch (_: TimeoutException) { get(HelperLink.GRACE_SECONDS, TimeUnit.SECONDS) }

        /** A request the helper answered with an error, in words for people. A restarted phone process (see CardShell)
         *  needs words of its own: the card is fine, and the next try reaches the new process. */
        internal fun helperError(raw: String): ApduException {
            if (!raw.startsWith("DeadObjectException")) return ApduException.fromHelper(raw)
            Log.w(TAG, "Phone process restarted")
            return ApduException("Android's SIM service restarted. Try again in a moment.")
        }

        /** Whether [hello] is the helper's on the ADB stream. On some phones debugging runs as root, as after
         *  "adb root" on a debug build: the helper may too here, as it ends with its stream. Only the background one
         *  has to be the shell (see CardShell.main). The uid is what it says itself, so this is no security check. */
        internal fun isStreamHelper(hello: JSONObject): Boolean {
            val uid = hello.getInt("uid")
            return hello.getInt("version") == 1 && (uid == 2000 || uid == 0)
        }

        /** The port setup has people turn on with "adb tcpip", on Android 10 and older; LineageOS's "ADB over
         *  network" uses it too. */
        const val TCP_PORT = 5555

        private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** Ports to try on Android 10 and older, in order: the one typed; or the one that worked last, then
         *  [TCP_PORT]. */
        internal fun tcpPorts(typed: Int?, last: Int?): List<Int> =
            if (typed != null) listOf(typed) else listOfNotNull(last, TCP_PORT).distinct()

        /** Whether something accepts connections on [port] of this phone. A closed port is refused at once. */
        private fun listening(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 500) }
            true
        } catch (_: IOException) { false }

        /** The ADB service that runs a command. Android 6's "shell:" runs it in a terminal, which echoes what the app
         *  sends and turns line ends into "\r\n"; "exec:" runs it plain on every version. Wireless debugging (11+)
         *  keeps "shell:", plain there too. */
        internal fun shellService(sdk: Int) = if (sdk < 30) "exec:" else "shell:"

        /** Where setup sends people when another app holds [TCP_PORT] ([NotDebuggingToolException]). */
        const val SPARE_PORT = 5556

        /** The Settings.Global key [proveShell] writes, for a moment. */
        internal const val PROBE_KEY = "esimonsim_probe"

        /** How long each command of [proveShell] may take: "settings" starts a Java program, slow on old phones. */
        private const val PROBE_SECONDS = 10L

        /** Android 10 and older: proves that what answered on [port] is Android's debugging service, before it gets
         *  anything worth having. It has the shell write a fresh random value to Settings.Global, which apps can't
         *  (WRITE_SECURE_SETTINGS), and the app reads it back ([read]); then removes it again. [run] runs an ADB
         *  service and returns once it ended. Throws [NotDebuggingToolException] when the value isn't there, and
         *  [run]'s IOException when a command doesn't end. */
        internal fun proveShell(port: Int, run: (String) -> Unit, read: () -> String?) {
            val nonce = HelperLink.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
            run("exec:settings put global $PROBE_KEY $nonce")
            if (read() != nonce) throw NotDebuggingToolException(port)
            // Left behind it's harmless: the next proof writes a new value anyway.
            runCatching { run("exec:settings delete global $PROBE_KEY") }
        }

        /** What a failed connection on Android 10 and older means for people. [cancelled]: the app stopped waiting
         *  for the phone's answer. */
        internal fun tcpFailure(e: Exception, cancelled: Boolean): IOException = when {
            e is DebuggingPortClosedException || e is AllowNeededException || e is NotDebuggingToolException -> e
            cancelled -> AllowNeededException(asked = true, cancelled = true, cause = e)
            // The phone answered the app's key with a new question: it doesn't know the key.
            generateSequence<Throwable>(e) { it.cause }.any { it is AdbAuthenticationFailedException } ->
                AllowNeededException(asked = false, cause = e)
            else -> IOException("Couldn't connect to Android's debugging tool. Try again in a moment.", e)
        }
    }
}
