package com.esimonsim.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.esimonsim.adb.AdbIdentity
import com.esimonsim.adb.DebugSettings
import com.esimonsim.adb.PairingFlow
import com.esimonsim.apdu.AccessMethod
import com.esimonsim.apdu.AllowNeededException
import com.esimonsim.apdu.ApduException
import com.esimonsim.apdu.CardAccess
import com.esimonsim.apdu.ConnectCancelledException
import com.esimonsim.apdu.DebuggingPortClosedException
import com.esimonsim.apdu.NearbyDevicesNeededException
import com.esimonsim.apdu.PairingRequiredException
import com.esimonsim.apdu.PortNotFoundException
import com.esimonsim.apdu.RootApduBackend
import com.esimonsim.apdu.SetupNeededException
import com.esimonsim.apdu.ShizukuApduBackend
import com.esimonsim.apdu.ShizukuStatus
import com.esimonsim.data.ProfileStore
import com.esimonsim.es9.Es9Client
import com.esimonsim.lpa.ActivationCode
import com.esimonsim.lpa.AlreadyOnCardException
import com.esimonsim.lpa.CardFacts
import com.esimonsim.lpa.ConfirmationCodeNeededException
import com.esimonsim.lpa.EuiccProfile
import com.esimonsim.lpa.Lpa
import com.esimonsim.lpa.SlotLook
import com.esimonsim.lpa.SlotReach
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class AccessStatus { SETUP, CONNECTING, READY }

/** A card operation in progress; [progress] is 0..1 when it can be measured. [step] is set while installing.
 *  [cancellable]: it only reads the card, so it can stop early; [cancelling] once asked to. */
data class Busy(val label: String, val progress: Float? = null, val step: Lpa.Step? = null,
                val cancellable: Boolean = false, val cancelling: Boolean = false)

/** What [LpaViewModel.priorInstall] found. */
data class PriorInstall(val install: ProfileStore.Install, val onCard: EuiccProfile?, val onOther: CardRow?)

/** An activation code this app installed before; installing waits for the user's answer. */
data class DuplicateCode(
    val code: ActivationCode,
    val confirmationCode: String?,
    val install: ProfileStore.Install,
    /** Still on the card: installing again can't work. */
    val onCard: EuiccProfile?,
)

data class LpaUi(
    val access: AccessStatus = AccessStatus.SETUP,
    val paired: Boolean = false,
    /** The phone settings card access depends on. */
    val settings: DebugSettings.State = DebugSettings.State(developerOptions = false, wifi = false, wirelessDebugging = null),
    val pairing: PairingFlow.State = PairingFlow.State(),
    /** The phone dropped the app's pairing (e.g. 7 days unused, or "Forget" in Settings). */
    val pairingExpired: Boolean = false,
    /** Android 10 and older: the phone didn't allow this app's debugging access when it asked, or no longer
     *  remembers allowing it. Connecting again lets it ask. */
    val allowAgain: Boolean = false,
    /** Android 10 and older: nothing answered on the debugging port, as after a restart: it needs a computer again. */
    val portClosed: Boolean = false,
    /** The [error] that [allowAgain] or [portClosed] came with: their screens say it in their own words, and show any
     *  other error, such as root being refused, as usual. */
    val explainedError: String? = null,
    /** Connecting, and the phone may ask "Allow USB debugging?" for this app meanwhile (Android 10 and older). */
    val askingAllow: Boolean = false,
    /** What the phone shows as this app's "RSA key fingerprint" when it asks; Android 10 and older, once read. */
    val adbKey: String? = null,
    /** Android 17: finding Wireless debugging's new port needs "Nearby devices", which this app may not use yet. */
    val nearbyNeeded: Boolean = false,
    /** Direct card access, Android 10 and older: a switch couldn't set mobile data and roaming because the phone no
     *  longer allows this app's debugging access. Settings › Mobile data and roaming allows it again. */
    val simAllowNeeded: Boolean = false,
    /** How often [LpaViewModel.setUpSimSettings] succeeded, so its screen can close when it does. */
    val simSettingsSetUps: Int = 0,
    /** The user stopped card access; connect only when asked, also after the app restarts. */
    val stopped: Boolean = false,
    /** Connected through the helper running in the background, which needs no Wireless debugging or Wi-Fi. */
    val background: Boolean = false,
    /** The background helper didn't start on this phone, so card access always needs Wireless debugging. */
    val helperUnavailable: Boolean = false,
    /** How the card is reached: the way that worked last, also while card access is stopped. Null before any did. */
    val method: AccessMethod? = null,
    /** The way chosen in this app's Settings, which connecting tries first; null: none, so the order is automatic. */
    val preferred: AccessMethod? = null,
    /** Why card access isn't through [preferred] (see [CardAccess.preferredFailure]). */
    val preferredFailure: Exception? = null,
    /** The last try at direct access was turned down by the card, not just slow or empty. */
    val directRefused: Boolean = false,
    /** Direct access is worth offering on this phone ([CardAccess.directAvailable]). Read off the main thread. */
    val directAvailable: Boolean = false,
    /** Two or more SIM slots, so a card that direct access can't open may sit in another. Read off the main thread. */
    val multiSim: Boolean = false,
    /** The pane that sets up ADB to switch card access to it is open. The way in use carries on meanwhile. */
    val accessSetup: Boolean = false,
    /** How often card access switched while connected, so Settings opened just for that can close once it did. */
    val accessSwitches: Int = 0,
    /** The phone's SIM settings can follow a switch; with direct access only through Shizuku or a pairing. */
    val canSetUpSim: Boolean = true,
    /** This phone has an su the app might use, and whether root was turned down for it. */
    val rootFound: Boolean = false,
    val rootDeclined: Boolean = false,
    /** Android sees no SIM in any slot. Only a hint: an empty eSIM card may count as none. */
    val noSim: Boolean = false,
    /** Whether this phone has Shizuku, and how it stands with this app. */
    val shizuku: ShizukuStatus = ShizukuStatus.ABSENT,
    /** Shizuku's question to allow this app is up. */
    val shizukuAsking: Boolean = false,
    val loading: Boolean = false,
    val busy: Busy? = null,
    val eid: String? = null,
    val slot: Int = -1,
    /** The number the phone goes by for [slot], from 0, once a look through the slots has said; -1 before. */
    val sim: Int = -1,
    /** A line per card in the phone, the one in view among them. As last looked: [rows] has the one in view up to date. */
    val cards: List<CardRow> = emptyList(),
    /** The first look through the slots since card access came up is still going: another card may yet turn up. */
    val lookingForCards: Boolean = true,
    /** The EID of the phone's own built-in eSIM chip, the only one found: held back, so [eid] stays null, until people
     *  say it's their removable card. */
    val builtInChip: String? = null,
    /** With [builtInChip]: another slot holds a card the phone isn't using, likely the one people are looking for. */
    val idleSlot: Int = -1,
    /** With [builtInChip]: another slot holds an eSIM card that didn't answer just now (see [Lpa.quietSlot]). */
    val quietSlot: Int = -1,
    /** Active profile first. */
    val profiles: List<EuiccProfile> = emptyList(),
    /** Notes saved on this phone, by ICCID. */
    val notes: Map<String, String> = emptyMap(),
    /** Same-name warnings dismissed, by their eSIMs' sorted ICCIDs. */
    val dismissedSameName: Set<String> = emptySet(),
    /** The advice to turn on USB debugging before travelling ([usbTip]) was dismissed. */
    val usbTipDismissed: Boolean = false,
    /** ICCIDs of eSIMs hidden from the list on this phone. A hidden eSIM still shows while it's the active one. */
    val hidden: Set<String> = emptySet(),
    /** ICCID of the profile whose details sheet is open. */
    val details: String? = null,
    /** Free space and versions, read when Details opens. */
    val cardInfo: Lpa.CardInfo? = null,
    /** The card's own name as last remembered, shown until [cardInfo] has the one read now. */
    val knownCardName: CardFacts.Name? = null,
    val cardInfoLoading: Boolean = false,
    /** Shown inline during setup; with [errorTitle] set, shown as a dialog instead. */
    val error: String? = null,
    val errorTitle: String? = null,
    /** One-shot snackbar text. */
    val info: String? = null,
    /** With [info]: offer to open the card's SIM settings, which the app couldn't change itself. */
    val infoSimSettings: Boolean = false,
    /** The card's SIM subscription before the last switch: a SIM settings link never opens that one's page. */
    val subBeforeSwitch: Int = -1,
    /** Install dialog is open. It stays open through a failed install so input isn't lost. */
    val adding: Boolean = false,
    /** Closing the form leaves what's in it, once: the error may have sent people to the list, to delete an eSIM, say. */
    val keepDraft: Boolean = false,
    /** The provider wanted a confirmation code for this eSIM and got none: the form opens its field. */
    val ccNeeded: ActivationCode? = null,
    val duplicate: DuplicateCode? = null,
    /** Which card the Add eSIM form installs on, when there are several: the one in view if it was opened in the app; a
     *  code from another app has none until people pick. Never a guess: an activation code often works only once. */
    val addTarget: String? = null,
    /** Just downloaded; the UI offers to name it and switch to it. */
    val installed: EuiccProfile? = null,
    /** Last chosen in the switch dialog: whether the phone's SIM settings follow a switch. */
    val switchSetup: ProfileStore.SwitchSetup = ProfileStore.SwitchSetup(),
    /** An activation code from another app (a share, a link), waiting to fill in the Add eSIM form. */
    val incoming: String? = null,
    /** eSIMs providers have sent to this card, once checked; the card's default provider comes first. */
    val waiting: List<Lpa.WaitingEsim>? = null,
) {
    /** Said before people travel: abroad without Wi-Fi only USB debugging keeps the background helper up. It may read
     *  as unknown on Android 17; a helper alive with Wireless debugging off shows it's on. With nothing to switch to,
     *  there's nothing to warn about. */
    val usbTip get() = method == AccessMethod.ADB && background && !settings.legacy &&
        settings.usbDebugging != true && settings.wirelessDebugging != false && profiles.any { !it.enabled }

    val rows get() = withView(cards, slot, eid, profiles, sim)
    /** Cards this app can open, unlike one in a slot the phone isn't using. */
    val manageable get() = cards.count { it.eid != null }
    /** The SIM numbers, from 1, of eSIM cards that direct access can't open ([CardRow.locked]). */
    val lockedSims get() = cards.filter { it.locked }.map { it.sim + 1 }
    /** What the card switcher offers. Under the Add eSIM form, no card only another way in opens: that switch would
     *  go on under the form. */
    val menuRows get() = if (adding || installed != null) rows.filterNot { it.locked } else rows
    val switcher get() = cards.size >= 2 && (!adding || cards.count { !it.locked } >= 2)
    /** The SIM number people know the card in view by, from 1. */
    val simNumber get() = (if (sim >= 0) sim else slot) + 1
    /** Android's own number for [slot], from 0, or -1: only OMAPI's slots are Android's, and the helper says otherwise. */
    val androidSlot get() = if (sim >= 0) sim else if (method == AccessMethod.DIRECT) slot else -1
    /** The Add eSIM form waits for people to say which card: there are several and none was chosen, or the one chosen
     *  isn't the one in view any more. Never a guess, as an activation code often works only once. */
    val pickCard get() = !loading && if (addTarget == null) manageable >= 2 else eid != null && eid != addTarget
    /** The card to install on is settled. For the form's button and the install itself, so nothing gets round it. */
    val mayInstall get() = eid != null && !loading && !lookingForCards &&
        (if (addTarget == null) manageable < 2 else eid == addTarget)

    /** The provider wants a confirmation code for [ac]: to the form that's a failed install like any other. */
    fun ccAsked(ac: ActivationCode) = copy(ccNeeded = ac, keepDraft = true)

    /** The Add eSIM form opening. A kept one still has the code the provider asked about, so its ask stays too and
     *  Install waits for the answer; trying again without it would only go back to the provider to be asked again. */
    fun addOpened(target: String? = eid) = copy(adding = true, ccNeeded = ccNeeded.takeIf { keepDraft }, duplicate = null,
        keepDraft = false, addTarget = target)
}

class LpaViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val backend = CardAccess(app)
    // The feature exists from Android 9; older phones simply don't report it. Below Android 10 the helper reaches the
    // card through Android's older calls, which can't name every SIM slot (see SlotReach); OMAPI can.
    /** The card in view. Moves only on the main thread and behind [working], so work behind that gate can just read it;
     *  what runs outside it (notices, Details) takes it first. */
    @SuppressLint("InlinedApi")
    @Volatile private var lpa = Lpa(backend, phoneHasEsim = app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC),
        slotReach = { if (Build.VERSION.SDK_INT >= 29 || backend.method == AccessMethod.DIRECT) SlotReach.ALL
            else if (Build.VERSION.SDK_INT >= 24) SlotReach.FIRST_OR_ACTIVE else SlotReach.FIRST_ONLY },
        accessRulesApply = { backend.method == AccessMethod.DIRECT })
    private val store = ProfileStore(app)

    /** A card not in view: [lpa] reaches it, and [profiles] are its eSIMs as last read, if they could be. */
    private class Kept(val lpa: Lpa, val profiles: List<EuiccProfile>?)
    // On the main thread only, like what goes with them.
    private var kept = emptyMap<String, Kept>()
    /** The way in that the addresses in [lpa] and [kept] are for. */
    private var cardsVia: AccessMethod? = null
    /** [com.esimonsim.apdu.SlotInfo.token]s already looked behind, since card access came up. */
    private var seen = emptyMap<Int, String>()
    /** The number the phone goes by for each slot, as the last look said, for the way in [cardsVia]. Kept when card
     *  access drops, so a card that comes back keeps its number before the next look has said it. */
    private var simOf = emptyMap<Int, Int>()
    /** Slots a look lost card access to. Coming back to them would drop it again and again: only Reconnect tries them. */
    private var hungSlots = emptySet<Int>()
    /** Goes up when what a look is based on no longer holds. It looks outside [working], or every tap in the meantime
     *  would do nothing, so its answer can arrive after that. */
    private var lookEpoch = 0
    private var looking: Job? = null
    /** Where the view goes once card access switched to another way, when the next look has found the cards that way. */
    private var landing: Landing? = null
    /** The SIM (from 0) of the card the pane for switching to ADB was opened for, or -1: see [chooseAccess]. */
    private var switchFor = -1
    /** Read once, off the main thread, for Settings: nobody else needs it. */
    private val multiSim by lazy { simSlots(app) >= 2 }
    // PairingFlow outlives this: a pairing from before it doesn't count as a new one below.
    private val _ui = MutableStateFlow(LpaUi(paired = backend.wasPaired, settings = DebugSettings.read(app),
        pairing = PairingFlow.state.value, stopped = store.stopped(), preferred = backend.preferred,
        helperUnavailable = backend.helperUnavailable, method = backend.method, rootFound = backend.rootFound,
        rootDeclined = backend.rootDeclined, notes = store.notes(), dismissedSameName = store.dismissedSameName(), hidden = store.hidden(),
        usbTipDismissed = store.usbTipDismissed(), noSim = noSimInPhone(app),
        switchSetup = store.switchSetup(),
        incoming = saved[INCOMING], adding = saved[ADDING] ?: false, addTarget = saved[ADD_TARGET]))
    val ui: StateFlow<LpaUi> = _ui.asStateFlow()
    private val working get() = _ui.value.let { it.loading || it.busy != null || it.access == AccessStatus.CONNECTING }

    init {
        // Android can stop the app while people fetch a code from their email: the typed form comes back with this.
        viewModelScope.launch { _ui.map { it.adding }.distinctUntilChanged().collect { saved[ADDING] = it } }
        viewModelScope.launch { _ui.map { it.addTarget }.distinctUntilChanged().collect { saved[ADD_TARGET] = it } }
        viewModelScope.launch {
            _ui.map { it.access }.distinctUntilChanged().collect {
                // A switch needs the way in use to carry on meanwhile.
                if (it != AccessStatus.READY) { forgetCards(); landing = null; _ui.update { u -> u.copy(accessSetup = false) } }
            }
        }
        viewModelScope.launch { _ui.map { it.stopped }.distinctUntilChanged().drop(1).collect { store.setStopped(it) } }
        // Settings are read directly, so a switched-off Wireless debugging shows at once instead of
        // after a failed connection attempt; and the app connects as soon as it is switched on.
        viewModelScope.launch {
            // Off the main thread: it reads the settings again and signs up for changes, on the way to the first frame.
            DebugSettings.observe(app).flowOn(Dispatchers.Default).collect { s ->
                val could = _ui.value.settings.mayConnect
                _ui.update { it.copy(settings = s) }
                // Switched back on: a real change (a new port, too), so an earlier failed attempt no longer holds it back.
                // On Android 10 and older the debugging port opens a moment after USB debugging comes on.
                if (!could && s.mayConnect) { failedWith = null; autoConnect(portMayOpen = s.legacy) }
            }
        }
        viewModelScope.launch {
            PairingFlow.state.collect { p ->
                val newlyPaired = p.pairedCount > _ui.value.pairing.pairedCount
                _ui.update { it.copy(pairing = p, paired = it.paired || newlyPaired,
                    pairingExpired = it.pairingExpired && !newlyPaired) }
                // Also just for SIM settings, with direct card access: the switch dialog offers them from now on. Or
                // paired to switch card access to Wireless debugging: that's next.
                if (newlyPaired) {
                    _ui.update { it.copy(stopped = false, canSetUpSim = backend.canSetUpSim) }
                    // After whatever the card is doing, such as a refresh: the pairing was for this.
                    if (_ui.value.accessSetup) launch {
                        _ui.first { !it.loading && it.busy == null }
                        if (_ui.value.accessSetup) finishAccessSetup()
                    } else autoConnect(justPaired = true)
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(2000)
                if (!working && _ui.value.access == AccessStatus.READY && !backend.isReady()) {
                    // Usually Wireless debugging switched off (restart, Wi-Fi change): its own screen explains.
                    // If it's still on, reconnect straight away. An open Add eSIM form is kept for afterwards.
                    // A pairing lost mid-operation (connect() forgot it) goes straight to pairing again.
                    val s = DebugSettings.read(getApplication())
                    val paired = backend.wasPaired
                    _ui.update { it.copy(access = AccessStatus.SETUP, background = false, eid = null, profiles = emptyList(),
                        details = null, waiting = null, settings = s, paired = paired,
                        // Only if there was a pairing to lose: root and Shizuku drop without one.
                        pairingExpired = it.pairingExpired || (!paired && backend.method == AccessMethod.ADB),
                        error = if ((s.mayConnect && paired) || backend.hasHelper || backend.withoutSetup)
                            "Card access stopped. Reconnecting…" else null) }
                    autoConnect()
                }
            }
        }
        // Shizuku hands this app its connection in its own time, also long after the app started: when someone just
        // started it, say, or allowed this app in it.
        viewModelScope.launch { backend.shizukuChanges.collect { refreshShizuku() } }
        // Shown during setup so people can tell the phone's question is this app's. Reading it (or, the first time,
        // making the key) takes a moment: not on the main thread.
        if (Build.VERSION.SDK_INT < 30) viewModelScope.launch {
            val key = withContext(Dispatchers.IO) { runCatching { AdbIdentity.fingerprint(app) }.getOrNull() }
            _ui.update { it.copy(adbKey = key) }
        }
        // Android keeps the Theme setting from 12 on; a phone updated from 11 has it only here (see ThemeSetting).
        if (Build.VERSION.SDK_INT >= 31) viewModelScope.launch(Dispatchers.IO) { ThemeSetting.resync(app) }
        autoConnect()
    }

    override fun onCleared() {
        // A question or a check for eSIMs nobody here waits for any more would hold the card thread, with close() and
        // the next start of the app behind it. Unlike an install, a check has no reason to finish.
        stopWaiting(backend::cancelConnect, checkCancel)
        worker.execute { backend.close() }
    }

    /** On return to the app, e.g. from Settings: pick up changes and connect if possible. */
    fun refreshConnection() {
        inApp = true
        // The first time is the app starting, and the above has only just done all this.
        if (firstResume) { firstResume = false; return }
        // Allowed in a root app that hides su until then (KernelSU, APatch): worth asking for root again.
        if (backend.rootAppeared() || backend.shizukuQuestionWaits) failedWith = null
        _ui.update { it.copy(settings = DebugSettings.read(getApplication()), rootFound = backend.rootFound,
            noSim = noSimInPhone(getApplication())) }
        autoConnect()
        refreshShizuku()
        // A card put in meanwhile. Android keeps the slot list in memory, so this costs the card nothing.
        if (!working) lookForCards(quick = true)
    }

    private var firstResume = true

    /** Someone left the app: from now on Shizuku's question would open over whatever else they're doing. */
    fun leftApp() { inApp = false }
    private var inApp = true

    /** Shizuku's state, and connecting if it just became usable. Started in or allowed from Shizuku's own app, this
     *  often only shows on return to this app: that sends no word. Also what Settings shows that costs a call to read,
     *  and what the last connection found out about the ways in. */
    private fun refreshShizuku() {
        viewModelScope.launch {
            val appeared = withContext(Dispatchers.IO) {
                val appeared = backend.shizukuAppeared()
                val status = backend.shizukuStatus
                val canSetUpSim = backend.canSetUpSim
                val asking = backend.shizukuAsking
                val direct = backend.directAvailable
                val slots = multiSim
                _ui.update { it.copy(shizuku = status, canSetUpSim = canSetUpSim, shizukuAsking = asking,
                    directAvailable = direct, multiSim = slots, directRefused = backend.directRefused,
                    preferredFailure = backend.preferredFailure) }
                appeared
            }
            if (appeared) shizukuPending = true
            if (shizukuPending && !working) { shizukuPending = false; failedWith = null; autoConnect() }
        }
    }

    /** Shizuku became usable while a connection was under way, which may fail without having looked at it again. */
    private var shizukuPending = false

    /** The settings when connecting last failed: coming back to the app with nothing changed doesn't
     *  start another wait, and leaves the Reconnect screen (and anything typed there) as it was. */
    private var failedWith: DebugSettings.State? = null

    /** Connect without being asked, when nothing is known to be in the way. Wireless debugging can
     *  take a moment to accept connections after it is switched on, so one failure is retried. A helper
     *  left running in the background answers without it, so it's tried even with no Wi-Fi. So are the
     *  ways in that need no setup (the card itself, root), until they are ruled out. [portMayOpen]: USB debugging
     *  just came on (Android 10 and older), so a closed debugging port is worth that retry too. */
    private fun autoConnect(justPaired: Boolean = false, ready: String? = null, portMayOpen: Boolean = false) {
        val ui = _ui.value
        val canStart = backend.wasPaired && ui.settings.mayConnect
        if (ui.stopped || working || ui.access != AccessStatus.SETUP ||
            !(canStart || backend.hasHelper || backend.withoutSetup)) return
        // A saved helper is worth asking again each time (over loopback a dead one fails at once); anything else
        // waits for a change in settings after a failure.
        if (!justPaired && ((ui.settings == failedWith && !backend.hasHelper) || PairingFlow.state.value.active)) return
        startConnection(retry = true, ready = if (justPaired) "Card access is ready" else ready, portMayOpen = portMayOpen) {
            backend.connect(viaAdb = canStart || backend.hasHelper, mayAsk = inApp)
        }
    }

    /** "Reconnect": every way in again, the ones ruled out earlier included. On Android 10 and older also the
     *  first connection after setup, which the phone asks to allow. [forAdb]: tapped on ADB's own screens, so the
     *  phone may ask also behind a way chosen in this app's Settings (see [CardAccess.connect]). */
    fun connect(portText: String = "", forAdb: Boolean = true) {
        if (working) return
        val port = if (portText.isBlank()) null else parsePort(portText) ?: return
        _ui.update { it.copy(stopped = false) }
        hungSlots = emptySet()
        store.clearNoManagers()
        val asks = forAdb || port != null || backend.preferred == null || backend.preferred == AccessMethod.ADB
        startConnection(askingAllow = Build.VERSION.SDK_INT < 30 && asks) {
            backend.connect(port, again = true, forAdb = forAdb)
        }
    }

    /** "Check again" on first-run setup, for a card put in since: never ADB, so nothing is asked of the phone, but
     *  the ways that need no setup get another try. */
    fun checkAgain() {
        if (working || PairingFlow.state.value.active) return
        _ui.update { it.copy(stopped = false) }
        startConnection {
            try { backend.connect(viaAdb = false, again = true) }
            catch (e: SetupNeededException) {
                throw SetupNeededException(e.message ?: "Still no card this app can open by itself. If it's in, carry " +
                    "on with the steps below.")
            }
        }
    }

    /** Set when people stopped waiting for the phone to allow this app: no error to show for that. */
    @Volatile private var allowCancelled = false

    /** "Cancel" while the phone asks to allow this app (Android 10 and older). */
    fun cancelConnect() {
        allowCancelled = true
        viewModelScope.launch(Dispatchers.IO) { backend.cancelConnect() }
    }

    /** Settings › Mobile data and roaming with direct card access, on Android 10 and older: the first
     *  connection over ADB, which the phone asks to allow; or the one after the phone forgot it. Card access
     *  itself stays direct. */
    fun setUpSimSettings() {
        if (working || _ui.value.access != AccessStatus.READY) return
        allowCancelled = false
        _ui.update { it.copy(busy = Busy("Waiting for your phone to allow this app…", cancellable = true), error = null,
            errorTitle = null, info = null) }
        viewModelScope.launch {
            val error = try { withContext(cardDispatcher) { backend.setUpSimSettings() }; null }
                catch (e: CancellationException) { throw e } catch (e: Exception) { e }
            _ui.update { it.copy(busy = null, paired = backend.wasPaired, canSetUpSim = backend.canSetUpSim,
                allowAgain = error is AllowNeededException, portClosed = error is DebuggingPortClosedException,
                explainedError = error?.takeIf { e -> e is AllowNeededException || e is DebuggingPortClosedException }?.message,
                simAllowNeeded = it.simAllowNeeded && error != null,
                simSettingsSetUps = it.simSettingsSetUps + if (error == null) 1 else 0,
                error = if (error == null || (error is AllowNeededException && error.cancelled)) null
                    else error.message ?: "Couldn't connect. Try again.",
                info = if (error == null) "This app can now set mobile data and roaming after a switch" else null) }
        }
    }

    /** "Use Shizuku": asks it to allow this app, also after a refusal. With direct card access, which stays, it's only
     *  for mobile data and roaming: the card would be reached directly again at the next start anyway. Not connected,
     *  it replaces a way chosen in this app's Settings once it worked; under Automatic it stays automatic. */
    fun useShizuku() {
        if (working) return
        allowCancelled = false
        if (_ui.value.access != AccessStatus.READY) {
            _ui.update { it.copy(stopped = false) }
            return startConnection(ready = "Card access now uses Shizuku") {
                backend.useShizuku()
                if (backend.preferred != null) backend.prefer(AccessMethod.SHIZUKU)
            }
        }
        _ui.update { it.copy(busy = Busy("Waiting for Shizuku…", cancellable = true), error = null, errorTitle = null,
            info = null) }
        viewModelScope.launch {
            val error = try { withContext(cardDispatcher) { backend.allowShizuku() }; null }
                catch (e: CancellationException) { throw e } catch (e: Exception) { e }
            _ui.update { it.copy(busy = null, info = when {
                error == null -> "This app can now set mobile data and roaming after a switch"
                allowCancelled -> null
                else -> error.message ?: "Shizuku didn't allow this app."
            }) }
            refreshShizuku()
        }
    }

    /** "Use root": asks the phone's root app again, e.g. after root was turned down once. Card access that works
     *  now (directly, say) carries on if root is refused. A way chosen in this app's Settings is replaced once root
     *  worked; under Automatic it stays automatic. */
    fun useRoot() {
        if (working) return
        if (_ui.value.access != AccessStatus.READY) {
            _ui.update { it.copy(stopped = false) }
            return startConnection(ready = "Card access now uses root") {
                backend.connectRoot()
                if (backend.preferred != null) backend.prefer(AccessMethod.ROOT)
            }
        }
        switchAccess(AccessMethod.ROOT, save = backend.preferred != null)
    }

    // ---------------- choosing card access (Settings) ----------------

    /** [way] picked in this app's Settings › Card access; null for Automatic. Connected, card access switches to it, and
     *  only once that worked is it kept: a failed switch changes nothing. Not connected there's nothing to lose, so it's
     *  kept at once, and connecting tries it first. [fromSim]: picked for the card in that SIM (from 0), which direct
     *  access can't open: it comes into view after the switch. Shizuku that isn't running, and "Nearby devices" on
     *  Android 17, are the screen's to deal with first. */
    fun chooseAccess(way: AccessMethod?, fromSim: Int = -1) {
        val ui = _ui.value
        // Settings closes for the Add eSIM form, and its rows are off while a pairing runs.
        if (ui.adding || ui.installed != null || PairingFlow.state.value.active) return
        if (working || CardWork.active.value) return _ui.update { it.copy(info = "Wait for the card to finish, then try again.") }
        val sdk = Build.VERSION.SDK_INT
        val unset = way == AccessMethod.ADB && !backend.wasPaired && !backend.hasHelper
        if (ui.access != AccessStatus.READY) {
            // Only news when it changed: the selected row again just tries again.
            val said = switched(way, null).takeIf { way != ui.preferred }
            backend.prefer(way)
            _ui.update { it.copy(preferred = way, preferredFailure = null, stopped = false) }
            // ADB's setup screens are what's next; Shizuku connects by itself once it's started.
            if (unset || (way == AccessMethod.SHIZUKU && ui.shizuku == ShizukuStatus.STOPPED)) return
            failedWith = null
            // On Android 10 and older the phone may ask to allow ADB, where it isn't behind another way.
            return startConnection(askingAllow = sdk < 30 && (way == null || way == AccessMethod.ADB), ready = said,
                via = way) { backend.connect(again = true) }
        }
        when {
            way == ui.method && way == ui.preferred -> {}
            way == null && ui.preferred == null -> {}
            // In use already: only the choice is new.
            way != null && way == ui.method -> {
                backend.prefer(way)
                _ui.update { it.copy(preferred = way, preferredFailure = null, info = switched(way, way)) }
            }
            // Nothing to connect to yet: set up first, while the way in use carries on.
            way == AccessMethod.ADB && (unset || ui.pairingExpired) -> {
                switchFor = fromSim
                _ui.update { it.copy(accessSetup = true) }
            }
            else -> switchAccess(way, save = true, fromSim)
        }
    }

    /** The pane for switching card access to ADB is done: paired (the pairing flow calls this), or on Android 10 and
     *  older set up with a computer, [portText] being the port typed after "adb tcpip", if not the usual one. */
    fun finishAccessSetup(portText: String = "") {
        val port = if (portText.isBlank()) null else parsePort(portText) ?: return
        switchAccess(AccessMethod.ADB, save = true, switchFor, port)
    }

    /** Closes that pane: card access stays as it is. */
    fun closeAccessSetup() {
        cancelPairing()
        _ui.update { it.copy(accessSetup = false) }
    }

    /** A setup screen's "instead" ("Set up Wireless debugging instead") for [way]: a way chosen in this app's Settings is
     *  replaced, as that's what it says. Under Automatic nothing is chosen, so that stays. */
    fun preferIfChosen(way: AccessMethod) {
        if (backend.preferred == null || backend.preferred == way) return
        backend.prefer(way)
        _ui.update { it.copy(preferred = way, preferredFailure = null) }
    }

    /** Card access through [way] from now on, while connected (see [CardAccess.switchTo]): the way in use carries on
     *  until that one works, and if it doesn't. With [save] it's also the choice in this app's Settings. [fromSim]: see
     *  [chooseAccess]. [port]: ADB on the port people typed (Android 10 and older). */
    private fun switchAccess(way: AccessMethod?, save: Boolean, fromSim: Int = -1, port: Int? = null) {
        if (working || _ui.value.access != AccessStatus.READY) return
        val sdk = Build.VERSION.SDK_INT
        val before = backend.method
        allowCancelled = false
        // It holds the card thread between slots, and stops at the next one; the switch finds the cards again anyway.
        looking?.cancel()
        // The questions can be stopped waiting for, and so can the search for Wireless debugging.
        _ui.update { it.copy(busy = Busy(switching(way, sdk), cancellable = way == AccessMethod.SHIZUKU ||
            way == AccessMethod.ADB), error = null, errorTitle = null, info = null) }
        viewModelScope.launch {
            val error = try {
                // Cancel lets go of the new way even once it connected: nothing changes, and nothing is saved.
                withContext(cardDispatcher) { backend.switchTo(way, port) { allowCancelled }; if (save) backend.prefer(way) }
                null
            } catch (e: CancellationException) { throw e } catch (e: Exception) { e }
            val adb = backend.method == AccessMethod.ADB
            _ui.update { it.copy(busy = null, method = backend.method, preferred = backend.preferred,
                preferredFailure = backend.preferredFailure, canSetUpSim = backend.canSetUpSim,
                background = backend.inBackground, paired = backend.wasPaired, helperUnavailable = backend.helperUnavailable,
                rootFound = backend.rootFound, rootDeclined = backend.rootDeclined, directRefused = backend.directRefused) }
            if (error == null) {
                if (PairingFlow.state.value.active) PairingFlow.stop(getApplication()) // the pairing was for this
                // Slots that hung for the way before are just numbers to this one, and the card may be elsewhere now.
                hungSlots = emptySet()
                val inView = _ui.value.eid
                landing = if (fromSim >= 0) Landing.Other(inView, fromSim) else inView?.let { Landing.Keep(it) }
                _ui.update { it.copy(stopped = false, accessSetup = false, accessSwitches = it.accessSwitches + 1,
                    pairingExpired = it.pairingExpired && !adb, portClosed = false, allowAgain = false,
                    nearbyNeeded = it.nearbyNeeded && !adb, simAllowNeeded = it.simAllowNeeded && !adb) }
                refreshShizuku()
                // Each way in numbers slots its own way: this finds the card again before anything else uses it.
                return@launch loadCard(switched(way, before))
            }
            val body = if (allowCancelled || (error is AllowNeededException && error.cancelled)) null
                else switchError(way, error, sdk, DebugSettings.read(getApplication()).mayConnect)
            // The pane says what these need, and it opens for them.
            val setUp = error is PairingRequiredException || error is DebuggingPortClosedException
            _ui.update { it.copy(pairingExpired = it.pairingExpired || error is PairingRequiredException,
                nearbyNeeded = it.nearbyNeeded || error is NearbyDevicesNeededException,
                portClosed = it.portClosed || error is DebuggingPortClosedException,
                accessSetup = it.accessSetup || setUp, errorTitle = body?.let { _ -> switchTitle(way, sdk) },
                error = body?.let { b -> "$b\n\nCard access stays as it was." }) }
            if (setUp) switchFor = fromSim
            refreshShizuku()
            lookForCards() // what the switch stopped
        }
    }

    /** What the busy dialog says while card access switches to [way]: what may take a moment there. */
    private fun switching(way: AccessMethod?, sdk: Int) = when (way) {
        null -> "Connecting to your card…"
        AccessMethod.DIRECT -> "Checking your card…"
        AccessMethod.ROOT -> "Asking for root…"
        AccessMethod.SHIZUKU -> "Waiting for Shizuku…"
        AccessMethod.ADB -> if (sdk >= 30) "Connecting to Wireless debugging…" else "Waiting for your phone to allow this app…"
    }

    /** What a switch to [way] says once it worked. [before]: the way in use until then, so Automatic can say whether
     *  that changed. */
    private fun switched(way: AccessMethod?, before: AccessMethod?): String {
        val sdk = Build.VERSION.SDK_INT
        return when (way) {
            null -> "Card access is automatic again" + (backend.method?.takeIf { before != null && it != before }
                ?.let { ". Now using ${wayName(it, sdk, backend.inBackground)}." } ?: "")
            AccessMethod.DIRECT -> "This app now reaches your card directly"
            AccessMethod.ROOT -> "Card access now uses root"
            AccessMethod.SHIZUKU -> "Card access now uses Shizuku"
            AccessMethod.ADB -> "Card access now uses ${if (sdk >= 30) "Wireless debugging" else "USB debugging"}"
        }
    }

    /** Said on the setup screens when the way chosen in this app's Settings failed, and they're about another one
     *  (see SetupScreen): what was chosen, and why it didn't work. Their own text is about the other. */
    private fun chosenNote(): String? {
        val chosen = backend.preferred?.takeIf { it != AccessMethod.ADB && it != backend.method } ?: return null
        val e = backend.preferredFailure?.takeIf { it !== CardAccess.NotTried } ?: return null
        val sdk = Build.VERSION.SDK_INT
        return "You chose ${wayLabel(chosen, sdk)} in this app's Settings, but " +
            "${fallbackWhy(e, chosen, sdk, backend.wasPaired, _ui.value.shizuku != ShizukuStatus.ABSENT)}."
    }

    // ---------------- pairing ----------------

    /** [notify]: the code is typed into a notification; otherwise into the app (split screen). */
    @RequiresApi(30)
    fun startPairing(notify: Boolean) {
        _ui.update { it.copy(error = null) }
        PairingFlow.start(getApplication(), notify)
    }

    fun cancelPairing() = PairingFlow.stop(getApplication())

    @RequiresApi(30)
    fun submitPairingCode(code: String, portText: String) {
        val port = if (portText.isBlank()) null else portText.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return PairingFlow.reject(getApplication(), "Enter the pairing port shown in the pop-up (a number up to 65535).")
        viewModelScope.launch(Dispatchers.IO) { PairingFlow.submit(getApplication(), code, port) }
    }

    private fun parsePort(text: String): Int? {
        val port = text.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            _ui.update { it.copy(error = "Enter a number between 1 and 65535.") }; return null
        }
        return port
    }

    /** [askingAllow]: the phone may ask to allow this app meanwhile, so the Connecting screen says what to do.
     *  [portMayOpen]: with [retry], a closed debugging port (Android 10 and older) is retried too, since USB
     *  debugging just came on and Android's debugging service starts listening a moment later. [ready] is said once
     *  the card has been read; with [via], only if it was reached that way, as a chosen way that fails falls back. */
    private fun startConnection(retry: Boolean = false, ready: String? = null, askingAllow: Boolean = false,
                                portMayOpen: Boolean = false, via: AccessMethod? = null, block: () -> Unit) {
        allowCancelled = false
        _ui.update { it.copy(access = AccessStatus.CONNECTING, askingAllow = askingAllow, error = null, errorTitle = null,
            info = null) }
        viewModelScope.launch {
            try {
                withContext(cardDispatcher) {
                    try { block() } catch (e: Exception) {
                        // A retry is no use with the app closed (not active): a reopened app is waiting for this
                        // thread, and a second root question would hold it.
                        if (!retry || !isActive || !worthRetrying(e, portMayOpen) ||
                            !DebugSettings.read(getApplication()).mayConnect) throw e
                        Thread.sleep(3000)
                        block()
                    }
                }
                failedWith = null
                if (PairingFlow.state.value.active) PairingFlow.stop(getApplication()) // connected another way
                _ui.update { it.copy(access = AccessStatus.READY, paired = backend.wasPaired, pairingExpired = false,
                    allowAgain = false, portClosed = false, nearbyNeeded = false, askingAllow = false, lookingForCards = true,
                    // Connected over ADB: the phone allows this app again.
                    simAllowNeeded = it.simAllowNeeded && backend.method != AccessMethod.ADB,
                    background = backend.inBackground, helperUnavailable = backend.helperUnavailable,
                    method = backend.method, canSetUpSim = backend.canSetUpSim, rootFound = backend.rootFound, rootDeclined = backend.rootDeclined,
                    preferred = backend.preferred, preferredFailure = backend.preferredFailure) }
                loadCard(ready?.takeIf { via == null || backend.method == via })
            } catch (e: CancellationException) { throw e }
            catch (e: PairingRequiredException) {
                _ui.update { it.copy(access = AccessStatus.SETUP, askingAllow = false, paired = false, pairingExpired = true,
                    eid = null, profiles = emptyList(), method = backend.method, rootFound = backend.rootFound, rootDeclined = backend.rootDeclined) }
            } catch (e: AllowNeededException) {
                // Android 10 and older: the phone has to allow this app (again). Connecting again lets it ask.
                failedWith = DebugSettings.read(getApplication())
                _ui.update { it.copy(access = AccessStatus.SETUP, askingAllow = false, paired = backend.wasPaired,
                    allowAgain = true, portClosed = false, eid = null, profiles = emptyList(), method = backend.method,
                    rootFound = backend.rootFound, rootDeclined = backend.rootDeclined, explainedError = e.message,
                    error = if (e.cancelled || allowCancelled) null else e.message) }
            } catch (e: DebuggingPortClosedException) {
                // Android 10 and older, usually after a restart: the screen says the computer is needed again.
                val s = DebugSettings.read(getApplication())
                failedWith = s
                _ui.update { it.copy(access = AccessStatus.SETUP, askingAllow = false, paired = backend.wasPaired,
                    settings = s, portClosed = true, allowAgain = false, helperUnavailable = backend.helperUnavailable,
                    eid = null, profiles = emptyList(), method = backend.method, rootFound = backend.rootFound,
                    rootDeclined = backend.rootDeclined, explainedError = e.message, error = e.message) }
            } catch (e: NearbyDevicesNeededException) {
                // Android 17: the Reconnect screen asks for "Nearby devices", or takes the port by hand. Switched off
                // meanwhile: that screen asks to turn Wireless debugging on instead, with neither.
                val s = DebugSettings.read(getApplication())
                failedWith = s
                _ui.update { it.copy(access = AccessStatus.SETUP, askingAllow = false, paired = backend.wasPaired,
                    settings = s, nearbyNeeded = true, eid = null, profiles = emptyList(), method = backend.method,
                    rootFound = backend.rootFound, rootDeclined = backend.rootDeclined,
                    error = if (s.mayConnect) e.message else null) }
            } catch (e: SetupNeededException) {
                // Nothing that needs no setup reached the card: setup (or reconnecting) takes it from here.
                val s = DebugSettings.read(getApplication())
                failedWith = s
                _ui.update { it.copy(access = AccessStatus.SETUP, paired = backend.wasPaired, settings = s,
                    method = backend.method, rootFound = backend.rootFound, rootDeclined = backend.rootDeclined, eid = null, profiles = emptyList(),
                    portClosed = false, allowAgain = false, noSim = noSimInPhone(getApplication()),
                    error = chosenNote() ?: e.message) }
            } catch (e: Exception) {
                // Switched off meanwhile (e.g. "Allow on this network?" declined): its own screen explains.
                val s = DebugSettings.read(getApplication())
                failedWith = s
                // Root turned down is news even with Wireless debugging off; so is a way in that worked before.
                val direct = backend.method != null && backend.method != AccessMethod.ADB
                // A try of root or Shizuku says nothing about the debugging port, so what the screen says of it stands.
                val otherWay = failedOtherWay(e)
                _ui.update { it.copy(access = AccessStatus.SETUP, paired = backend.wasPaired, settings = s,
                    helperUnavailable = backend.helperUnavailable, eid = null, profiles = emptyList(),
                    method = backend.method, rootFound = backend.rootFound, rootDeclined = backend.rootDeclined,
                    portClosed = it.portClosed && otherWay, allowAgain = it.allowAgain && otherWay,
                    error = when {
                        e is ConnectCancelledException || (e is ShizukuApduBackend.Failure && allowCancelled) -> null
                        s.mayConnect || direct || otherWay -> e.message ?: "Couldn't connect. Try again."
                        else -> null
                    }) }
            } finally { refreshShizuku() }
        }
    }

    /** "Stop card access": the background helper ends too, so nothing keeps running for this app. Not Shizuku: that
     *  isn't this app's to stop. */
    fun disconnect() {
        if (working) return
        _ui.update { it.copy(access = AccessStatus.SETUP, stopped = true, background = false, eid = null,
            profiles = emptyList(), details = null, error = null) }
        looking?.cancel() // it holds the card thread between slots, so stop() would wait for the rest
        viewModelScope.launch { withContext(cardDispatcher) { backend.stop() } }
    }

    // ---------------- card ----------------

    /** [done]: said once the card has been read, so "Card access is back" can't come before a failed read. */
    fun loadCard(done: String? = null) {
        if (working || _ui.value.access != AccessStatus.READY) return
        // Each way in numbers slots its own way, so a list on screen from another is no card this app can reach now.
        if (cardsVia != backend.method) {
            forgetCards()
            cardsVia = backend.method
            simOf = emptyMap()
            hungSlots = emptySet() // slots of another way in
            _ui.update { it.copy(eid = null, slot = -1, profiles = emptyList(), details = null, waiting = null, cardInfo = null) }
        }
        val stay = refreshStays(lpa.card?.eid, store.chosenCard(), _ui.value.manageable)
        _ui.update { it.copy(loading = true, error = null, errorTitle = null, builtInChip = null, idleSlot = -1, quietSlot = -1) }
        val card = if (stay) lpa else lpa.again()
        viewModelScope.launch {
            try {
                val (shown, profiles) = withContext(cardDispatcher) {
                    backend.reading {
                        if (stay) try {
                            noticesVia = backend.method
                            return@reading card to card.read().activeFirst()
                        } catch (e: ApduException) {
                            // Taken out, as Android says: whatever card is in the phone now is found as for anyone else.
                            // So for one there that refuses this app: it's not the one chosen, which let it in.
                            if (!Lpa.isLockedOnly(e.message) &&
                                runCatching { backend.present(card.slot) }.getOrNull() != false) throw e
                        }
                        val finder = if (stay) card.again(sameSlot = false) else card
                        val chosen = store.chosenCard()?.let { store.cardSlot(backend.method, it) } ?: -1
                        finder.slotHint = if (chosen >= 0) chosen else store.lastSlot(backend.method)
                        val eid = finder.discover()
                        val held = finder.onBuiltInSlot && eid !in store.ownCards()
                        noticesVia = backend.method.takeUnless { held }
                        if (!held && finder.slot != store.lastSlot(backend.method)) store.setLastSlot(backend.method, finder.slot)
                        finder to if (held) null else finder.getProfiles().activeFirst()
                    }
                }
                // Android's to manage, and a delete there can't be undone: nothing on it is read or changed from here.
                if (profiles == null) {
                    _ui.update { it.copy(loading = false, eid = null, slot = -1, profiles = emptyList(), details = null,
                        waiting = null, adding = false, keepDraft = it.keepDraft || it.adding, installed = null,
                        duplicate = null, builtInChip = shown.card?.eid, idleSlot = shown.idleSlot, quietSlot = shown.quietSlot) }
                    return@launch
                }
                show(shown, profiles, done)
                deliverNotices() // any left over from earlier, e.g. a delete made offline
            } catch (e: CancellationException) { throw e }
            catch (e: ApduException) {
                // The card someone chose was swapped for another: that one is to be offered, which a look does only for a
                // slot that isn't in view.
                if (stay && e.message == Lpa.OTHER_CARD) {
                    lpa = lpa.again(sameSlot = false)
                    _ui.update { it.copy(loading = false, eid = null, slot = -1, sim = -1, profiles = emptyList(), details = null,
                        waiting = null, cards = it.cards.filterNot { row -> row.slot == card.slot },
                        error = "Another card is in SIM ${it.simNumber} now. Choose a card at the top, or tap Try again.") }
                } else if (_ui.value.eid != null && Lpa.isLockedOnly(e.message)) {
                    // What's gone is the card in view. The one that refuses this app has its row at the top already, and
                    // the dialog has no "Change card access".
                    failed(e, "Couldn't refresh the card", Lpa.NOT_FOUND, changing = false)
                } else failed(e, if (_ui.value.eid == null) null else "Couldn't refresh the card", changing = false)
            }
            // With no card shown yet, the error is shown inline next to "Try again".
            catch (e: Exception) { failed(e, if (_ui.value.eid == null) null else "Couldn't refresh the card", changing = false) }
            // Whatever became of that card: another one may be the way out.
            finally { lookForCards() }
        }
    }

    /** [card], just read, is the one in view now. The card it replaces may just have stopped answering: it stays known. */
    private fun show(card: Lpa, profiles: List<EuiccProfile>, done: String?) {
        val eid = card.card?.eid ?: return
        store.addSeenOnCard(eid, profiles.map { it.iccid }) // so a deleted one is never taken for the card's own name
        val ui = _ui.value
        ui.eid?.takeIf { it != eid }?.let { kept = kept + (it to Kept(lpa, ui.profiles)) }
        // One slot holds one card: one there before was taken out for this one.
        kept = kept.filterValues { it.lpa.slot != card.slot } - eid
        lpa = card
        _ui.update { it.copy(loading = false, eid = eid, slot = card.slot, profiles = profiles, info = done ?: it.info,
            // The number goes with the slot: a card put in another one has another.
            sim = simOf[card.slot] ?: -1,
            cardInfo = it.cardInfo.takeIf { _ -> it.eid == eid }, knownCardName = store.cardName(eid)) }
    }

    /** Puts [eid] in view: at once if its list is known, while it's read again. On a card already in view, the form's
     *  choice just goes to it. */
    fun selectCard(eid: String) {
        landing = null // picked by hand, or the landing itself
        val ui = _ui.value
        if (eid == ui.eid) {
            store.chooseCard(eid)
            _ui.update { it.copy(addTarget = eid) }
            return
        }
        val next = kept[eid]
        if (working || ui.access != AccessStatus.READY || next == null) return
        lookEpoch++ // what's in view is what a look leaves alone
        ui.eid?.let { kept = kept + (it to Kept(lpa, ui.profiles)) }
        lpa = next.lpa
        store.chooseCard(eid)
        _ui.update { it.copy(eid = eid.takeIf { _ -> next.profiles != null }, slot = next.lpa.slot, profiles = next.profiles.orEmpty(),
            sim = simOf[next.lpa.slot] ?: -1, cards = it.rows, cardInfo = null, knownCardName = store.cardName(eid), waiting = null,
            details = null, addTarget = if (it.adding) eid else it.addTarget) }
        loadCard()
    }

    /** Card access went, or changed: every address is void. [hungSlots] aren't, as Reconnect is what clears those. */
    private fun forgetCards() {
        lookEpoch++
        kept = emptyMap()
        seen = emptyMap()
        lpa = lpa.again(sameSlot = false)
        _ui.update { it.copy(cards = emptyList(), sim = -1, slot = if (it.eid == null) -1 else it.slot, lookingForCards = true) }
    }

    /** Looks for other cards, after the first list is on screen, so one card costs what it did. Only ever changes what
     *  the switcher offers: the view stays, as someone may be in a dialog or a form on it. One at a time, and a look whose
     *  answer no longer applies goes again. [quick]: only what needs no card traffic. */
    private fun lookForCards(quick: Boolean = false) {
        if (looking?.isActive == true || _ui.value.access != AccessStatus.READY) return
        looking = viewModelScope.launch {
            try {
                do {
                    val epoch = lookEpoch
                    val inView = _ui.value.slot
                    val known = seen
                    val hung = hungSlots
                    val own = store.ownCards()
                    val how = backend.method
                    val looks = withContext(cardDispatcher) {
                        backend.reading { lpa.scan(inView, known, own, { s, t -> store.noManager(how, s, t) }, hung, quick) { isActive } }
                    }
                    val current = epoch == lookEpoch
                    // The card in view turned up in another slot: the slot it left wasn't looked behind, and may hold
                    // a card of its own now.
                    val moved = current && lookedThrough(looks, how)
                } while ((!current || moved) && _ui.value.access == AccessStatus.READY)
                land()
            } catch (e: CancellationException) { throw e }
            // Not something people asked for: what's on screen stands.
            catch (e: Exception) { Log.w("LpaViewModel", "Looking for cards failed", e) }
            // Not after a cancel: that look didn't get through the slots, and the one after it is what Install waits for.
            _ui.update { it.copy(lookingForCards = false) }
        }
    }

    /** After a switch of card access, once a look has found the cards that way: the [landing] card comes into view. */
    private fun land() {
        val l = landing ?: return
        if (cardsVia != backend.method) return
        landing = null
        val ui = _ui.value
        val cards = ui.rows.filter { it.eid != null }
        val target = when (l) {
            is Landing.Keep -> cards.firstOrNull { it.eid == l.eid }
            is Landing.Other -> cards.firstOrNull { it.eid != l.notEid && it.sim == l.sim }
                ?: cards.firstOrNull { it.eid != l.notEid }
        }
        // Android 9 and older reach a card outside SIM 1 this way only while one of its eSIMs is on (see SlotReach).
        if (target == null && l is Landing.Other && Build.VERSION.SDK_INT < 29 && backend.method != AccessMethod.DIRECT)
            _ui.update { it.copy(info = "Couldn't reach the card in SIM ${l.sim + 1}. On this version of Android, the app " +
                "reaches a card outside SIM 1 only while one of its eSIMs is on. Move it to SIM 1 to manage it.") }
        target?.eid?.takeIf { it != ui.eid }?.let(::selectCard)
    }

    /** True when the card in view was found in another slot, and the view went with it. */
    private fun lookedThrough(looks: List<SlotLook>, how: AccessMethod?): Boolean {
        Log.i("LpaViewModel", "Looked for cards: " + looks.joinToString { "${it.slot}=${it.what}" })
        for (l in looks) {
            when (l.what) {
                SlotLook.What.CARD -> l.card?.let {
                    kept = kept + (it.eid to Kept(lpa.at(it), l.profiles?.activeFirst()))
                    l.profiles?.let { list -> store.addSeenOnCard(it.eid, list.map { p -> p.iccid }) }
                }
                SlotLook.What.EMPTY, SlotLook.What.IDLE, SlotLook.What.LOCKED -> kept = kept.filterValues { it.lpa.slot != l.slot }
                SlotLook.What.HUNG -> hungSlots = hungSlots + l.slot
                else -> { }
            }
            if (l.noManager && l.token != null) store.setNoManager(how, l.slot, l.token)
        }
        seen = seenAfter(seen, looks)
        simOf = simOf + looks.mapNotNull { l -> l.logical?.let { l.slot to it } }
        val inView = _ui.value.eid
        // Taken out and put in another slot: the next read has to go there. Not under an operation, which has the
        // card: the next look finds it again.
        val moved = looks.firstNotNullOfOrNull { l -> l.card?.takeIf { it.eid == inView && it.slot != _ui.value.slot } }
        if (moved != null) {
            if (!working) { lpa = lpa.at(moved); _ui.update { it.copy(slot = moved.slot, sim = simOf[moved.slot] ?: -1) } }
            else seen = seen - moved.slot
        }
        inView?.let { kept = kept - it }
        _ui.update {
            val sim = looks.firstOrNull { l -> l.slot == it.slot }?.logical ?: it.sim
            val rows = withView(rowsAfter(it.cards, looks), it.slot, it.eid, it.profiles, sim)
            it.copy(cards = rows, sim = sim)
        }
        if (_ui.value.manageable >= 2) _ui.value.cards.forEach { row -> row.eid?.let { store.setCardSlot(how, it, row.slot) } }
        return moved != null && !working
    }

    /** "It's my removable card": a phone can report one as built in. Remembered, so this is asked once per card. */
    fun ownCard() {
        _ui.value.builtInChip?.let(store::addOwnCard)
        loadCard()
    }

    /** [note] is added to the "Now using" message. With [setup], the phone's SIM settings follow the switch,
     *  and the choice is remembered for next time. */
    fun enable(p: EuiccProfile, note: String? = null, setup: ProfileStore.SwitchSetup? = null) {
        setup?.let { chosen ->
            // With several cards the tick follows which card has mobile data now: a remembered one would be another card's.
            val s = if (_ui.value.manageable >= 2) chosen.copy(mobileData = store.switchSetup().mobileData) else chosen
            store.setSwitchSetup(s)
            _ui.update { it.copy(switchSetup = s) }
        }
        // No way to change SIM settings (direct card access, no pairing): the dialog had no checkboxes, so the
        // message offers the phone's own SIM settings instead.
        val manual = !_ui.value.canSetUpSim
        val defaults = defaultsNote(dataFollows = setup?.mobileData == true && !manual)
        _ui.update { it.copy(subBeforeSwitch = simSubscription(getApplication(), it.androidSlot)) }
        op("Switching to ${p.displayName}…", "Now using ${p.displayName}" + (note?.let { ". $it" } ?: ""),
            "Couldn't switch eSIM", settle = true,
            run = { lpa.enable(p) }, confirmed = { l -> l.any { it.isdpAid == p.isdpAid && it.enabled } },
            onDone = { deliverNotices(afterSwitch = true) },
            afterwards = {
                val advice = if (manual) "If it doesn't connect, turn on mobile data and data roaming for " +
                    "${p.displayName} in your phone's SIM settings." else setup?.let { setUpSim(p, it) }
                // A pairing Android dropped meanwhile: the next switch dialog leaves the checkboxes out. The sheet closes
                // too: the result and its SIM settings link show in a snackbar, which a sheet would cover.
                _ui.update { it.copy(paired = backend.wasPaired, canSetUpSim = backend.canSetUpSim, details = null) }
                listOfNotNull(advice, defaults).joinToString(" ").ifEmpty { null }
            })
    }

    /** With a second card's SIM on, Android moves or clears what the eSIM going off has: it can leave calls asking every
     *  time, or mobile data off. Which it does varies by version and phone, so this only points to where to look. Read
     *  before the switch, as Android changes them along the way. For [op]'s message, which adds a link to SIM settings. */
    private fun defaultsNote(dataFollows: Boolean = false): String? {
        val ui = _ui.value
        if (ui.manageable < 2 || ui.rows.none { it.slot != ui.slot && it.on != null }) return null
        // This app moves mobile data to the new eSIM itself when asked to.
        val held = defaultsOn(simSubscription(getApplication(), ui.androidSlot)) - (if (dataFollows) setOf(MOBILE_DATA) else emptySet())
        if (held.isEmpty()) return null
        return "Check which SIM your phone uses for ${listed(held)} now: Android may have changed that with the switch."
    }

    /** Android brings a newly switched-on eSIM up a few seconds after the card restarts: wait for that, then
     *  make it the mobile data SIM and turn on its data roaming, as chosen. Null when done; otherwise what to
     *  tell the user. A problem here never fails the switch itself. */
    private fun setUpSim(p: EuiccProfile, setup: ProfileStore.SwitchSetup): String? {
        if (!setup.mobileData && !setup.roaming) return null
        _ui.update { it.copy(busy = it.busy?.copy(label = if (setup.mobileData) "Setting up mobile data…" else "Turning on data roaming…")) }
        val todo = when {
            setup.mobileData && setup.roaming -> "use ${p.displayName} for mobile data and turn on its data roaming"
            setup.mobileData -> "use ${p.displayName} for mobile data"
            else -> "turn on data roaming for ${p.displayName}"
        }
        retryFor(30_000, 1000) {
            try {
                if (!backend.isReady()) backend.reconnect()
                val result = backend.useSim(lpa.slot, p.iccid, setup.mobileData, setup.roaming)
                if (result.applied) {
                    _ui.update { it.copy(simAllowNeeded = false) }
                    // Single-SIM Android 8.1 and 9 only keep roaming turned on with their own switch (see CardShell).
                    return if (!result.roamingMayReset) null else "Android 8.1 and 9 can switch data roaming back " +
                        "off by themselves, for example after a restart. To keep it on, turn data roaming for " +
                        "${p.displayName} off and back on in your phone's SIM settings."
                }
            } catch (e: Exception) {
                // Direct card access, which needs Shizuku or ADB for this: it's off, or no longer paired.
                if (e is CardAccess.NoSimSettingsException) return when {
                    e.shizukuCantStart -> "Shizuku couldn't start this app's helper, so $todo in your phone's SIM settings."
                    e.shizukuFailed -> "Shizuku isn't running or no longer allows this app, so $todo in your phone's " +
                        "SIM settings."
                    // Android 10 and older: set up with a computer, until the phone restarts.
                    Build.VERSION.SDK_INT < 30 -> when {
                        e.needsAllow -> {
                            // Settings › Mobile data and roaming offers to allow it again from now on.
                            _ui.update { it.copy(simAllowNeeded = true) }
                            "Your phone no longer allows this app's debugging access, so $todo in your phone's SIM " +
                                "settings. To allow it again, open \"Mobile data and roaming\" in this app's Settings."
                        }
                        e.needsPairing -> "Set up mobile data and roaming in this app's Settings first, so $todo in " +
                            "your phone's SIM settings this time."
                        DebugSettings.read(getApplication()).mayConnect -> "Couldn't reach Android's debugging tool " +
                            "(after a restart it needs your computer again), so $todo in your phone's SIM settings."
                        else -> "USB debugging is off, so $todo in your phone's SIM settings."
                    }
                    e.needsPairing -> "Wireless debugging needs pairing again (in this app's Settings, under \"Mobile " +
                        "data and roaming\"), so $todo in your phone's SIM settings."
                    // Android 17: Wireless debugging has a new port, and finding it needs a permission.
                    e.cause is NearbyDevicesNeededException -> "This app needs \"Nearby devices\" to find Wireless " +
                        "debugging (allow it under Permissions on this app's page in your phone's Settings), so $todo " +
                        "in your phone's SIM settings."
                    DebugSettings.read(getApplication()).mayConnect ->
                        "Couldn't reach Wireless debugging, so $todo in your phone's SIM settings."
                    else -> "Wireless debugging is off, so $todo in your phone's SIM settings."
                }
                // Still connected: the phone refused the change, and asking again won't help.
                if (backend.isReady() || e is PairingRequiredException || e is AllowNeededException) {
                    Log.w("LpaViewModel", "SIM settings not changed", e)
                    return when (e) {
                        is PairingRequiredException -> "Card access needs pairing again, so $todo in your phone's SIM settings."
                        is AllowNeededException -> "Your phone has to allow card access again, so $todo in your " +
                            "phone's SIM settings."
                        else -> "Your phone didn't let this app change its SIM settings. To finish, $todo in your phone's SIM settings."
                    }
                }
            }
        }
        return "Your phone hasn't picked up the eSIM yet. If it doesn't connect, $todo in your phone's SIM settings."
    }

    fun disable(p: EuiccProfile) {
        val defaults = defaultsNote()
        var answered = false // the card said it's done: what fails after that is the card coming back
        op("Turning off ${p.displayName}…", "${p.displayName} is off", "Couldn't turn off eSIM", settle = true,
            run = { answered = lpa.disable(p) }, confirmed = { l -> l.none { it.isdpAid == p.isdpAid && it.enabled } },
            afterwards = { defaults }, onDone = { deliverNotices(afterSwitch = true) }, work = "Turning off eSIM…",
            // Some phones stop seeing a card with no eSIM on, which looks like a failed turn-off but isn't one.
            failure = { if (answered && _ui.value.profiles.none { it.enabled && it.isdpAid != p.isdpAid })
                "Card not answering" to "${p.displayName} is off, but the card isn't answering now. Some phones stop " +
                    "seeing a card with no eSIM on. Restart your phone. If the card still doesn't show up, put it in " +
                    "another phone and turn an eSIM on there." else null })
    }

    /** The provider is told afterwards, in the background. A copy of the eSIM on another card keeps its note: a note goes
     *  with the eSIM, and the ICCID is all that says which that is. */
    fun delete(p: EuiccProfile) {
        val copy = kept.values.any { c -> c.profiles?.any { it.iccid == p.iccid } == true }
        op("Deleting ${p.displayName}…", "${p.displayName} deleted", "Couldn't delete eSIM", settle = false,
            run = { lpa.delete(p); if (!copy) { store.setNote(p.iccid, ""); store.setHidden(p.iccid, false) } },
            confirmed = { l -> l.none { it.isdpAid == p.isdpAid } }, onDone = ::deliverNotices)
    }

    /** Saved on the card itself; a blank [name] brings back the provider's name. */
    fun rename(p: EuiccProfile, name: String) {
        var answered = true // the card said it's done; without that, the list it gives next has to show the name
        op("Saving name…", if (name.isBlank()) "Name reset to ${p.copy(nickname = null).displayName}" else "Renamed to ${name.trim()}",
            "Couldn't rename eSIM", settle = false, run = { answered = lpa.setNickname(p.iccid, name) },
            confirmed = { l -> answered || l.any { it.iccid == p.iccid && it.nickname.orEmpty() == name.trim() } })
    }

    fun setNote(p: EuiccProfile, text: String) {
        store.setNote(p.iccid, text)
        _ui.update { it.copy(notes = store.notes()) }
    }

    /** Kept on this phone only, like notes: the card isn't touched, so it works even while the card is busy. */
    fun setHidden(p: EuiccProfile, hide: Boolean) {
        store.setHidden(p.iccid, hide)
        _ui.update { it.copy(hidden = store.hidden()) }
    }

    /** [afterwards] runs once the card confirms the change; a sentence it returns is added to [done]. */
    private fun op(label: String, done: String, failTitle: String, settle: Boolean,
                   run: () -> Unit, confirmed: (List<EuiccProfile>) -> Boolean = { true },
                   afterwards: () -> String? = { null }, onDone: () -> Unit = {}, work: String = "Switching eSIM…",
                   failure: () -> Pair<String, String>? = { null }) {
        if (working || _ui.value.access != AccessStatus.READY) return
        _ui.update { it.copy(busy = Busy(label), error = null, errorTitle = null, info = null) }
        viewModelScope.launch {
            // A switch: the wait for the card to restart, and SIM settings after it, shouldn't die with the app.
            if (settle) CardWork.begin(getApplication(), work)
            try {
                val (profiles, extra) = withContext(cardDispatcher) {
                    run()
                    if (settle) Thread.sleep(2000) // switching eSIMs restarts the card (REFRESH)
                    val list = reloadUntil(confirmed, restarting = settle)
                    _ui.update { it.copy(profiles = list) } // the switch shows while SIM settings follow
                    list to afterwards()
                }
                _ui.update { it.copy(busy = null, profiles = profiles, notes = store.notes(), hidden = store.hidden(),
                    info = listOfNotNull(done.removeSuffix("."), extra).joinToString(". "), infoSimSettings = extra != null,
                    details = it.details?.takeIf { id -> profiles.any { p -> p.iccid == id } }) }
                onDone()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val (title, message) = failure() ?: (failTitle to (e.message ?: "Card operation failed"))
                failed(e, title, message)
            }
            finally { if (settle) CardWork.end() } // cancelled too, only ever after the blocking block above has ended
        }
    }

    private var notices: Job? = null

    /** On the card thread, set by [loadCard]: the way in that found the card on screen, whose notices these are. Slot
     *  numbers mean something only to that way in. Null while it's the chip in [LpaUi.builtInChip]: those are Android's. */
    @Volatile private var noticesVia: AccessMethod? = null
    private val noticesAllowed get() = noticesVia != null && noticesVia == backend.method

    /** Tell providers about installs, switches and deletes the card has noted, in the background and one run
     *  after another. The card is only held to read and clear notices, not while sending them; any that can't be
     *  sent now stay on the card for the next run. [afterSwitch]: the phone's connection normally drops during a
     *  switch, so what didn't get through is tried a few more times. */
    private fun deliverNotices(afterSwitch: Boolean = false) {
        val previous = notices
        // Retries stay with the card that switched, whatever comes into view meanwhile, and with the way in whose slot
        // numbers its address is in.
        val card = lpa
        val via = backend.method
        notices = viewModelScope.launch {
            previous?.join()
            whileLeft(if (afterSwitch) NOTICE_RETRIES else emptyList()) { sendNotices(card, via) }
        }
    }

    /** One run: true when something is left to send. Nothing here is news to people, so a failure stays quiet. */
    private suspend fun sendNotices(card: Lpa, via: AccessMethod?) = try {
        val pending = withContext(cardDispatcher) {
            if (noticesAllowed && backend.method == via) backend.reading { card.takeNotifications() } else emptyList()
        }
        val delivered = deliverEach(pending) { n -> withContext(Dispatchers.IO) { card.deliver(n) } }
        if (delivered.isNotEmpty()) withContext(cardDispatcher) {
            if (noticesAllowed && backend.method == via) card.removeNotifications(delivered.map { it.seq })
        }
        delivered.size < pending.size
    } catch (e: CancellationException) { throw e } catch (_: Exception) { true }

    fun dismissSameName(key: String) {
        store.dismissSameName(key)
        _ui.update { it.copy(dismissedSameName = store.dismissedSameName()) }
    }

    fun dismissUsbTip() {
        store.dismissUsbTip()
        _ui.update { it.copy(usbTipDismissed = true) }
    }

    /** Read the card's free space and versions. Queues behind any card operation instead of blocking one. */
    fun loadCardInfo() {
        if (_ui.value.access != AccessStatus.READY) return
        _ui.update { it.copy(cardInfoLoading = true) }
        val card = lpa // outside the gate on changing card: the sheet can be closed and another card picked meanwhile
        val iccids = _ui.value.profiles.map { it.iccid }
        viewModelScope.launch {
            val r = try {
                withContext(cardDispatcher) {
                    backend.reading {
                        // Asked apart: a card that won't say its addresses still shows its other details.
                        val (defaultDp, rootDs) = runCatching { card.configuredAddresses() }.getOrDefault(null to null)
                        card.cardInfo().copy(rootDs = rootDs, defaultDp = defaultDp, name = cardName(card, iccids))
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            // By EID, not object: a refresh meanwhile gives the same card a new one.
            val same = card.card?.eid?.let { it == lpa.card?.eid } == true
            _ui.update { it.copy(cardInfoLoading = false, cardInfo = r.takeIf { _ -> same } ?: it.cardInfo,
                knownCardName = if (r != null && same) r.name else it.knownCardName) }
        }
    }

    /** The card's own name ([CardFacts.name]), remembered per card for when the helper can't read Android's records.
     *  A new name replaces a remembered one only if at least as sure; a remembered one is dropped once its profile
     *  turns out to be one of the card's eSIMs ([iccids], or seen before). */
    private fun cardName(card: Lpa, iccids: List<String>): CardFacts.Name? {
        val found = card.card ?: return null
        val records = runCatching { backend.cardRecords(found.eid) }.getOrDefault(emptyList())
        var esims = iccids + store.seenOnCard(found.eid)
        var named = CardFacts.name(records, esims, found.isdR)
        // Only a profile in use can be an eSIM turned on in another app since the list on screen was read: then the
        // card's list is read again, so that eSIM isn't taken for the card's own profile.
        // Where that read fails, a profile in use proves nothing, so none counts.
        if (named?.from == CardFacts.Name.From.IN_USE) {
            val now = runCatching { card.getProfiles().map { it.iccid } }.getOrNull()
            if (now != null) store.addSeenOnCard(found.eid, now)
            esims = (now ?: iccids) + store.seenOnCard(found.eid)
            named = CardFacts.name(if (now != null) records else records.filter { !it.inUse }, esims, found.isdR)
        }
        val stored = store.cardName(found.eid)
        val kept = stored?.takeUnless { k -> k.iccid?.let { CardFacts.isEsim(it, esims) } == true }
        val best = when {
            named == null -> kept
            kept == null || named.from.ordinal <= kept.from.ordinal -> named
            else -> kept
        }
        if (best != stored) store.setCardName(found.eid, best)
        return best
    }

    fun openDetails(p: EuiccProfile) = _ui.update { it.copy(details = p.iccid) }
    fun closeDetails() = _ui.update { it.copy(details = null) }

    // ---------------- install ----------------

    fun openAdd() = _ui.update { it.addOpened() }
    /** For a code from another app, which has no card of its own: with several, people pick. */
    fun openAddFromOutside() = _ui.update { it.addOpened(target = null) }
    fun closeAdd() = _ui.update { it.copy(adding = false, duplicate = null, waiting = null) }

    /** Shared text or an opened link: the activation code in it waits for the Add eSIM form, which opens as
     *  soon as card access is ready. Nothing installs without a tap on Install. */
    fun receive(text: String) {
        val ac = ActivationCode.find(text)
        ac?.let { saved[INCOMING] = it.toString() } // kept if Android stops the app while people finish setup
        _ui.update { it.copy(incoming = ac?.toString() ?: it.incoming, info = when {
            ac == null -> "That doesn't contain an eSIM activation code."
            it.access != AccessStatus.READY -> "Got the eSIM code. It opens in Add eSIM once card access is ready."
            else -> it.info
        }) }
    }

    /** A shared image, e.g. a screenshot of the provider's QR code; [text] came with it and may hold the code. */
    fun receiveImage(uri: Uri, text: String? = null) {
        viewModelScope.launch {
            val qr = withContext(Dispatchers.Default) { runCatching { QrImage.decode(getApplication(), uri) }.getOrNull() }
            when {
                qr != null && ActivationCode.find(qr) != null -> receive(qr)
                text != null && ActivationCode.find(text) != null -> receive(text)
                // The same words as "Use a screenshot" in the app.
                qr != null -> _ui.update { it.copy(info = "That QR code isn't an eSIM activation code.") }
                else -> _ui.update { it.copy(info = "No QR code found in that image. Try a sharper screenshot, or enter the code.") }
            }
        }
    }

    fun clearIncoming() {
        saved.remove<String>(INCOMING)
        _ui.update { it.copy(incoming = null) }
    }

    /** eSIMs providers have sent straight to this card's EID, without a QR code: asks the discovery servers
     *  (SM-DS), the card's own first, and lists the card's default provider, if it has one, to try as well. */
    fun checkWaiting() {
        if (working || _ui.value.access != AccessStatus.READY || _ui.value.eid == null) return
        val before = _ui.value.waiting
        val cancel = Es9Client.Cancel().also { checkCancel = it }
        _ui.update { it.copy(busy = Busy("Checking for eSIMs sent to this card…", cancellable = true), waiting = null,
            error = null, errorTitle = null, info = null) }
        viewModelScope.launch {
            try {
                val (default, searches) = withContext(cardDispatcher) {
                    // A card that won't say its addresses still gets the well-known discovery servers asked.
                    val (defaultDp, rootDs) = runCatching { lpa.configuredAddresses() }.getOrDefault(null to null)
                    defaultDp?.let { Lpa.WaitingEsim(it, "", fromDefault = true) } to
                        Lpa.discoveryServers(rootDs).map { server ->
                            server to runCatching { cancel.check(); lpa.waitingEsims(server, cancel) }.onFailure { e ->
                                if (!cancel.cancelled)
                                    Log.w("LpaViewModel", "Discovery at $server failed: ${e.javaClass.simpleName}: ${e.message}")
                            }
                        }
                }
                // Nothing on the card changed, so cancelling just goes back to how it was.
                if (cancel.cancelled) { _ui.update { it.copy(busy = null, waiting = before) }; return@launch }
                // One server out of reach doesn't hide what the others have; with none reached, a default
                // provider still leaves something to try.
                val failures = searches.mapNotNull { it.second.exceptionOrNull() }
                // Card access dropped during the searches: that's the problem to report, not the servers.
                if (failures.isNotEmpty() && !backend.isReady()) throw failures.first()
                val reached = searches.filter { it.second.isSuccess }
                if (reached.isEmpty() && default == null) throw failures.first()
                val found = reached.flatMap { it.second.getOrThrow() }.distinctBy { it.smdp.lowercase() to it.eventId }
                val missed = searches.filter { it.second.isFailure }.joinToString(" and ") { it.first }
                val listed = if (default != null) " Your card's default provider is listed in case it has one." else ""
                _ui.update { it.copy(busy = null, waiting = listOfNotNull(default) + found, info = when {
                    reached.isEmpty() -> (if (failures.all { e -> e is Es9Client.Es9Exception })
                        "The eSIM discovery servers refused the check." else "Couldn't reach the eSIM discovery servers.") + listed
                    found.isNotEmpty() -> null
                    // Named, since a card can name a server that no longer exists: then "later" won't help.
                    missed.isNotEmpty() -> "No eSIMs found for this card. $missed couldn't be checked.$listed"
                    else -> "No eSIMs are waiting for this card.$listed"
                }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = if (e is Es9Client.Es9Exception) "The eSIM discovery servers refused the check. Try again later."
                    else networkMessage(e, "the eSIM discovery servers")
                failed(e, "Couldn't check for eSIMs", message, changing = false)
            } finally { checkCancel = null }
        }
    }

    /** Set while [checkWaiting] runs. */
    private var checkCancel: Es9Client.Cancel? = null

    /** Cancel on the busy dialog: the check for eSIMs ([cancelCheck]), or the wait for the phone to allow this
     *  app ([setUpSimSettings]). */
    fun cancelBusy() {
        if (checkCancel != null) return cancelCheck()
        _ui.update { it.copy(busy = it.busy?.copy(label = "Cancelling…", cancelling = true)) }
        cancelConnect()
    }

    /** Stops the check at its next server request, dropping one that's waiting for an answer. A card command
     *  in progress always finishes first. */
    fun cancelCheck() {
        val cancel = checkCancel ?: return
        checkCancel = null
        _ui.update { it.copy(busy = it.busy?.copy(label = "Cancelling…", cancelling = true)) }
        viewModelScope.launch(Dispatchers.IO) { cancel.cancel() }
    }

    /** [force]: the user saw the duplicate warning and wants to try anyway. */
    fun download(ac: ActivationCode, confirmationCode: String?, force: Boolean = false) {
        // The card has been read, so the eSIMs already on it are known (the new one is told apart from them).
        if (working || _ui.value.access != AccessStatus.READY || !_ui.value.mayInstall) return
        val prior = if (force) null else store.installOf(ac)
        if (prior != null) {
            val onCard = prior.iccid?.let { id -> _ui.value.profiles.firstOrNull { it.iccid == id } }
            _ui.update { it.copy(duplicate = DuplicateCode(ac, confirmationCode, prior, onCard)) }
            return
        }
        val before = _ui.value.profiles
        val known = before.mapTo(HashSet()) { it.isdpAid }
        _ui.update { it.copy(duplicate = null, ccNeeded = null, busy = Busy(Lpa.Step.READ_CARD.label, 0f, Lpa.Step.READ_CARD),
            error = null, errorTitle = null, info = null, keepDraft = false) }
        viewModelScope.launch {
            CardWork.begin(getApplication(), "Installing eSIM…")
            try {
                val (outcome, profiles, added) = withContext(cardDispatcher) {
                    val outcome = lpa.downloadProfile(ac, confirmationCode, before.mapTo(HashSet()) { it.iccid }) { step, f ->
                        _ui.update { it.copy(busy = Busy(step.label, overall(step, f), step)) }
                    }
                    // The eSIM is installed at this point; a failed re-read must not report the download as failed.
                    val profiles = runCatching { reloadUntil() }.getOrNull()
                    val added = profiles?.firstOrNull { it.isdpAid !in known }
                    // In here: this block still ends if people swiped the app away, and what follows it doesn't run then.
                    store.recordInstall(ac, added?.iccid ?: outcome.iccid, added?.displayName ?: outcome.profileName)
                    // One of the card's eSIMs, even if the list couldn't be read: never the card's own name.
                    lpa.card?.eid?.let { eid -> (added?.iccid ?: outcome.iccid)?.let { store.addSeenOnCard(eid, listOf(it)) } }
                    // An eSIM hidden once and removed elsewhere (another phone or app) can come back: it shows like any new one.
                    (added?.iccid ?: outcome.iccid)?.let { store.setHidden(it, false) }
                    Triple(outcome, profiles, added)
                }
                // The card's answer didn't say how it went, and no new eSIM shows: not something to call installed.
                // The code stays recorded all the same, as the provider has handed the eSIM out by now.
                if (!outcome.confirmed && added == null) _ui.update { it.copy(busy = null, keepDraft = true,
                    profiles = profiles ?: it.profiles, hidden = store.hidden(),
                    errorTitle = "Install not confirmed", error = Lpa.INSTALL_UNKNOWN) }
                else _ui.update { it.copy(busy = null, adding = false, waiting = null, profiles = profiles ?: it.profiles,
                    installed = added, hidden = store.hidden(),
                    info = when {
                        added != null -> null
                        profiles == null -> "Installed ${outcome.profileName}. Refresh to see it."
                        else -> "Installed ${outcome.profileName}"
                    }) }
            } catch (e: CancellationException) { throw e }
            catch (e: AlreadyOnCardException) {
                // Remember the code, so the next attempt is caught before contacting the provider.
                val onCard = before.firstOrNull { it.iccid == e.iccid }
                store.recordInstall(ac, e.iccid, onCard?.displayName ?: "eSIM")
                // Not kept for codes without a matching ID (a card's default provider), so say it here instead.
                val prior = store.installOf(ac)
                _ui.update { it.copy(busy = null, duplicate = prior?.let { p -> DuplicateCode(ac, confirmationCode, p, onCard) },
                    info = if (prior != null) null else onCard?.let { p -> "This eSIM is already on your card as \"${p.displayName}\"." }
                        ?: "This eSIM is already on your card.") }
            }
            catch (e: ConfirmationCodeNeededException) {
                // Nothing broke, and nothing is used up: the same code works once the confirmation code goes with it.
                _ui.update { it.ccAsked(ac) }
                failed(e, "Confirmation code needed", "${e.message} Type it into the \"Confirmation code\" box, then " +
                    "tap \"Install\" again.")
            }
            catch (e: Exception) {
                Log.w("LpaViewModel", "Install failed", e) // the words on screen don't name the cause
                _ui.update { it.copy(keepDraft = true) }
                // Nothing was typed for a card's default provider: its refusal means it has nothing for this card.
                val nothingWaiting = ac.matchingId.isEmpty() && e is Es9Client.Es9Exception &&
                    e.subject in setOf("8.1.1", "8.2", "8.2.6")
                failed(e, "Couldn't install eSIM", if (nothingWaiting) "This provider has no eSIM waiting for this card."
                    else networkMessage(e, reach = "Check your internet connection. If that's working, the address may be " +
                        "wrong or the provider's server may be down.",
                        otherwise = "Something went wrong during the install. Check your eSIM list, then try again."))
            } finally { CardWork.end() } // cancelled too, only ever after the blocking block above has ended
        }
    }

    fun dismissDuplicate() = _ui.update { it.copy(duplicate = null) }

    /** An earlier install of [ac] by this app, with the eSIM it became if that is still on the card in view, or the row of
     *  another card that has it. That's only worth a word: it's how an eSIM moves from one card to the other. */
    fun priorInstall(ac: ActivationCode): PriorInstall? = store.installOf(ac)?.let { prior ->
        val id = prior.iccid
        val other = kept.entries.firstOrNull { (_, c) -> c.profiles?.any { it.iccid == id } == true }?.key
        PriorInstall(prior, id?.let { _ui.value.profiles.firstOrNull { p -> p.iccid == it } },
            other?.takeIf { id != null }?.let { eid -> _ui.value.cards.firstOrNull { it.eid == eid } })
    }

    /** "Show it" on the duplicate warning: close the install dialog and open the existing eSIM. */
    fun showDuplicate(p: EuiccProfile) = _ui.update { it.copy(adding = false, keepDraft = false, duplicate = null,
        waiting = null, details = p.iccid) }

    /** After an install: optionally name it, then optionally switch to it ([setup] as in [enable]). */
    fun finishInstall(name: String, switch: Boolean, setup: ProfileStore.SwitchSetup? = null) {
        val p = _ui.value.installed ?: return
        _ui.update { it.copy(installed = null) }
        if (name.isBlank()) { if (switch) enable(p, setup = setup); return }
        if (working || _ui.value.access != AccessStatus.READY) return
        val renamed = name.trim()
        _ui.update { it.copy(busy = Busy("Saving name…")) }
        viewModelScope.launch {
            val error = try {
                val profiles = withContext(cardDispatcher) { lpa.setNickname(p.iccid, renamed); reloadUntil() }
                _ui.update { it.copy(busy = null, profiles = profiles) }
                null
            } catch (e: CancellationException) { throw e } catch (e: Exception) { e }
            // A name that didn't save mustn't cancel the switch that was asked for.
            when {
                error != null && (!switch || !backend.isReady()) -> failed(error, "Couldn't rename eSIM")
                switch -> {
                    _ui.update { it.copy(busy = null) }
                    val named = _ui.value.profiles.firstOrNull { it.isdpAid == p.isdpAid } ?: p
                    enable(named, error?.let { "The name wasn't saved. Tap the eSIM in the list to rename it." }, setup)
                }
                else -> _ui.update { it.copy(info = "Renamed to $renamed") }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null, errorTitle = null) }
    fun showInfo(message: String) = _ui.update { it.copy(info = message) }
    fun clearInfo() = _ui.update { it.copy(info = null, infoSimSettings = false) }

    /** Re-read the profile list until [confirmed] holds. After a switch ([restarting]) the card
     *  restarts: Android takes up to about 30 seconds to bring the SIM back, reads fail meanwhile,
     *  and card access may drop, so this waits longer and reconnects by itself. */
    private fun reloadUntil(confirmed: (List<EuiccProfile>) -> Boolean = { true },
                            restarting: Boolean = false): List<EuiccProfile> {
        var last: Exception? = null
        retryFor(if (restarting) 45_000 else 10_000, if (restarting) 1500 else 1000) {
            try {
                if (!backend.isReady()) {
                    if (!restarting) throw last ?: ApduException("Card access disconnected. Reconnect first.")
                    backend.reconnect() // Wireless debugging is normally still on: just the link dropped
                }
                val list = lpa.getProfiles().activeFirst()
                lpa.card?.eid?.let { store.addSeenOnCard(it, list.map { p -> p.iccid }) }
                if (confirmed(list)) return list
                _ui.update { it.copy(profiles = list) }
                last = ApduException("The card didn't confirm the change. Refresh to check its current state.")
            } catch (e: Exception) {
                if (reloadGivesUp(e, restarting, backend.isReady())) throw e
                last = e
                if (restarting) _ui.update { it.copy(busy = it.busy?.copy(label = "Waiting for the card to restart…")) }
            }
        }
        throw last ?: IllegalStateException("Could not reload profiles")
    }

    /** [changing]: the operation could have changed the card, so a lost connection may have cut it off midway. */
    private fun failed(e: Exception, title: String?, message: String = e.message ?: "Card operation failed",
                       changing: Boolean = true) {
        val ready = backend.isReady()
        val unpaired = e is PairingRequiredException // the phone dropped the pairing: straight to pairing again
        _ui.update { it.copy(loading = false, busy = null,
            access = if (ready) AccessStatus.READY else AccessStatus.SETUP, background = ready && it.background,
            eid = if (ready) it.eid else null, profiles = if (ready) it.profiles else emptyList(),
            details = if (ready) it.details else null, waiting = if (ready) it.waiting else null,
            paired = it.paired && !unpaired, pairingExpired = it.pairingExpired || unpaired,
            allowAgain = it.allowAgain || e is AllowNeededException,
            explainedError = if (e is AllowNeededException) e.message else it.explainedError,
            // A dropped connection keeps the Add eSIM form (and what was typed) for after reconnecting.
            error = if (unpaired) null else message, errorTitle = if (ready) title else null) }
        // Lost card access mid-operation: get it back, then show the card as it really is now. At most
        // every 30 s, so a card that keeps dropping the link can't cause a reconnect loop.
        val now = SystemClock.elapsedRealtime()
        if (!ready && now - lastRecovery > 30_000) {
            lastRecovery = now
            autoConnect(ready = if (changing) "Card access is back. Check the list: the last change may have gone through."
                else "Card access is back.")
        }
    }
    private var lastRecovery = -30_000L

    private companion object {
        const val INCOMING = "incoming"
        const val ADDING = "adding"
        const val ADD_TARGET = "add-target"
        // Serialize entire LPA operations, not just individual APDUs. One thread for the process, not per ViewModel: an
        // install left running by people swiping the app away finishes first, and a reopened app queues behind it.
        val worker = Executors.newSingleThreadExecutor()
        val cardDispatcher = worker.asCoroutineDispatcher()
    }
}

/** Ends what a ViewModel on its way out left waiting on the card thread: the phone's question ([cancelConnect]) and the
 *  check for eSIMs ([check]). Both close connections, which is network I/O: not on the main thread. */
internal fun stopWaiting(cancelConnect: () -> Unit, check: Es9Client.Cancel?): Thread =
    Thread { cancelConnect(); check?.cancel() }.apply { isDaemon = true; start() }

/** How long to wait before each new try at notices left after a switch: about 5, 20 and 60 s after it. */
internal val NOTICE_RETRIES = listOf(5_000L, 15_000L, 40_000L)

/** Runs [attempt], which says whether anything is left to do, and again after each of [waits] for as long as it is. */
internal suspend inline fun whileLeft(waits: List<Long>, attempt: () -> Boolean) {
    var left = attempt()
    for (wait in waits) {
        if (!left) return
        delay(wait)
        left = attempt()
    }
}

/** The notices [send] got through, of [pending], oldest first. After one failure a host is skipped for the rest of the
 *  run, so a server that has shut down costs one time-out, not one per notice. */
internal inline fun deliverEach(pending: List<Lpa.Notification>, send: (Lpa.Notification) -> Boolean): List<Lpa.Notification> {
    val down = HashSet<String>()
    val delivered = ArrayList<Lpa.Notification>()
    for (n in pending) {
        val host = n.address.lowercase()
        if (host in down) continue
        if (send(n)) delivered += n else down += host
    }
    return delivered
}

/** Whether connecting by itself should try once more after a failure: not for what only people can change, as waiting
 *  won't, or a search for Wireless debugging's port, which has waited already. [portMayOpen]: USB debugging just came
 *  on, so a closed debugging port may open yet. */
internal fun worthRetrying(e: Exception, portMayOpen: Boolean) = !(e is PairingRequiredException ||
    e is SetupNeededException || e is AllowNeededException || (e is DebuggingPortClosedException && !portMayOpen) ||
    e is NearbyDevicesNeededException || e is PortNotFoundException)

/** Whether a failed re-read of the card ends the wait for it. [ready]: card access is still up. While the card restarts
 *  a read can fail any number of times, but no number of them changes what only people can. */
internal fun reloadGivesUp(e: Exception, restarting: Boolean, ready: Boolean) = e is PairingRequiredException ||
    e is AllowNeededException || e is NearbyDevicesNeededException || (!restarting && !ready)

/** Runs [attempt] for up to [ms], [pauseMs] apart, until it leaves with a return from the caller. Uptime, not the wall
 *  clock: the network often corrects the clock right after a switch. The pause comes after the check of the time, so
 *  when the phone sleeps, or Android freezes the app, past the end of a pause, one more attempt still follows it. */
internal inline fun retryFor(ms: Long, pauseMs: Long, now: () -> Long = SystemClock::elapsedRealtime,
                             pause: (Long) -> Unit = Thread::sleep, attempt: () -> Unit) {
    val deadline = now() + ms
    var attempts = 0
    while (attempts == 0 || now() < deadline) {
        if (attempts++ > 0) pause(pauseMs)
        attempt()
    }
}

/** Asking for root or Shizuku is what failed, not ADB. */
internal fun failedOtherWay(e: Exception) = e is RootApduBackend.DeniedException ||
    e is RootApduBackend.NotRootedException || e is ShizukuApduBackend.Failure

/** Overall download progress. Installing on the card takes most of the time, so it gets most of the bar. */
internal fun overall(step: Lpa.Step, f: Float): Float {
    val p = if (step == Lpa.Step.INSTALL) 0.3f + 0.7f * f else 0.3f * step.ordinal / Lpa.Step.INSTALL.ordinal
    return (p * 100).roundToInt() / 100f // StateFlow drops equal values, so this also throttles updates
}

/** Network failures reaching [server] in plain words; card and server errors keep their own message. [reach]: what to
 *  try when the server couldn't be reached. [otherwise]: for anything unforeseen, whose own text no one could act on. */
internal fun networkMessage(e: Exception, server: String = "the eSIM provider's server",
                            reach: String = "Check your internet connection and try again.",
                            otherwise: String = "Something went wrong. Try again."): String {
    if (e is ApduException || e is PairingRequiredException || e is Es9Client.Es9Exception ||
        e is ConfirmationCodeNeededException) return e.message ?: "Card operation failed"
    // Anywhere in the chain: Android wraps them (an UnknownHostException around a GaiException).
    val chain = generateSequence<Throwable>(e) { it.cause }.toList()
    return when {
        chain.any { it is UnknownHostException || it is ConnectException } -> "Couldn't reach $server. $reach"
        // Public Wi-Fi that wants a sign-in first can do this as well as the next: it drops or intercepts secure traffic.
        chain.any { it is SocketTimeoutException } ->
            "No answer from $server. On public Wi-Fi, open a web page first to sign in. Then try again."
        chain.any { it is SSLException } -> "Couldn't make a secure connection to $server. Check that your phone's date " +
            "and time are right (search your phone's Settings for \"Date & time\"). On public Wi-Fi, open a web page " +
            "first to sign in. Then try again."
        // After the others, which are IOExceptions too. Card links never get here: they end up as ApduExceptions.
        chain.any { it is IOException } -> "The connection to $server dropped. Check your internet connection and try again."
        else -> otherwise
    }
}

private fun List<EuiccProfile>.activeFirst() = sortedByDescending { it.enabled }

/** The card to show once card access switched to another way: the one in view before ([Keep]), or for a card direct
 *  access couldn't open, another than that one ([Other]), the one in Android's SIM [Other.sim] (from 0) if it's found.
 *  By EID: each way in numbers slots its own way, so an old slot would be another card. */
private sealed interface Landing {
    data class Keep(val eid: String) : Landing
    data class Other(val notEid: String?, val sim: Int) : Landing
}
