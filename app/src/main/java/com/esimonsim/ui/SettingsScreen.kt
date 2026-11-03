package com.esimonsim.ui

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.esimonsim.adb.AdbIdentity
import com.esimonsim.apdu.AccessMethod
import com.esimonsim.apdu.AllowNeededException
import com.esimonsim.apdu.CardAccess
import com.esimonsim.apdu.DebuggingPortClosedException
import com.esimonsim.apdu.NearbyDevicesNeededException
import com.esimonsim.apdu.OmapiApduBackend
import com.esimonsim.apdu.PairingRequiredException
import com.esimonsim.apdu.RootApduBackend
import com.esimonsim.apdu.ShizukuApduBackend
import com.esimonsim.apdu.ShizukuStatus

/** This app's Settings: how it reaches the card, what's set up for it on this phone, and its look. A pane of the main
 *  screen, not a screen of its own: it needs the view model, opens the panes that set things up, and stays open
 *  through a reconnect or a change of theme. [accessDialogFor]: the Card access dialog is open, from here (-1) or for
 *  the card in that SIM (from 0), which direct access can't open; null while it's closed. [onAccessCancel]: it closed
 *  without a pick. */
@Composable
fun SettingsScreen(ui: LpaUi, vm: LpaViewModel, modifier: Modifier, accessDialogFor: Int?,
                   onAccessDialog: (Int?) -> Unit, onAccessCancel: () -> Unit, onSimSetup: () -> Unit,
                   onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val sdk = Build.VERSION.SDK_INT
    val ready = ui.access == AccessStatus.READY
    // A switch or an install that carries on without the app in view: card access can't change under it.
    val cardWork by CardWork.active.collectAsStateWithLifecycle()
    val busy = ui.access == AccessStatus.CONNECTING || ui.loading || ui.busy != null
    val ways = accessWays(sdk, ui.directAvailable, ui.rootFound, ui.shizuku, ui.method, ui.preferred)
    // Only ADB on offer: nothing to choose, so the row just says what's in use.
    val choosable = ways.size >= 2
    var stopAccess by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ready) { if (!ready) stopAccess = false }
    var themeDialog by rememberSaveable { mutableStateOf(false) }
    var theme by remember { mutableStateOf(ThemeSetting.get(context)) }
    // Here, not in the dialog: that closes before "Nearby devices" is asked.
    val nearby = rememberNearbyAccess()
    // The SIM (from 0) the dialog was for when it sent people to start Shizuku, or -1: coming back, the tap on the row
    // here is for that card still, so it comes into view after the switch.
    var shizukuFor by rememberSaveable { mutableIntStateOf(-1) }
    val pick: (AccessMethod?, Int) -> Unit = { way, sim ->
        onAccessDialog(null)
        val toStart = way == AccessMethod.SHIZUKU && ui.shizuku == ShizukuStatus.STOPPED
        shizukuFor = if (toStart && ready) sim else -1
        when {
            // Started in Shizuku's own app, it's running when people come back, and a tap here switches. Not
            // connected, the choice is kept first, and the app connects by itself once Shizuku runs.
            toStart -> {
                if (!ready) { vm.chooseAccess(way); onClose() }
                openShizuku(context)
            }
            // Android 17: finding Wireless debugging's port needs "Nearby devices". Either answer goes on to the
            // switch, as a helper that's still running needs no search. Pairing asks for itself.
            ready && way == AccessMethod.ADB && sdk >= 37 && ui.paired && !ui.pairingExpired ->
                nearby.ask(context) { vm.chooseAccess(way, sim) }
            // Not connected, what comes next is on the setup screens.
            else -> { vm.chooseAccess(way, sim); if (!ready) onClose() }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DetailsSection("On this phone") {
            InfoCard {
                val help = listOfNotNull(accessHelp(ui, choosable, sdk), ownerNote(ui, ways, sdk),
                    "Card access can't change while the card is busy.".takeIf { cardWork && !busy }).joinToString("\n\n")
                InfoRow("Card access", accessValue(ui, choosable, sdk), help,
                    { onAccessDialog(shizukuFor) }.takeIf { choosable && !busy && !cardWork })
                if (ready) SetUpHere(ui, onSimSetup)
                // Unverified links open in the browser until people allow them for an app (Android 12+).
                if (Build.VERSION.SDK_INT >= 31) {
                    InfoDivider()
                    LinksRow()
                }
            }
        }
        DetailsSection("Appearance") {
            InfoCard { InfoRow("Theme", theme.label, onClick = { themeDialog = true }) }
        }
        // Last, so the helps above that say to tap "Stop card access" below stay true.
        if (ready) OutlinedButton(onClick = { stopAccess = true }, enabled = !ui.loading && ui.busy == null && !cardWork,
            modifier = Modifier.fillMaxWidth()) { Text("Stop card access") }
    }

    accessDialogFor?.let { sim ->
        AccessDialog(ui, ways, sim, onDismiss = { shizukuFor = -1; onAccessCancel() }) { way -> pick(way, sim) }
    }
    if (themeDialog) ThemeDialog(theme, onDismiss = { themeDialog = false }) { choice ->
        // Closed first: the flag is saved, so it would come back after the relaunch.
        themeDialog = false
        if (choice != theme) {
            theme = choice
            activity?.let { ThemeSetting.set(it, choice) }
        }
    }
    if (ready && stopAccess) StopAccessDialog(ui, onDismiss = { stopAccess = false }) {
        stopAccess = false
        onClose()
        vm.disconnect()
    }
}

/** What's set up for this app on this phone besides the way in, while connected: as the Details sheet had it, with USB
 *  debugging on a row of its own. */
@Composable
private fun SetUpHere(ui: LpaUi, onSimSetup: () -> Unit) {
    val context = LocalContext.current
    val adb = ui.method == AccessMethod.ADB
    // Android 10 and older: no Wireless debugging. A computer turned on Android's debugging port, which
    // lasts until the phone restarts, and needs USB debugging on to listen.
    val legacy = Build.VERSION.SDK_INT < 30
    // The background helper only outlives Wi-Fi with USB debugging on, which Android 17 may not tell apps.
    if (adb && ui.background && !legacy && ui.settings.usbDebugging != true) {
        InfoDivider()
        if (ui.settings.usbDebugging == null) InfoRow("USB debugging", "Can't check", "Card access keeps working " +
            "without Wi-Fi while it's on. Tap to check in Developer options.") { openDeveloperOptions(context) }
        else InfoRow("USB debugging", "Off", "Turn it on in Developer options to keep card access working without " +
            "Wi-Fi, until your phone restarts or this app is updated. Some banking apps won't open while it's on.") {
            openDeveloperOptions(context)
        }
    }
    // Android keeps these from apps; with direct access, Shizuku or a pairing just for them is optional.
    if (ui.method == AccessMethod.DIRECT) {
        InfoDivider()
        when {
            // Tried first by a switch, and asks nobody anything.
            ui.shizuku == ShizukuStatus.ALLOWED -> InfoRow("Mobile data and roaming", "Shizuku",
                "After a switch, the app sets the ones you tick while Shizuku is running.")
            // A switch found that the phone forgot this app (Android 10 after 7 days unused, say).
            ui.paired && legacy && ui.simAllowNeeded -> InfoRow("Mobile data and roaming", "Allow again",
                "Your phone no longer allows this app's debugging access, so a switch can't set these. " +
                    "Tap to allow it again.", onSimSetup)
            // Its steps stay at hand: after a restart, they're needed again.
            ui.paired && legacy -> InfoRow("Mobile data and roaming", "USB debugging",
                "After a switch, the app sets the ones you tick, while USB debugging is on. After a " +
                    "restart, that needs the computer again: tap for the steps.", onSimSetup)
            // Shizuku needs no Wireless debugging: its setup is open to those who have it.
            ui.paired -> InfoRow("Mobile data and roaming", "Wireless debugging",
                "After a switch, the app sets the ones you tick while Wireless debugging is on." +
                    if (ui.shizuku != ShizukuStatus.ABSENT) " Tap to use Shizuku instead." else "",
                onSimSetup.takeIf { ui.shizuku != ShizukuStatus.ABSENT })
            else -> InfoRow("Mobile data and roaming", "Set up", "Optional. Android only lets this app " +
                "change them through " + (if (legacy) "its debugging tool, set up with a computer"
                    else "Wireless debugging") + (if (ui.shizuku != ShizukuStatus.ABSENT) " or Shizuku" else "") +
                    (if (ui.rootFound) " or root" else "") + ". " +
                "Otherwise, a switch offers your phone's SIM settings.", onSimSetup)
        }
    }
    // Card access only uses the pairing to start its helper, so it can go unused for a week easily.
    if (adb || (ui.method == AccessMethod.DIRECT && ui.paired)) {
        InfoDivider()
        when {
            // Android 10 forgets allowed keys after a week too, and has no switch to stop it. With direct
            // access the app connects only for a switch, and then without asking.
            legacy && ui.settings.pairingExpires -> InfoRow("Allowed", "Expires after 7 days unused",
                if (adb) "Then your phone asks \"Allow USB debugging?\" again the next time this app connects."
                    else "Then allow this app again from \"Mobile data and roaming\" above.")
            legacy -> InfoRow("Allowed", "Kept", "Your phone remembers this app until you tap \"Revoke " +
                "USB debugging authorizations\" in Developer options.")
            ui.settings.pairingExpires -> InfoRow("Pairing", "Expires after 7 days unused",
                "Then the app needs pairing again the next time it starts its helper, e.g. after a restart. " +
                    "To keep it, turn on \"Disable adb authorization timeout\" in Developer options.") {
                openDeveloperOptions(context)
            }
            else -> InfoRow("Pairing", "Kept", "Android keeps this app's pairing until you remove it.")
        }
    }
}

@RequiresApi(31)
@Composable
private fun LinksRow() {
    val context = LocalContext.current
    val on = rememberLinksAllowed()
    InfoRow("eSIM links", if (on) "On" else "Set up",
        if (on) "\"Install on iPhone\" links from eSIM shops open in this app."
        else "Opens \"Install on iPhone\" links from eSIM shops in this app: tap, then add " +
            "esimsetup.apple.com under \"Add link\".") { openLinkSettings(context) }
}

/** Card access: Automatic, then the ways on this phone, each saying what choosing it means now. Android's single
 *  choice: a tap picks and closes. The selected row only closes where it's what's in use; otherwise it tries again.
 *  [sim]: opened for the card in that SIM (from 0), which direct access can't open, or -1. */
@Composable
private fun AccessDialog(ui: LpaUi, ways: List<AccessMethod>, sim: Int, onDismiss: () -> Unit,
                         onPick: (AccessMethod?) -> Unit) {
    val sdk = Build.VERSION.SDK_INT
    val ready = ui.access == AccessStatus.READY
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Card access") },
        text = {
            // A lead line, five rows and a footer don't fit on a small phone.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (sim >= 0) leadLine(sim + 1, ways, sdk, both = ui.manageable > 0)?.let {
                    Text(it, Modifier.padding(bottom = 8.dp))
                }
                Column(Modifier.selectableGroup()) {
                    for (way in listOf<AccessMethod?>(null) + ways) {
                        val selected = way == ui.preferred
                        // Chosen and in use: nothing to do.
                        val done = selected && ready && (way == null || way == ui.method)
                        // A switch would end the pairing under way, or go on without it.
                        val pairing = ui.pairing.active && !done
                        val gone = way == AccessMethod.SHIZUKU && ui.shizuku == ShizukuStatus.ABSENT && !done
                        RadioRow(wayLabel(way, sdk), if (pairing) "Finish or cancel pairing first." else wayRowHelp(way, ui, sdk),
                            selected, enabled = !pairing && !gone) { if (done) onDismiss() else onPick(way) }
                    }
                }
                footer(ways, sdk)?.let {
                    Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ThemeDialog(current: ThemeChoice, onDismiss: () -> Unit, onPick: (ThemeChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme") },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                ThemeChoice.entries.forEach { c -> RadioRow(c.label, ThemeSetting.help(c), c == current) { onPick(c) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One choice in a dialog: the whole row is the radio button, at least 48dp tall, with an optional line of help. */
@Composable
private fun RadioRow(label: String, help: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    // 48dp: with onClick = null the RadioButton adds no touch target of its own.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        // Material's disabled look for text.
        Column(if (enabled) Modifier else Modifier.alpha(0.38f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private const val STOPS_HELPER = " and stops its background helper, the small program that keeps it connected"

@Composable
private fun StopAccessDialog(ui: LpaUi, onDismiss: () -> Unit, onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop card access?") },
        text = { Text(when {
            ui.method == AccessMethod.DIRECT || ui.method == AccessMethod.ROOT || ui.method == AccessMethod.SHIZUKU ->
                "The app disconnects from your card until you reconnect."
            // Android 10 and older: the debugging port stays on until a restart, so reconnecting needs no computer.
            Build.VERSION.SDK_INT < 30 -> "The app disconnects from your card" + (if (ui.background) STOPS_HELPER else "") +
                ". You can reconnect without the computer until your phone restarts, which also turns off what this " +
                "app connects to."
            // Running without Wireless debugging (no Wi-Fi, say): starting again needs it, and so Wi-Fi.
            ui.background && ui.settings.wirelessDebugging != true -> "The app disconnects from your card$STOPS_HELPER. " +
                "To connect again, you'll need Wi-Fi and Wireless debugging."
            ui.background -> "The app disconnects from your card$STOPS_HELPER. Wireless debugging stays on until you " +
                "turn it off or restart your phone."
            else -> "The app disconnects from your card. Wireless debugging stays on until you turn it off or " +
                "restart your phone."
        }, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onStop) { Text("Stop") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Whether people let this app open "Install on iPhone" links; read again each time the app comes back. */
@RequiresApi(31)
@Composable
private fun rememberLinksAllowed(): Boolean {
    val context = LocalContext.current
    var on by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        on = runCatching {
            context.getSystemService(DomainVerificationManager::class.java)
                ?.getDomainVerificationUserState(context.packageName)?.hostToStateMap?.get("esimsetup.apple.com") ==
                DomainVerificationUserState.DOMAIN_STATE_SELECTED
        }.getOrDefault(false)
        onPauseOrDispose {}
    }
    return on
}

/** This app's "Open by default" page, where people allow links it can't verify. */
@RequiresApi(31)
private fun openLinkSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
        "package:${context.packageName}".toUri())) }
}

// ---------------- what Card access says: pure, so the tests can read it ----------------

/** A way in in the middle of a sentence: "choose root or Shizuku". */
private fun wayWord(m: AccessMethod, sdk: Int) = if (m == AccessMethod.ROOT) "root" else wayLabel(m, sdk)

/** The ways that reach every card, unlike direct access, which only reaches cards that let this app in. */
private fun elevated(ways: List<AccessMethod>, sdk: Int) = (ways - AccessMethod.DIRECT).map { wayWord(it, sdk) }

/** Card access's value: the choice, where there's one to make; otherwise the way in use, as Details named it.
 *  [choosable]: more than one way is on offer. */
internal fun accessValue(ui: LpaUi, choosable: Boolean, sdk: Int): String = if (choosable) wayLabel(ui.preferred, sdk)
    else when (ui.method) {
        null -> "Not set up"
        AccessMethod.DIRECT -> "Direct"
        AccessMethod.ROOT -> "Root"
        AccessMethod.SHIZUKU -> "Shizuku"
        // Started where the background helper can't run: requests go over the debugging connection itself.
        AccessMethod.ADB -> if (ui.background) "Background helper" else if (sdk >= 30) "Wireless debugging" else "USB debugging"
    }

/** The help under Card access. Connected: why the chosen way isn't the one in use, if it isn't, then the way in use,
 *  named first under Automatic, whose value doesn't say. Otherwise where to go from here. */
internal fun accessHelp(ui: LpaUi, choosable: Boolean, sdk: Int): String {
    val method = ui.method
    if (ui.access == AccessStatus.CONNECTING) return "Connecting to your card…"
    if (ui.access != AccessStatus.READY || method == null) return when {
        ui.stopped -> "Card access is stopped. " +
            if (choosable) "Choose a way here, or go back and tap \"Reconnect\"." else "Go back and tap \"Reconnect\"."
        method == null -> "Not set up yet. " +
            if (choosable) "Choose a way here, or go back to follow the steps." else "Go back to follow the steps."
        else -> "Not connected right now. Go back to reconnect" + if (choosable) ", or choose another way here." else "."
    }
    val now = (if (choosable && ui.preferred == null) "Now using ${wayName(method, sdk, ui.background)}. " else "") +
        inUseHelp(ui, sdk)
    return listOfNotNull(fallbackNote(ui, sdk), now).joinToString("\n\n")
}

/** What the way in use means, as the Details sheet said it. */
private fun inUseHelp(ui: LpaUi, sdk: Int): String {
    // Android 10 and older: no Wireless debugging. A computer turned on Android's debugging port, which lasts until
    // the phone restarts, and needs USB debugging on to listen.
    val legacy = sdk < 30
    return when {
        ui.method == AccessMethod.DIRECT -> "Your card lets this app manage it, so nothing needs setting up."
        ui.method == AccessMethod.ROOT -> "Works without Wi-Fi or Wireless debugging. This app's helper ends when you " +
            "swipe the app away from Recent apps or tap \"Stop card access\" below."
        ui.method == AccessMethod.SHIZUKU -> "Works while Shizuku is running. This app's helper ends when you swipe " +
            "the app away from Recent apps or tap \"Stop card access\" below." +
            // Only a Shizuku started with root leaves Android's debugging service behind.
            if (ui.settings.usbDebugging == true) "" else " If you started Shizuku with Wireless debugging, Android " +
                "can stop it when Wi-Fi drops. Turning on \"USB debugging\" in Developer options may help, but some " +
                "banking apps won't open while it's on."
        legacy && !ui.background -> "Set up with a computer. Works while USB debugging is on, until your phone " +
            "restarts. After a restart, it needs the computer again."
        // Android 7.1 and older leave the helper running when their debugging service stops.
        legacy && sdk < 26 -> "Set up with a computer. Usually keeps working until your phone restarts, even with " +
            "USB debugging off. After a restart, it needs the computer again."
        legacy -> "Set up with a computer. Keeps working while USB debugging is on, until your phone restarts. After " +
            "a restart, it needs the computer again."
        !ui.background -> "Works while Wireless debugging is on."
        ui.settings.usbDebugging == true -> "Keeps working without Wi-Fi until your phone restarts or this app is updated."
        // Android 17 may not tell apps whether USB debugging is on; its own row says where to look.
        ui.settings.usbDebugging == null -> "Keeps working without Wi-Fi until your phone restarts or this app is " +
            "updated, if USB debugging is on. Otherwise it stops when Wireless debugging goes off."
        // Android stops its debugging service, and the helper with it, once both kinds are off.
        else -> "Stops when Wireless debugging goes off, which Android also does when Wi-Fi drops or changes."
    }
}

/** Why card access uses another way than the one chosen, and what to do; null when it uses the chosen one. */
private fun fallbackNote(ui: LpaUi, sdk: Int): String? {
    val chosen = ui.preferred ?: return null
    val method = ui.method?.takeIf { it != chosen && ui.access == AccessStatus.READY } ?: return null
    val shizuku = ui.shizuku != ShizukuStatus.ABSENT
    return fallbackWhy(ui.preferredFailure, chosen, sdk, ui.paired, shizuku).replaceFirstChar { it.uppercase() } +
        ", so card access uses ${wayName(method, sdk, ui.background)} for now. " +
        fallbackAction(ui.preferredFailure, chosen, ui.paired, shizuku)
}

/** Why [way] didn't connect, as part of a sentence, from what it threw ([e], see [CardAccess.preferredFailure]).
 *  Shorter than what the Reconnect screens say ([CardAccess.reason]), which is about tapping "Reconnect" there.
 *  [paired]: ADB was set up. */
internal fun fallbackWhy(e: Exception?, way: AccessMethod, sdk: Int, paired: Boolean, shizukuInstalled: Boolean): String {
    val debugging = if (sdk >= 30) "Wireless debugging" else "USB debugging"
    return when (e) {
        is ShizukuApduBackend.NotRunningException ->
            if (shizukuInstalled) "Shizuku isn't running" else "Shizuku isn't on this phone any more"
        is ShizukuApduBackend.DeniedException -> "Shizuku doesn't allow this app"
        is ShizukuApduBackend.StartException -> "Shizuku couldn't start this app's card helper"
        is RootApduBackend.NotRootedException -> "root isn't available to this app"
        is RootApduBackend.DeniedException -> "your root app didn't allow this app"
        is OmapiApduBackend.UnavailableException -> when {
            e.timedOut -> "Android's SIM service didn't answer in time"
            e.locked -> "your card didn't let this app in directly"
            else -> "no eSIM card answered directly"
        }
        is PairingRequiredException -> "your phone forgot this app's pairing"
        is NearbyDevicesNeededException -> "this app needs \"Nearby devices\" to find Wireless debugging"
        is DebuggingPortClosedException -> "USB debugging needs your computer again after a restart"
        is AllowNeededException -> "your phone no longer allows this app's debugging access"
        // Not tried: ADB that's off, or not set up.
        else -> when {
            way != AccessMethod.ADB -> "${wayName(way, sdk, background = false)} didn't connect"
            e != null && e !== CardAccess.NotTried -> "this app couldn't connect through $debugging"
            paired -> "$debugging is off"
            else -> "$debugging isn't set up"
        }
    }
}

/** What to do about [fallbackWhy]: tapping the chosen way in the Card access dialog tries it again. */
internal fun fallbackAction(e: Exception?, way: AccessMethod, paired: Boolean, shizukuInstalled: Boolean): String = when {
    e is ShizukuApduBackend.NotRunningException ->
        if (shizukuInstalled) "Start Shizuku, then tap it here." else "Choose another way here."
    e is RootApduBackend.NotRootedException || e is RootApduBackend.DeniedException ->
        "Allow $APP_NAME in your root app, then tap it here."
    e is DebuggingPortClosedException -> "Tap it here for the steps."
    way == AccessMethod.ADB && (e == null || e === CardAccess.NotTried) ->
        if (paired) "Turn it on, then tap it here." else "Tap it here to set it up."
    else -> "Tap it here to try again."
}

/** The line under [way] in the Card access dialog: what choosing it means now. The chosen way, while card access uses
 *  another, says why and what to do, and a tap on it tries again. */
internal fun wayRowHelp(way: AccessMethod?, ui: LpaUi, sdk: Int): String {
    val ready = ui.access == AccessStatus.READY
    val inUse = ready && way != null && way == ui.method
    if (ready && way != null && way == ui.preferred && !inUse) {
        val shizuku = ui.shizuku != ShizukuStatus.ABSENT
        return "Chosen, but ${fallbackWhy(ui.preferredFailure, way, sdk, ui.paired, shizuku)}. " +
            fallbackAction(ui.preferredFailure, way, ui.paired, shizuku)
    }
    return when (way) {
        null -> "Uses the first of the ways below that works." +
            (ui.method?.takeIf { ready }?.let { " Now: ${wayLabel(it, sdk)}." } ?: "")
        AccessMethod.ROOT -> when {
            inUse -> "In use. Works without Wi-Fi."
            ui.rootDeclined -> "Your root app didn't allow this app. Allow $APP_NAME there first."
            else -> "Your root app may ask. Works without Wi-Fi."
        }
        AccessMethod.DIRECT -> when {
            inUse -> "In use. Nothing to set up."
            ui.directRefused -> "Didn't open your card last time. Most cards only let their maker's app in."
            else -> "Nothing to set up, for cards that let any app in."
        }
        AccessMethod.SHIZUKU -> if (inUse) "In use. Works while Shizuku is running." else when (ui.shizuku) {
            ShizukuStatus.ALLOWED -> "Already allows this app. Works while Shizuku is running."
            ShizukuStatus.NOT_ASKED -> "Shizuku asks you to allow this app. Works while it's running."
            ShizukuStatus.DECLINED -> "You said no to this app in Shizuku. Choose it to be asked again."
            ShizukuStatus.STOPPED -> "Not running. Tap to open Shizuku and start it, then tap it here again."
            ShizukuStatus.ABSENT -> "Not on this phone any more."
        }
        AccessMethod.ADB -> if (sdk >= 30) when {
            inUse && ui.background -> "In use, through its background helper."
            inUse -> "In use. Works while Wireless debugging is on."
            ui.pairingExpired -> "Needs pairing again: about a minute."
            ui.paired && ui.settings.wirelessDebugging == false -> "Paired. Turn on Wireless debugging, then tap it here."
            ui.paired -> "Already paired. Works while Wireless debugging is on."
            else -> "Pair this app once, on Wi-Fi: about 2 minutes. Works while Wireless debugging is on."
        } else when {
            inUse -> "In use, until your phone restarts."
            ui.paired -> "Set up with a computer before. After each restart, it needs the computer again."
            else -> "Set up with a computer: about 5 minutes. Again after each restart."
        }
    }
}

/** Under Card access in Settings, for owners of a second eSIM card that direct access can't open: what would reach it.
 *  Only once the first look for cards is done, with direct access in use, and one card at most to manage. Named where
 *  a look found such a card ([LpaUi.lockedSims]), which shows there's room for it; a phone with its own eSIM chip never
 *  names one, so there it's only the question, and only where the phone has room for another card. */
internal fun ownerNote(ui: LpaUi, ways: List<AccessMethod>, sdk: Int): String? {
    val elevated = elevated(ways, sdk)
    val sims = ui.lockedSims
    if (ui.access != AccessStatus.READY || ui.method != AccessMethod.DIRECT || ui.lookingForCards || ui.manageable >= 2 ||
        (sims.isEmpty() && !ui.multiSim) || elevated.isEmpty()) return null
    val choose = listed(elevated, "or")
    return when (sims.size) {
        0 -> "Two eSIM cards in your SIM slots, but only one shows? Choose $choose here to reach both."
        1 -> "The card in SIM ${sims[0]} doesn't let this app in directly. Choose $choose here to manage it too."
        else -> "The cards in ${listed(sims.map { "SIM $it" })} don't let this app in directly. Choose $choose " +
            "here to manage them too."
    } + if (sdk < 29) " On this version of Android, ${if (elevated.size == 1) "it reaches" else "those reach"} a " +
        "card outside SIM 1 only while one of its eSIMs is on." else ""
}

/** The Card access dialog's first line when opened for the card in SIM [sim] (from 1), which direct access can't
 *  open. [both]: there's a card it does open, so this is about managing both. */
internal fun leadLine(sim: Int, ways: List<AccessMethod>, sdk: Int, both: Boolean): String? {
    val elevated = elevated(ways, sdk).ifEmpty { return null }
    return "Direct access can't open the card in SIM $sim. " +
        (if (both) "To manage both cards, choose " else "To manage it, choose ") + listed(elevated, "or") + "."
}

/** Below the ways, where direct access is one: what the others reach that it doesn't. Android 9's helper reaches a
 *  card outside SIM 1 only while one of its eSIMs is on (see SlotReach). */
internal fun footer(ways: List<AccessMethod>, sdk: Int): String? {
    if (AccessMethod.DIRECT !in ways) return null
    val elevated = elevated(ways, sdk).ifEmpty { return null }
    return if (sdk < 29) "On this version of Android, ${listed(elevated)} ${if (elevated.size == 1) "reaches" else "reach"} " +
        "a card in SIM 1, or in another SIM slot while one of its eSIMs is on. Direct reaches any slot, but only " +
        "cards that let this app in."
    else listed(elevated).replaceFirstChar { it.uppercase() } + " can reach every card in your phone. Direct only " +
        "reaches cards that let this app in."
}

/** The error dialog's title when switching card access to [way] failed. */
internal fun switchTitle(way: AccessMethod?, sdk: Int) = when (way) {
    null -> "Couldn't switch card access"
    AccessMethod.DIRECT -> "Couldn't switch to direct access"
    else -> "Couldn't switch to ${wayWord(way, sdk)}"
}

/** What a failed switch to [way] says, before "Card access stays as it was."; null where nothing needs saying, as
 *  when the pane for pairing again opens instead. [mayConnect]: ADB's settings are on, as far as the app can tell. */
internal fun switchError(way: AccessMethod?, e: Exception, sdk: Int, mayConnect: Boolean): String? = when (e) {
    is RootApduBackend.NotRootedException ->
        "Root isn't available to this app. If your root app hides it, allow $APP_NAME there, then try again."
    is RootApduBackend.DeniedException -> "Your root app didn't allow this app. Allow $APP_NAME in it, then try again."
    is OmapiApduBackend.UnavailableException -> when {
        e.timedOut -> "Android's SIM service didn't answer in time. Try again."
        e.locked -> "Your card doesn't let this app in directly. Most cards only let their maker's app in."
        else -> "Couldn't open your card directly."
    }
    is ShizukuApduBackend.NotRunningException -> "Shizuku isn't running. Start it in Shizuku's app, then try again."
    is ShizukuApduBackend.DeniedException -> "Shizuku didn't allow this app. Choose Shizuku again to be asked again."
    is ShizukuApduBackend.StartException -> "Shizuku couldn't start this app's card helper. Try again."
    is PairingRequiredException -> null
    is NearbyDevicesNeededException -> "This app needs \"Nearby devices\" to find Wireless debugging. Allow it under " +
        "Permissions on this app's page in your phone's Settings, then try again."
    is AllowNeededException ->
        "Your phone didn't allow this app. Try again, and tap \"${AdbIdentity.allowButton(sdk)}\" when it asks."
    is DebuggingPortClosedException ->
        "After a restart, USB debugging needs your computer again. Follow the steps to turn it on."
    else -> when {
        way != AccessMethod.ADB -> e.message ?: "Something went wrong. Try again."
        sdk < 30 -> if (mayConnect) "Couldn't connect through USB debugging. Try again."
            else "USB debugging is off. Turn it on in Developer options, then try again."
        mayConnect -> "Couldn't connect through Wireless debugging. Check that it's on, then try again."
        else -> "Wireless debugging is off. Turn it on, then choose it here again."
    }
}
