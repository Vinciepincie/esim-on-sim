package com.esimonsim.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esimonsim.R
import com.esimonsim.adb.AdbIdentity
import com.esimonsim.adb.Pairing
import com.esimonsim.adb.PairingFlow
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.esimonsim.apdu.AccessMethod
import com.esimonsim.apdu.AdbApduBackend
import com.esimonsim.apdu.ShizukuClient
import com.esimonsim.apdu.ShizukuStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything before the card is reachable: first-time setup, reconnecting, and connecting. */
@Composable
fun SetupScreen(ui: LpaUi, vm: LpaViewModel, modifier: Modifier = Modifier) {
    // "Pair again" lasts until the next successful pairing, even if connecting afterwards fails.
    var pairAgainAt by rememberSaveable { mutableStateOf<Int?>(null) }
    // Kept above the Connecting screen, so a failed attempt comes back with the port still filled in.
    val port = rememberSaveable { mutableStateOf("") }
    val pairAgain = pairAgainAt == ui.pairing.pairedCount
    // Card access worked without setup here before (the card let the app in, root, Shizuku): reconnecting is all it
    // takes, unless someone chooses to set up Wireless debugging instead.
    var setUpAnyway by rememberSaveable { mutableStateOf(false) }
    val noSetup = (ui.method == AccessMethod.DIRECT || ui.method == AccessMethod.ROOT ||
        ui.method == AccessMethod.SHIZUKU) && !setUpAnyway
    // Android 10 and older: "Full setup" from the reconnect screen; and the computer step, which nothing on the
    // phone can check, done. Both kept above the Connecting screen, which the last step leads to.
    var computerAgain by rememberSaveable { mutableStateOf(false) }
    val computerDone = rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = setUpAnyway && !pairAgain && !computerAgain) { vm.cancelPairing(); setUpAnyway = false }
    when {
        // The root app's or Shizuku's question comes up over this screen the first time; a root app may hide su until then.
        ui.access == AccessStatus.CONNECTING -> Connecting(modifier,
            askRoot = ui.rootFound && ui.method != AccessMethod.ROOT && !ui.shizukuAsking, askShizuku = ui.shizukuAsking,
            allowKey = if (ui.askingAllow) ui.adbKey.orEmpty() else null, onCancel = vm::cancelConnect)
        // The error here talks of a "Reconnect" button, which the setup steps don't have.
        noSetup -> DirectReconnect(ui, vm, modifier) { setUpAnyway = true; vm.dismissError() }
        Build.VERSION.SDK_INT < 30 ->
            if (ui.paired && !computerAgain) ComputerReconnect(ui, vm, modifier, port) { computerAgain = true; computerDone.value = false }
            else ComputerSetup(ui, vm, modifier, computerDone, port, again = ui.paired,
                onBack = if (computerAgain) {{ computerAgain = false }} else null)
        ui.paired && !pairAgain -> Reconnect(ui, vm, modifier, port) { pairAgainAt = ui.pairing.pairedCount }
        else -> Checklist(ui, vm, modifier, pairingAgain = ui.paired || ui.pairingExpired,
            onBack = if (pairAgain) {{ vm.cancelPairing(); pairAgainAt = null }} else null)
    }
}

/** Cards sit just above the background: white on light grey, and a lighter grey in dark mode. */
@Composable
fun cardColor() = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerLow
    else MaterialTheme.colorScheme.surfaceContainerLowest

@Composable
private fun Centered(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    // The scroll goes inside: on a short screen the column would otherwise squeeze its last children, the buttons, to
    // nothing. A scrolling column has no height to centre in, so the Box centres it.
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** [allowKey]: Android 10 and older, where the phone may ask to allow this app meanwhile; its fingerprint, or
 *  empty while unknown. [askShizuku]: Shizuku may ask the same. [onCancel] stops waiting for either answer. */
@Composable
private fun Connecting(modifier: Modifier, askRoot: Boolean, askShizuku: Boolean, allowKey: String?,
                       onCancel: () -> Unit) = Centered(modifier) {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text("Connecting to your card…", style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (askRoot) {
        Spacer(Modifier.height(8.dp))
        Text("If your root app asks, allow this app.", style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (askShizuku) {
        Spacer(Modifier.height(8.dp))
        Text("If Shizuku asks, allow this app.", style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (allowKey != null) {
        Spacer(Modifier.height(8.dp))
        Text("If your phone asks \"Allow USB debugging?\", tick \"Always allow from this computer\" and tap " +
            "\"${allowButton()}\"." + if (allowKey.isEmpty()) "" else " The fingerprint it shows should be:",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (allowKey.isNotEmpty()) KeyFingerprint(allowKey, Modifier.padding(top = 12.dp))
    }
    if (allowKey != null || askShizuku) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/** Card access worked without setup here before (the card let the app in, root, Shizuku), and isn't running now. */
@Composable
private fun DirectReconnect(ui: LpaUi, vm: LpaViewModel, modifier: Modifier, onSetUp: () -> Unit) = Centered(modifier) {
    Icon(painterResource(R.drawable.ic_sim), contentDescription = null, Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Text(if (ui.stopped) "Card access stopped" else "Couldn't reach your card", style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    val error = ui.error?.takeIf { !ui.stopped }
    Text(when {
            ui.stopped -> "Reconnect any time."
            ui.method == AccessMethod.ROOT && ui.rootDeclined -> "This app reaches your card with root. Allow " +
                "$APP_NAME in your root app, then tap \"Reconnect\"."
            ui.method == AccessMethod.ROOT -> "This app reaches your card with root." +
                if (error == null) " Check that the card is inserted, then tap \"Reconnect\"." else ""
            ui.method == AccessMethod.SHIZUKU -> "This app reaches your card through Shizuku." +
                if (error == null) " Check that Shizuku is running, then tap \"Reconnect\"." else ""
            else -> "This app normally reaches your card directly." +
                if (error == null) " Check that it's inserted, then tap \"Reconnect\"." else ""
        }, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { Spacer(Modifier.height(16.dp)); ErrorNote(it) }
    Spacer(Modifier.height(24.dp))
    // Stopped by a restart, usually: this app reconnects by itself once it's started.
    if (ui.method == AccessMethod.SHIZUKU && ui.shizuku == ShizukuStatus.STOPPED) {
        val context = LocalContext.current
        Button(onClick = { openShizuku(context) }, Modifier.fillMaxWidth()) { Text("Open Shizuku") }
        OutlinedButton(onClick = { vm.connect() }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Reconnect") }
    } else Button(onClick = { vm.connect() }, Modifier.fillMaxWidth()) { Text("Reconnect") }
    // A locked card instead, say, or root taken away. Android 10 and older have no Wireless debugging.
    TextButton(onClick = onSetUp) {
        Text(if (Build.VERSION.SDK_INT >= 30) "Set up Wireless debugging instead" else "Set up with a computer instead")
    }
}

/** Rooted phones: root needs no setup at all. Most root apps no longer ask: apps are allowed in them beforehand. */
@Composable
private fun RootOption(ui: LpaUi, vm: LpaViewModel) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = cardColor())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rooted phone?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text((if (ui.rootDeclined) "Root wasn't allowed for this app. " else "") + "This app can use root " +
                "instead, with nothing to set up. Allow $APP_NAME in your root app, then tap \"Use root\".",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = vm::useRoot) { Text("Use root") }
        }
    }
}

/** Shizuku is on this phone: with it running and this app allowed, there's nothing else to set up. */
@Composable
private fun ShizukuOption(ui: LpaUi, vm: LpaViewModel) {
    if (ui.shizuku == ShizukuStatus.ABSENT) return
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = cardColor())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("You have Shizuku", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(when (ui.shizuku) {
                    ShizukuStatus.STOPPED -> "This app can use Shizuku instead of the steps below, with nothing else to " +
                        "set up. Start Shizuku in its own app, and this app connects by itself."
                    ShizukuStatus.DECLINED -> "You said no to this app in Shizuku. To use Shizuku instead of the steps " +
                        "below, tap \"Use Shizuku\" and allow it. There's nothing else to set up."
                    ShizukuStatus.ALLOWED -> "Shizuku already allows this app, so you can skip the steps below. Tap " +
                        "\"Use Shizuku\"."
                    else -> "This app can use Shizuku instead of the steps below, with nothing else to set up. Tap " +
                        "\"Use Shizuku\" and allow it."
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.shizuku == ShizukuStatus.STOPPED) OutlinedButton(onClick = { openShizuku(context) }) { Text("Open Shizuku") }
            else OutlinedButton(onClick = vm::useShizuku) { Text("Use Shizuku") }
        }
    }
}

/** The same as [ShizukuOption], on the reconnect screens. */
@Composable
private fun ShizukuButton(ui: LpaUi, vm: LpaViewModel) {
    val context = LocalContext.current
    when (ui.shizuku) {
        ShizukuStatus.ABSENT -> {}
        ShizukuStatus.STOPPED -> TextButton(onClick = { openShizuku(context) }) { Text("Open Shizuku") }
        else -> TextButton(onClick = vm::useShizuku) { Text("Use Shizuku instead") }
    }
}

// ---------------- reconnect: paired, but card access isn't running (e.g. after a restart) ----------------

@RequiresApi(30)
@Composable
private fun Reconnect(ui: LpaUi, vm: LpaViewModel, modifier: Modifier, port: MutableState<String>,
                      onPairAgain: () -> Unit) {
    val context = LocalContext.current
    val s = ui.settings
    val nearby = rememberNearbyAccess()
    Column(modifier.fillMaxSize().imePadding().clearFocusOnTap().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Icon(painterResource(R.drawable.ic_sim), contentDescription = null, Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        val noWifi = !s.wifi && s.wirelessDebugging != true
        // Android 17 may not say whether Developer options is on; Wireless debugging (off) is known either way.
        val devUnknown = s.developerOptions == null
        val notInSettings = if (devUnknown) "\n\nNot in Settings? Then Developer options is off too: ${developerOptionsHint()}" else ""
        val (title, body) = when {
            s.developerOptions == false -> "Turn Developer options back on" to
                "Card access needs \"Developer options\".\n\n${developerOptionsHint()} " +
                "${developerOptionsElsewhere()} ${developerOptionsDone()}"
            noWifi -> "Connect to Wi-Fi" to
                "Wireless debugging only works on Wi-Fi. Any network will do, even without internet." +
                // The background helper would have kept card access going without Wi-Fi, had adbd stayed up.
                if (s.usbDebugging != false || ui.helperUnavailable) "" else "\n\nNext time, turn on \"USB debugging\" in " +
                    "Developer options as well: card access then keeps working without Wi-Fi until your phone " +
                    "restarts or this app is updated. Some banking apps won't open while it's on."
            // Stopped stays stopped: Wireless debugging coming back on doesn't reconnect by itself.
            ui.stopped && s.wirelessDebugging == false -> "Card access stopped" to
                "Turn on \"Wireless debugging\" when you want to use your card again, then tap \"Reconnect\".$notInSettings"
            s.wirelessDebugging == false -> "Turn Wireless debugging back on" to
                "Android switches it off when your phone restarts or changes Wi-Fi.\n\nTurn on " +
                "\"Wireless debugging\" and this app reconnects by itself. Your pairing is saved.$notInSettings"
            ui.stopped -> "Card access stopped" to
                "Wireless debugging is on. Turn it off if you're done, or reconnect any time."
            s.wirelessDebugging == null -> "Check Wireless debugging" to
                "Make sure \"Wireless debugging\" is on, then tap \"Reconnect\"."
            else -> "Ready to reconnect" to "Wireless debugging is on. Tap \"Reconnect\" to open your card."
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            s.developerOptions == false -> AboutPhoneSketch(Modifier.padding(top = 24.dp))
            !noWifi && s.wirelessDebugging == false -> DeveloperOptionsSketch(Modifier.padding(top = 24.dp))
        }
        ui.error?.let { Spacer(Modifier.height(16.dp)); ErrorNote(it) }
        if (ui.nearbyNeeded && nearby.refused) NearbyRefused(Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        val wide = Modifier.fillMaxWidth()
        val canConnect = s.developerOptions != false && !noWifi && s.wirelessDebugging != false
        when {
            s.developerOptions == false -> Button(onClick = { openAboutPhone(context) }, wide) { Text("Open About phone") }
            noWifi -> Button(onClick = { openWifi(context) }, wide) { Text("Choose Wi-Fi") }
            !canConnect -> {
                Button(onClick = { openWirelessDebugging(context) }, wide) { Text("Open Wireless debugging") }
                if (devUnknown) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { openAboutPhone(context) }, wide) { Text("Open About phone") }
                }
            }
            else -> {
                // Wireless debugging's port changed since last time, and Android 17 asks before the app looks for it.
                Button(onClick = { if (ui.nearbyNeeded) nearby.ask(context) { if (it) vm.connect() } else vm.connect() },
                    wide) { Text("Reconnect") }
                if (s.wirelessDebugging == null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { openWirelessDebugging(context) }, wide) {
                        Text("Open Wireless debugging")
                    }
                }
                if (ui.stopped) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { openWirelessDebugging(context) }, wide) {
                        Text("Open Wireless debugging")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { AboutActivity.open(context, ui) }) { Text("Why is this needed?") }
        TextButton(onClick = onPairAgain) { Text("Full setup") }
        if (ui.rootFound) TextButton(onClick = vm::useRoot) { Text("Use root instead") }
        ShizukuButton(ui, vm)
        // Only after a failed attempt: the usual cause is Wireless debugging's port not being found.
        if (canConnect && ui.error != null) {
            PortEntry(label = "Connect with a port number", button = "Connect with this port", portState = port,
                help = "On the \"Wireless debugging\" page, look under \"IP address & Port\" and enter " +
                    "the number after the colon.\nIt changes each time Wireless debugging turns on.",
                forceOpen = ui.nearbyNeeded && nearby.refused) { vm.connect(it) }
        }
    }
}

// ---------------- first-time setup checklist ----------------

private enum class StepState { DONE, CURRENT, PENDING, UNKNOWN }

private enum class PairMode { IDLE, NOTIFY, MANUAL }

/** With direct card access: an optional pairing, only so the app can set mobile data and roaming after a switch.
 *  Done once a pairing succeeds; on Android 10 and older, once the phone allowed this app, also when it did before
 *  and forgot. Or once Shizuku allows this app, or root reaches the card instead. */
@Composable
fun SimSettingsSetup(ui: LpaUi, vm: LpaViewModel, modifier: Modifier, onDone: () -> Unit) {
    if (Build.VERSION.SDK_INT < 30) {
        // Already allowed before when allowing again, so this counts successes instead. Not saved: the count starts
        // from 0 again with the app, and so must this.
        val setUpBefore = remember { ui.simSettingsSetUps }
        LaunchedEffect(ui.simSettingsSetUps, ui.method, ui.shizuku) {
            if (ui.simSettingsSetUps > setUpBefore || ui.method == AccessMethod.ROOT ||
                ui.shizuku == ShizukuStatus.ALLOWED) onDone()
        }
        // Only allowing this app again: what the computer turned on still answered.
        val computerDone = rememberSaveable { mutableStateOf(ui.simAllowNeeded) }
        ComputerSetup(ui, vm, modifier, computerDone, port = null, again = false, onBack = onDone, forSimSettings = true)
    } else {
        val pairedBefore = rememberSaveable { ui.pairing.pairedCount }
        LaunchedEffect(ui.pairing.pairedCount, ui.method, ui.shizuku) {
            if (ui.pairing.pairedCount > pairedBefore || ui.method == AccessMethod.ROOT ||
                ui.shizuku == ShizukuStatus.ALLOWED) { vm.cancelPairing(); onDone() }
        }
        Checklist(ui, vm, modifier, pairingAgain = false, onBack = { vm.cancelPairing(); onDone() }, forSimSettings = true)
    }
}

@RequiresApi(30)
@Composable
private fun Checklist(ui: LpaUi, vm: LpaViewModel, modifier: Modifier, pairingAgain: Boolean, onBack: (() -> Unit)?,
                      forSimSettings: Boolean = false) {
    val context = LocalContext.current
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val s = ui.settings
    var wdConfirmed by rememberSaveable { mutableStateOf(false) } // when Android won't say whether it's on
    var devConfirmed by rememberSaveable { mutableStateOf(false) } // the same, for Developer options (Android 17)
    var visitedAbout by rememberSaveable { mutableStateOf(false) }
    val devDone = s.developerOptions == true || (s.developerOptions == null && devConfirmed)
    val wifiDone = s.wifi || s.wirelessDebugging == true
    val wdDone = s.wirelessDebugging == true || (s.wirelessDebugging == null && wdConfirmed)
    val current = when { !devDone -> 0; !wifiDone -> 1; !wdDone -> 2; else -> 3 }
    fun state(i: Int, done: Boolean) = when {
        done -> StepState.DONE
        i == current -> StepState.CURRENT
        else -> StepState.PENDING
    }

    Column(modifier.fillMaxSize().imePadding().clearFocusOnTap().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Pairing again without an expired pairing means it came from Reconnect's "Full setup".
            Text(when {
                    forSimSettings -> "Mobile data and roaming"
                    ui.pairingExpired -> "Pair again"
                    pairingAgain -> "Full setup"
                    else -> "Set up card access"
                },
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(when {
                    forSimSettings -> "Optional: your card already works without this. Android only lets apps " +
                        "change mobile data and roaming through Wireless debugging, Shizuku or root. Pair this app, and " +
                        "after a switch it sets the ones you tick, while Wireless debugging is on."
                    ui.pairingExpired -> "This phone forgot the pairing. That happens after 7 days without " +
                        "connecting, or when it's removed under \"Paired devices\". Pairing again takes a minute."
                    pairingAgain -> "Rechecks each step and pairs this app again. Takes about a minute."
                    else -> "Takes about 2 minutes. No computer or root needed. You'll turn Wireless debugging back on " +
                        "after a restart or a Wi-Fi change."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.pairingExpired) Text("To stop it expiring, turn on \"Disable adb authorization timeout\" in " +
                "\"Developer options\".", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!forSimSettings && !pairingAgain && !ui.pairing.active) CardCheck(ui, vm)
        if (!forSimSettings) ui.error?.let { ErrorNote(it) }
        if (!forSimSettings) WhyCard(onLearnMore = { AboutActivity.open(context, ui) })
        if (ui.rootFound) RootOption(ui, vm)
        ShizukuOption(ui, vm)
        BankingNote(if (forSimSettings) "This app then can't set mobile data and roaming until you turn it back on " +
            "(the same 7 taps)." else "Card access stops until you turn it back on (the same 7 taps). Your pairing " +
            "stays saved.")
        Card(colors = CardDefaults.cardColors(containerColor = cardColor()),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            val devUnknown = s.developerOptions == null && !devConfirmed
            Step(1, "Developer options", when { devDone -> "On"; devUnknown -> "Can't check"; else -> "Off" },
                if (devUnknown && current != 0) StepState.UNKNOWN else state(0, devDone)) {
                StepText("A hidden settings menu that the next steps need.")
                // Android 17 may hide it from apps: then only Wireless debugging being on shows it's on.
                if (s.developerOptions == null) StepText("This phone doesn't let apps check whether it's on. " +
                    "If \"Developer options\" is already in Settings, tap \"It's on\".")
                StepText(developerOptionsHint())
                AboutPhoneSketch(Modifier.padding(vertical = 4.dp))
                StepText(developerOptionsElsewhere(), small = true)
                StepText(developerOptionsDone())
                if (visitedAbout && s.developerOptions == false) StepText("Not on yet. Keep tapping until a message confirms it.")
                StepButton("Open About phone") { visitedAbout = true; openAboutPhone(context) }
                if (s.developerOptions == null) TextButton(onClick = { devConfirmed = true }) { Text("It's on") }
            }
            StepDivider()
            Step(2, "Wi-Fi", if (wifiDone) "Connected" else "Not connected", state(1, wifiDone)) {
                StepText("Android only allows Wireless debugging on Wi-Fi. Any network works, even without internet.")
                StepText("This app connects to it from inside your phone, and other devices can't use it without " +
                    "your pairing code. Changing Wi-Fi switches it off, so a network you stay on, like home, is best.",
                    small = true)
                StepButton("Choose Wi-Fi") { openWifi(context) }
            }
            StepDivider()
            val wdUnknown = s.wirelessDebugging == null && !wdConfirmed
            Step(3, "Wireless debugging", when { wdDone -> "On"; wdUnknown -> "Can't check"; else -> "Off" },
                if (wdUnknown && current != 2) StepState.UNKNOWN else state(2, wdDone)) {
                StepText("Turn on the \"Wireless debugging\" switch. If asked, tick \"Always allow on this " +
                    "network\" and tap \"Allow\".")
                DeveloperOptionsSketch(Modifier.padding(vertical = 4.dp))
                StepText(wirelessDebuggingLocation(), small = true)
                StepButton("Open Wireless debugging") { openWirelessDebugging(context) }
                if (s.wirelessDebugging == null) TextButton(onClick = { wdConfirmed = true }) { Text("It's on") }
                // Android 17, where only "It's on" in step 1 leads here: no Wireless debugging in Settings means
                // Developer options is off after all, so step 1 opens again, with its instructions.
                if (s.developerOptions == null) TextButton(onClick = { devConfirmed = false }) {
                    Text("Developer options isn't on")
                }
            }
            StepDivider()
            Step(4, if (pairingAgain) "Pair again" else "Pair this app", "Not paired", state(3, false)) {
                PairStep(ui, vm)
            }
        }
    }
}

@RequiresApi(30)
@Composable
private fun ColumnScope.PairStep(ui: LpaUi, vm: LpaViewModel) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val p = ui.pairing
    var mode by rememberSaveable { mutableStateOf(PairMode.IDLE) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    // Timed out, cancelled or done.
    LaunchedEffect(p.active) { if (!p.active && !p.pairing) mode = PairMode.IDLE }
    // Android 17: finding the pop-up needs "Nearby devices", asked for here, before Settings opens.
    val nearby = rememberNearbyAccess()

    val offNotice = "Notifications are off for this app, so enter the code here instead."
    val nearbyNotice = "Without \"Nearby devices\", this app can't find the pairing pop-up by itself, so enter its " +
        "code here, with the port it shows."
    fun manual(why: String?) { notice = why; mode = PairMode.MANUAL; vm.startPairing(notify = false) }
    fun notify() {
        notice = null; mode = PairMode.NOTIFY; vm.startPairing(notify = true)
        openWirelessDebugging(context, activity)
    }
    val permission = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted && notificationsOn(context)) notify() else manual(offNotice)
    }

    when (mode) {
        PairMode.IDLE -> {
            StepText("Android shows a 6-digit code in a pop-up. You type it into this app's notification, so the " +
                "pop-up stays open:")
            // Most phones open the list around the "Wireless debugging" row, not its own page: no app can open that.
            StepText("1. Tap \"Start pairing\". Settings opens. If you don't see \"Pair device with pairing code\", " +
                "find \"Wireless debugging\" in the list and tap its name (not its switch).")
            StepText("2. Tap \"Pair device with pairing code\".")
            PairCodeSketch(Modifier.padding(vertical = 4.dp))
            StepText("3. This app's notification drops down: tap \"Enter code\" on it and type the 6 digits. " +
                "Missed it? Swipe down from the top of the screen, and expand the notification if the button is hidden.")
            NotificationSketch(Modifier.padding(vertical = 4.dp))
            StepText("Stay in Settings until the notification says \"Paired\": switching to this app closes the " +
                "pop-up, and its code stops working. If you swipe the notification away, it comes back.", small = true)
            // The expired screen says this already, above the steps.
            if (ui.settings.pairingExpires && !ui.pairingExpired) StepText("Android can forget this pairing after 7 " +
                "days. To keep it, turn on \"Disable adb authorization timeout\" in Developer options.", small = true)
            p.error?.let { ErrorNote(it) }
            StepButton("Start pairing") {
                // Without "Nearby devices" the notification can't work: it has no room for the pop-up's port.
                nearby.ask(context) { found ->
                    when {
                        !found -> manual(nearbyNotice)
                        Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        !notificationsOn(context) -> manual(offNotice)
                        else -> notify()
                    }
                }
            }
            TextButton(onClick = { nearby.ask(context) { found -> manual(if (found) null else nearbyNotice) } }) {
                Text("Enter the code in this app instead")
            }
        }
        PairMode.NOTIFY -> {
            PairingStatus(p, found = "Pairing pop-up found. Enter its code in the notification.")
            StepText("Open \"Wireless debugging\" (tap its name, not the switch), then tap \"Pair device with pairing " +
                "code\". This app's notification drops down: tap \"Enter code\" on it. Missed it? Swipe down from the " +
                "top of the screen, and expand the notification if the button is hidden.")
            NotificationSketch(Modifier.padding(vertical = 4.dp))
            StepText("Coming back here closes the pop-up and its code. Open it again for a new one.", small = true)
            StepButton("Open Wireless debugging") { openWirelessDebugging(context, activity) }
            TextButton(onClick = { manual(null) }) { Text("Enter the code in this app instead") }
            TextButton(onClick = { vm.cancelPairing(); mode = PairMode.IDLE }) { Text("Cancel") }
        }
        PairMode.MANUAL -> {
            val searching = remember(mode, notice) { Pairing.canDiscover(context) }
            notice?.let {
                StepText(it)
                if (it == nearbyNotice) TextButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
                else TextButton(onClick = { openNotificationSettings(context) }) { Text("Open notification settings") }
            }
            StepText("Settings closes the pairing pop-up when you leave it, so put both apps on screen:")
            StepText("1. Open Recent apps. Tap this app's icon (or press and hold its preview), choose split " +
                "screen and pick Settings for the other half.")
            StepText("2. Tap \"Open Wireless debugging\" below. If you don't see \"Pair device with pairing code\", " +
                "find \"Wireless debugging\" in the list and tap its name (not its switch). Then tap \"Pair device with pairing code\".")
            StepText(if (searching) "3. Type the 6-digit code below." else "3. Type the 6-digit code and the port below.")
            PairingStatus(p, found = "Pairing pop-up found. Enter its code below.", searching = searching)
            CodeEntry(p, portFirst = !searching, onSubmit = vm::submitPairingCode)
            StepButton("Open Wireless debugging") { openWirelessDebugging(context, activity) }
            TextButton(onClick = { vm.cancelPairing(); mode = PairMode.IDLE }) { Text("Cancel") }
        }
    }
}

/** Live status of the pairing pop-up search, and of pairing itself. [searching] false: the app isn't allowed to
 *  look for the pop-up (Android 17, "Nearby devices"), so people type its port. */
@Composable
private fun PairingStatus(p: PairingFlow.State, found: String, searching: Boolean = true) {
    if (!searching && !p.pairing) { p.error?.let { ErrorNote(it) }; return }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (p.port != null && !p.pairing) Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null,
            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(when {
            p.pairing -> "Pairing…"
            p.port != null -> found
            else -> "Looking for the pairing pop-up…"
        }, style = MaterialTheme.typography.bodyMedium)
    }
    p.error?.let { ErrorNote(it) }
}

/** Six digits, shown in clear so they can be checked against the pop-up; pairs on the sixth digit.
 *  The port field only appears when the pop-up can't be found by itself; with [portFirst], from the start. */
@Composable
private fun CodeEntry(p: PairingFlow.State, portFirst: Boolean = false, onSubmit: (String, String) -> Unit) {
    var code by remember { mutableStateOf("") } // short-lived secret: never saved
    var port by rememberSaveable { mutableStateOf("") }
    var showPort by rememberSaveable { mutableStateOf(portFirst) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(p.port) { slow = false; if (p.port == null) { delay(15_000); slow = true } }
    // Without a port to pair with, the sixth digit can't start pairing yet.
    fun submit() { if (code.length == 6 && !p.pairing && (p.port != null || !portFirst || port.isNotEmpty())) onSubmit(code, port) }
    OutlinedTextField(value = code,
        onValueChange = { v ->
            val digits = v.filter(Char::isDigit).take(6)
            val complete = digits.length == 6 && code.length != 6
            code = digits
            if (complete) submit()
        },
        label = { Text("Pairing code") },
        supportingText = { Text("6 digits, shown in the pop-up") },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 22.sp, letterSpacing = 4.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        singleLine = true, enabled = !p.pairing, modifier = Modifier.fillMaxWidth())
    if (code.length == 6 && (p.error != null || portFirst) && !p.pairing) TextButton(onClick = ::submit) {
        Text(if (p.error != null) "Try again" else "Pair")
    }
    // One child of the step's spaced column, so its gap animates with the field instead of popping in.
    if (showPort || (p.port == null && (slow || p.error != null))) Column {
        if (!portFirst) TextButton(onClick = { showPort = !showPort }) { Text("Can't find the pop-up? Enter its port") }
        AnimatedVisibility(showPort) {
            OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Pairing port") },
                supportingText = { Text("The number after the colon under \"IP address & Port\" in the pop-up. " +
                    "For 192.168.1.5:37105, enter 37105.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** Collapsed by default: ports are for when automatic discovery fails. [forceOpen] opens it, e.g. once the app
 *  may not look for the port itself. */
@Composable
private fun PortEntry(label: String, button: String, help: String, portState: MutableState<String>,
                      forceOpen: Boolean = false, onConnect: (String) -> Unit) {
    var port by portState
    var open by rememberSaveable { mutableStateOf(port.isNotEmpty()) }
    LaunchedEffect(forceOpen) { if (forceOpen) open = true }
    // Centred like the rest of the reconnect screens, its callers.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = { open = !open }) { Text(label) }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") }, supportingText = { Text(help) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (port.isNotEmpty()) onConnect(port) }),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onConnect(port) }, enabled = port.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()) { Text(button) }
            }
        }
    }
}

// ---------------- Android 17: "Nearby devices", to find Wireless debugging's ports ----------------

/** Manifest.permission.ACCESS_LOCAL_NETWORK, Android 17+. Only asked for there: [Pairing.canDiscover] says when. */
private const val NEARBY_DEVICES = "android.permission.ACCESS_LOCAL_NETWORK"

/** Asks for "Nearby devices" when finding Wireless debugging's ports needs it ([Pairing.canDiscover]): a short
 *  explanation first, since Android's own question talks of finding nearby devices, then Android's question. */
@Stable
private class NearbyAccess {
    var explaining by mutableStateOf(false)
    /** Turned down: the app's settings page can still allow it. */
    var refused by mutableStateOf(false)
    var pending: ((Boolean) -> Unit)? = null

    /** Runs [then] with whether the app may look now: at once when it already may (or needn't ask). */
    fun ask(context: Context, then: (Boolean) -> Unit) {
        if (Pairing.canDiscover(context)) { refused = false; return then(true) }
        pending = then
        explaining = true
    }

    fun answered(granted: Boolean) {
        explaining = false
        refused = !granted
        pending?.invoke(granted)
        pending = null
    }
}

@Composable
private fun rememberNearbyAccess(): NearbyAccess {
    val access = remember { NearbyAccess() }
    val launcher = rememberLauncherForActivityResult(RequestPermission(), access::answered)
    if (access.explaining) AlertDialog(
        onDismissRequest = { access.answered(false) },
        icon = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
        title = { Text("Find Wireless debugging") },
        text = { Text("Wireless debugging changes its port number each time it turns on. This app finds it by " +
            "looking on your network for this phone's own Wireless debugging.\n\nAndroid asks before apps look, and " +
            "calls it \"Nearby devices\". This app doesn't look for, or connect to, any other device. Without it, " +
            "you type the port numbers in yourself.", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { access.explaining = false; launcher.launch(NEARBY_DEVICES) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { access.answered(false) }) { Text("Not now") } },
    )
    return access
}

/** After "Nearby devices" was turned down: what that means, and where to allow it after all. */
@Composable
private fun NearbyRefused(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Without \"Nearby devices\", enter Wireless debugging's port below. To allow it after all, open this " +
            "app's settings, then Permissions.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
    }
}

// ---------------- Android 10 and older: set up with a computer ----------------

/** Android 6 to 10 have no Wireless debugging. A computer can still turn on Android's debugging port ("adb tcpip"),
 *  which listens on this phone too until it restarts; the app connects to it over 127.0.0.1, and the phone asks
 *  "Allow USB debugging?" for this app once. [computerDone]: people said they did the computer step, which nothing
 *  on the phone can check. [port]: a port typed after a failed attempt; null where there is none (SIM settings). */
@Composable
private fun ComputerSetup(ui: LpaUi, vm: LpaViewModel, modifier: Modifier, computerDone: MutableState<Boolean>,
                          port: MutableState<String>?, again: Boolean, onBack: (() -> Unit)?,
                          forSimSettings: Boolean = false) {
    val context = LocalContext.current
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val s = ui.settings
    var visitedAbout by rememberSaveable { mutableStateOf(false) }
    val devDone = s.developerOptions == true
    val usbDone = s.usbDebugging == true
    val current = when { !devDone -> 0; !usbDone -> 1; !computerDone.value -> 2; else -> 3 }
    fun state(i: Int, done: Boolean) = when {
        done -> StepState.DONE
        i == current -> StepState.CURRENT
        else -> StepState.PENDING
    }
    val connect = { if (forSimSettings) vm.setUpSimSettings() else vm.connect() }

    Column(modifier.fillMaxSize().imePadding().clearFocusOnTap().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when {
                    forSimSettings -> "Mobile data and roaming"
                    again -> "Full setup"
                    else -> "Set up with a computer"
                },
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(when {
                    forSimSettings -> "Optional: your card already works without this. Android only lets apps " +
                        "change mobile data and roaming through its debugging tool, Shizuku or root. Set it up once, " +
                        "and after a switch the app sets the ones you tick, until your phone restarts."
                    again -> "Rechecks each step and connects this app again."
                    else -> "Your card only lets its maker's app manage it, or it isn't inserted. This phone runs " +
                        "Android ${Build.VERSION.RELEASE}, so this app reaches the card through Android's debugging " +
                        "tool. Turning that on needs a computer once, and again after each restart. About 5 minutes " +
                        "the first time."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!forSimSettings && !again) CardCheck(ui, vm)
        // Errors about connecting show by the Connect button, in the last step.
        if (!forSimSettings && current != 3) ui.error?.let { ErrorNote(it) }
        if (!forSimSettings) WhyCard(onLearnMore = { AboutActivity.open(context, ui) })
        if (ui.rootFound) RootOption(ui, vm)
        ShizukuOption(ui, vm)
        BankingNote(if (forSimSettings) "This app then can't set mobile data and roaming until you turn Developer " +
            "options and USB debugging back on." else "Card access stops until you turn Developer options and USB " +
            "debugging back on.")
        Card(colors = CardDefaults.cardColors(containerColor = cardColor()),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Step(1, "Developer options", if (devDone) "On" else "Off", state(0, devDone)) {
                StepText("A hidden settings menu that the next steps need.")
                StepText(developerOptionsHint())
                AboutPhoneSketch(Modifier.padding(vertical = 4.dp))
                StepText(developerOptionsElsewhere(), small = true)
                StepText(developerOptionsDone())
                if (visitedAbout) StepText("Not on yet. Keep tapping until a message confirms it.")
                StepButton("Open About phone") { visitedAbout = true; openAboutPhone(context) }
            }
            StepDivider()
            Step(2, "USB debugging", if (usbDone) "On" else "Off", state(1, usbDone)) {
                StepText("In \"Developer options\", turn on \"USB debugging\" and tap \"OK\".")
                StepText(developerOptionsLocation("USB debugging"), small = true)
                StepButton("Open Developer options") { openDeveloperOptions(context) }
            }
            StepDivider()
            Step(3, "On a computer", if (computerDone.value) "Done" else "", state(2, computerDone.value)) {
                StepText("Any Windows, Mac or Linux computer, and a USB cable that fits your phone.")
                StepText("1. On the computer, download Android's free \"SDK Platform-Tools\" from:")
                CommandBox(PLATFORM_TOOLS, copyLabel = "Copy address")
                StepText("2. Unzip it, and open a command window in the \"platform-tools\" folder. On Windows: click " +
                    "the folder's address bar, type cmd and press Enter. On a Mac: open Terminal, type cd and a " +
                    "space, drag the folder in, and press Return.", small = true)
                StepText("3. Connect your phone with the cable. If it asks \"Allow USB debugging?\", tick \"Always " +
                    "allow from this computer\" and tap \"${allowButton()}\".")
                StepText("4. Type this and press Enter:")
                CommandBox(TCP_COMMAND, copyLabel = "Copy command")
                StepText("It answers \"restarting in TCP mode port: ${AdbApduBackend.TCP_PORT}\". \"Command not " +
                    "found\"? Type ./$TCP_COMMAND instead. \"No devices\" or \"unauthorized\"? Unlock your phone, " +
                    "plug the cable in again and allow it.", small = true)
                StepText("LineageOS and some others have \"ADB over network\" in Developer options instead: turn it " +
                    "on, and skip the computer.", small = true)
                StepButton("Done") { computerDone.value = true }
                TextButton(onClick = { shareComputerSteps(context) }) { Text("Send these steps to your computer") }
            }
            StepDivider()
            Step(4, "Connect this app", "Not connected", state(3, false)) {
                StepText("Unplug the cable and tap \"Connect\". Your phone asks \"Allow USB debugging?\" again, this " +
                    "time for this app.")
                ui.adbKey?.let {
                    StepText("Check that it shows this fingerprint, tick \"Always allow from this computer\" and tap " +
                        "\"${allowButton()}\":")
                    KeyFingerprint(it)
                } ?: StepText("Tick \"Always allow from this computer\" and tap \"${allowButton()}\".")
                when {
                    // Nothing answered: the computer step didn't take (or the phone restarted since).
                    ui.portClosed -> {
                        ErrorNote("Nothing answered on this phone yet. Check that the computer said \"restarting " +
                            "in TCP mode port: ${AdbApduBackend.TCP_PORT}\", then tap \"Connect\" again.")
                        ui.error?.takeIf { it != ui.explainedError }?.let { ErrorNote(it) }
                        TextButton(onClick = { computerDone.value = false }) { Text("Back to the computer step") }
                    }
                    else -> ui.error?.let { ErrorNote(it) }
                }
                StepButton("Connect") { connect() }
                StepText("After your phone restarts, it needs the computer again: steps 3 and 4. While this is on, " +
                    "other devices on your network can ask to connect too, so only tap \"${allowButton()}\" when this " +
                    "app asks.", small = true)
                if (port != null && (ui.error != null || ui.portClosed)) PortEntry(label = "Used another port?",
                    button = "Connect with this port", portState = port,
                    help = "The number you typed after \"adb tcpip\".") { vm.connect(it) }
            }
        }
    }
}

/** Android 10 and older, set up before, but card access isn't running: after a restart the computer is needed
 *  again; otherwise reconnecting just works. */
@Composable
private fun ComputerReconnect(ui: LpaUi, vm: LpaViewModel, modifier: Modifier, port: MutableState<String>,
                              onFullSetup: () -> Unit) {
    val context = LocalContext.current
    val s = ui.settings
    Column(modifier.fillMaxSize().imePadding().clearFocusOnTap().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Icon(painterResource(R.drawable.ic_sim), contentDescription = null, Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        val (title, body) = when {
            s.developerOptions == false -> "Turn Developer options back on" to
                "Card access needs \"Developer options\".\n\n${developerOptionsHint()} " +
                "${developerOptionsElsewhere()} ${developerOptionsDone()}"
            // Stopped stays stopped: USB debugging coming back on doesn't reconnect by itself.
            ui.stopped && s.usbDebugging == false -> "Card access stopped" to
                "Turn on \"USB debugging\" in Developer options when you want to use your card again, then tap " +
                "\"Reconnect\"."
            s.usbDebugging == false -> "Turn USB debugging back on" to
                "Card access needs \"USB debugging\" in Developer options. Turn it on and this app reconnects by " +
                "itself, unless your phone restarted since: then it needs your computer again."
            ui.portClosed -> "Connect your computer again" to
                "Nothing answered on this phone. That happens after it restarts, which turns off what this app " +
                "connects to. On your computer, connect the phone with a USB cable and, in the \"platform-tools\" " +
                "folder, type the command below again. Then unplug it and tap \"Reconnect\"."
            ui.allowAgain -> "Allow this app again" to
                "Your phone doesn't allow this app's debugging access at the moment. Tap \"Reconnect\": when your " +
                "phone asks \"Allow USB debugging?\", tick \"Always allow from this computer\" and tap " +
                "\"${allowButton()}\"." +
                if (s.pairingExpires) "\n\nAndroid ${Build.VERSION.RELEASE} forgets this after 7 days without " +
                    "connecting." else ""
            ui.stopped -> "Card access stopped" to
                "Reconnect any time until your phone restarts. After a restart, it needs your computer again."
            else -> "Ready to reconnect" to "Tap \"Reconnect\" to open your card. After your phone restarts, this " +
                "needs your computer again."
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val canConnect = s.developerOptions != false && s.usbDebugging != false
        when {
            s.developerOptions == false -> AboutPhoneSketch(Modifier.padding(top = 24.dp))
            canConnect && ui.portClosed -> {
                Spacer(Modifier.height(16.dp))
                CommandBox(TCP_COMMAND, copyLabel = "Copy command")
            }
            canConnect && ui.allowAgain -> ui.adbKey?.let {
                Spacer(Modifier.height(16.dp))
                Text("The fingerprint it shows should be:", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                KeyFingerprint(it, Modifier.padding(top = 8.dp))
            }
        }
        // The title already says what nothing answering, or the phone not allowing the app, means.
        ui.error?.takeIf { it != ui.explainedError }?.let { Spacer(Modifier.height(16.dp)); ErrorNote(it) }
        Spacer(Modifier.height(24.dp))
        val wide = Modifier.fillMaxWidth()
        when {
            s.developerOptions == false -> Button(onClick = { openAboutPhone(context) }, wide) { Text("Open About phone") }
            !canConnect -> Button(onClick = { openDeveloperOptions(context) }, wide) { Text("Open Developer options") }
            else -> Button(onClick = { vm.connect() }, wide) { Text("Reconnect") }
        }
        if (canConnect && ui.portClosed) TextButton(onClick = { shareComputerSteps(context) }) {
            Text("Send the steps to your computer")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { AboutActivity.open(context, ui) }) { Text("Why is this needed?") }
        TextButton(onClick = onFullSetup) { Text("Full setup") }
        if (ui.rootFound) TextButton(onClick = vm::useRoot) { Text("Use root instead") }
        ShizukuButton(ui, vm)
        // Only after a failed attempt: someone may have typed another port after "adb tcpip".
        if (canConnect && (ui.error != null || ui.portClosed)) {
            PortEntry(label = "Used another port?", button = "Connect with this port", portState = port,
                help = "The number you typed after \"adb tcpip\".") { vm.connect(it) }
        }
    }
}

/** Where setup has people download adb, and what they type: see [ComputerSetup]. */
private const val PLATFORM_TOOLS = "developer.android.com/tools/releases/platform-tools"
private val TCP_COMMAND = "adb tcpip ${AdbApduBackend.TCP_PORT}"

/** A command or address to type on the computer, in a fixed-width font, with a button that copies it (to send on,
 *  say, or for a computer that shares this phone's clipboard). */
@Composable
private fun CommandBox(text: String, copyLabel: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by rememberCopied()
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            IconButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyLabel, text))) }
                copied = true
            }) {
                Icon(painterResource(if (copied) R.drawable.ic_check_circle else R.drawable.ic_copy),
                    contentDescription = if (copied) "Copied" else copyLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** This app's key fingerprint as the phone's "Allow USB debugging?" question shows it, in two lines of 8 pairs. */
@Composable
private fun KeyFingerprint(key: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Fingerprint " + key.replace(":", " ").toList().joinToString(" ")
        }) {
        Text(key.split(':').chunked(8).joinToString("\n") { it.joinToString(":") },
            style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

/** The computer step as text, to send to the computer itself: by email or chat, say, where the address and
 *  command can be copied. */
private fun shareComputerSteps(context: Context) {
    val steps = "Card access for $APP_NAME, from a computer:\n\n" +
        "1. Download Android's SDK Platform-Tools: https://$PLATFORM_TOOLS\n" +
        "2. Unzip it, and open a command window in the platform-tools folder.\n" +
        "3. Connect the phone with a USB cable. If it asks \"Allow USB debugging?\", tick \"Always allow from this " +
        "computer\" and tap \"${allowButton()}\".\n" +
        "4. Type: $TCP_COMMAND\n   (Mac or Linux: ./$TCP_COMMAND)\n" +
        "5. Unplug the phone and tap \"Connect\" in the app.\n\n" +
        "After the phone restarts, repeat steps 3 to 5."
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, steps)
        .putExtra(Intent.EXTRA_SUBJECT, "Card access setup")
    launch(context, listOf(Intent.createChooser(send, "Send the steps")))
}

// ---------------- building blocks ----------------

@Composable
private fun Step(number: Int, title: String, status: String, state: StepState, content: @Composable ColumnScope.() -> Unit) {
    val current = state == StepState.CURRENT
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
            StepMarker(number, state)
            Spacer(Modifier.width(16.dp))
            // Read out with the status: a live change speaks that one text, so it has to name the step too.
            Text(title, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(end = 8.dp).clearAndSetSemantics {},
                color = if (state == StepState.PENDING) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface)
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = stepSpeech(title, status)
                    liveRegion = LiveRegionMode.Polite
                })
        }
        AnimatedVisibility(current) {
            Column(Modifier.padding(start = 44.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content)
        }
    }
}

/** How a step's title and status are read out; some steps have no status. */
internal fun stepSpeech(title: String, status: String) = if (status.isEmpty()) title else "$title: $status"

@Composable
private fun StepMarker(number: Int, state: StepState) {
    val c = MaterialTheme.colorScheme
    when (state) {
        StepState.DONE -> Icon(painterResource(R.drawable.ic_check_circle), contentDescription = "Done",
            Modifier.size(28.dp), tint = c.primary)
        StepState.UNKNOWN -> Icon(painterResource(R.drawable.ic_info), contentDescription = "Can't check",
            Modifier.size(28.dp), tint = c.onSurfaceVariant)
        else -> Box(Modifier.size(28.dp).then(
                if (state == StepState.CURRENT) Modifier.background(c.primary, CircleShape)
                else Modifier.border(1.5.dp, c.outline, CircleShape)),
            contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge,
                color = if (state == StepState.CURRENT) c.onPrimary else c.onSurfaceVariant)
        }
    }
}

@Composable private fun StepDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/** First-time setup asks a lot, and a card that isn't in yet (or that lets this app in) needs none of it. Never more
 *  than a hint, with the steps right below it: a locked eSIM card and an ordinary SIM look the same to the app. */
@Composable
private fun CardCheck(ui: LpaUi, vm: LpaViewModel) {
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StepText(if (ui.noSim) "Your phone doesn't see a SIM card. Put your eSIM card in, then tap \"Check again\". " +
            "Already in? Carry on below." else "Put your eSIM card in first. If you just did, tap \"Check again\": it " +
            "may not need this setup.")
        OutlinedButton(onClick = vm::checkAgain) { Text("Check again") }
    }
}

/** A bank's app refusing to open is nothing people would connect to this app. Above the steps, not in them: a step's
 *  text only shows while it's the current one, and step 1 is skipped when Developer options is on already. [after]:
 *  what turning it off costs here. */
@Composable
private fun BankingNote(after: String) {
    Text("Some banking apps won't open while Developer options or debugging is on. If one stops opening, turn " +
        "Developer options off. $after", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StepText(text: String, small: Boolean = false) {
    Text(text, style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) { Text(text) }
}

@Composable
fun ErrorNote(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** The short, honest answer to "why does an eSIM app want debugging access?", on the setup screens; the About page
 *  ([onLearnMore]) has the rest, root included. Android 10 and older have no Wireless debugging: there it's USB
 *  debugging, turned on for this app from a computer. */
@Composable
fun WhyCard(onLearnMore: () -> Unit, modifier: Modifier = Modifier) {
    val legacy = Build.VERSION.SDK_INT < 30
    OutlinedCard(modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = cardColor())) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_info), contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(if (legacy) "Why USB debugging?" else "Why Wireless debugging?", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(8.dp))
            Text("Most eSIM cards only let their maker's app manage them. Android's built-in debugging tool is allowed " +
                "to talk to any SIM card, so this app uses it " + (if (legacy) "" else "through Wireless debugging ") +
                "to get past that lock. It only connects to this phone itself.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onLearnMore, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Learn more") }
        }
    }
}

// ---------------- help text and Settings shortcuts ----------------

/** A Settings path that only wraps after a "›", never inside a step's name. */
internal fun path(vararg steps: String) = steps.joinToString("\u00A0› ") { it.replace(' ', '\u00A0') }

/** The app's name as Settings lists it under paired devices, kept on one line. */
const val APP_NAME = "eSIM\u00A0On\u2011SIM"

// Stock Android names, for every phone: makers move and rename these, so each hint ends with a way to
// find the setting anyway.
private fun developerOptionsHint() = "Open \"About phone\" and tap \"Build number\" 7\u00A0times."

private fun developerOptionsElsewhere() = "On some phones it's under \"Software information\", or called " +
    "\"OS version\". Search Settings for \"Build number\" if you can't find it."

private fun developerOptionsDone() = "Enter your screen lock if asked. A message confirms when it's on."

private fun wirelessDebuggingLocation() = "It's in ${path("Settings", "System", "Developer options")}, " +
    "or search Settings for \"Wireless debugging\"."

/** Android 7.1 and older list Developer options in Settings itself; 8 and newer under System. */
private fun developerOptionsLocation(setting: String) = (if (Build.VERSION.SDK_INT >= 26)
    "It's in ${path("Settings", "System", "Developer options")}" else "It's near the end of Settings, in " +
    "\"Developer options\"") + ", or search Settings for \"$setting\"."

/** The button on this phone's "Allow USB debugging?" question that allows a computer, or this app. */
internal fun allowButton() = AdbIdentity.allowButton()

@RequiresApi(30)
private fun notificationsOn(context: Context): Boolean {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return false
    // The pairing channel can be switched off on its own, too.
    return nm.areNotificationsEnabled() && nm.getNotificationChannel("pairing")?.importance != NotificationManager.IMPORTANCE_NONE
}

private fun launch(context: Context, intents: List<Intent>) {
    for (intent in intents) {
        try { context.startActivity(intent); return }
        catch (_: ActivityNotFoundException) { }
        catch (_: SecurityException) { }
    }
}

/** The subscription Android runs in SIM slot [slot] (from 0); -1 when none, or on Android 9 and older, where
 *  only apps with phone permission can ask. Needs no permission. */
fun simSubscription(context: Context, slot: Int): Int {
    if (slot < 0 || Build.VERSION.SDK_INT < 29) return -1
    return runCatching {
        if (Build.VERSION.SDK_INT >= 34) SubscriptionManager.getSubscriptionId(slot)
        else @Suppress("DEPRECATION") context.getSystemService(SubscriptionManager::class.java)
            ?.getSubscriptionIds(slot)?.firstOrNull() ?: -1
    }.getOrDefault(-1).takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID } ?: -1
}

/** No SIM in any slot, as far as Android says. Needs no permission. */
fun noSimInPhone(context: Context): Boolean = runCatching {
    val t = context.getSystemService(TelephonyManager::class.java) ?: return false
    val slots = if (Build.VERSION.SDK_INT >= 30) t.activeModemCount else @Suppress("DEPRECATION") t.phoneCount
    when {
        slots <= 0 -> false
        Build.VERSION.SDK_INT >= 26 -> (0 until slots).all { t.getSimState(it) == TelephonyManager.SIM_STATE_ABSENT }
        else -> slots == 1 && t.simState == TelephonyManager.SIM_STATE_ABSENT // no per-slot call before 26
    }
}.getOrDefault(false)

/** The new eSIM's own settings page, with its mobile data and roaming switches, when Android already runs it in
 *  SIM slot [slot] (from 0) and it isn't still the one from before the switch ([notSub]). Otherwise the list of
 *  SIMs: a page that falls back to another SIM could get roaming turned on there. */
@SuppressLint("InlinedApi") // EXTRA_SUB_ID: a subscription is only known on Android 10+ (simSubscription)
fun openSimSettings(context: Context, slot: Int?, notSub: Int) {
    val sub = slot?.let { simSubscription(context, it) } ?: -1
    val pages = ArrayList<Intent>()
    if (sub != -1 && sub != notSub) pages += Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).putExtra(Settings.EXTRA_SUB_ID, sub)
    if (Build.VERSION.SDK_INT >= 31) pages += Intent(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS)
    pages += Intent(Settings.ACTION_WIRELESS_SETTINGS)
    launch(context, pages)
}

private fun openAboutPhone(context: Context) = launch(context,
    listOf(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS)))

private fun openShizuku(context: Context) {
    context.packageManager.getLaunchIntentForPackage(ShizukuClient.MANAGER)?.let { launch(context, listOf(it)) }
}

/** Developer options, where USB debugging is; Settings itself where a phone has no shortcut to it. */
internal fun openDeveloperOptions(context: Context) = launch(context,
    listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS)))

/** This app's page in Settings, where its permissions are. */
private fun openAppSettings(context: Context) = launch(context, listOf(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))))

@RequiresApi(30)
private fun openWifi(context: Context) = launch(context,
    listOf(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))) // the panel opens over the app

@RequiresApi(30)
private fun openNotificationSettings(context: Context) = launch(context, listOf(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)))

/** Opens Wireless debugging itself where possible. In split screen, Settings opens in the other half. */
@RequiresApi(30)
fun openWirelessDebugging(context: Context, activity: Activity? = null) {
    val adjacent = activity?.isInMultiWindowMode == true
    fun Intent.side() = apply { if (adjacent) addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK) }
    // There's no public action for it. Some Settings apps export the page that hosts the fragment; elsewhere
    // Developer options opens scrolled to (and highlighting) the Wireless debugging row.
    val key = "toggle_adb_wireless"
    launch(activity ?: context, listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.SubSettings")
            .putExtra(":settings:show_fragment", "com.android.settings.development.WirelessDebuggingFragment")
            .putExtra(":settings:show_fragment_title", settingsTitle(context)).side(),
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", key)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) }).side(),
        Intent(Settings.ACTION_SETTINGS),
    ))
}

/** That page's own title, in the phone's language, as Settings itself would show it. */
@SuppressLint("DiscouragedApi") // another app's strings can only be found by name
private fun settingsTitle(context: Context): String = runCatching {
    val res = context.packageManager.getResourcesForApplication("com.android.settings")
    res.getString(res.getIdentifier("enable_adb_wireless", "string", "com.android.settings"))
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "Wireless debugging"
