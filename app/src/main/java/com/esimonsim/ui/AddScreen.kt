package com.esimonsim.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esimonsim.R
import com.esimonsim.lpa.ActivationCode
import com.esimonsim.lpa.EuiccProfile
import com.esimonsim.lpa.Lpa
import com.esimonsim.lpa.nameKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** What has been typed into the Add eSIM form. It lives above the screen (in [LpaScreen]), so it
 *  survives a failed install and a dropped connection. */
class AddDraft {
    var code by mutableStateOf("")
    var smdp by mutableStateOf("")
    var cc by mutableStateOf("")
    fun clear() { code = ""; smdp = ""; cc = "" }

    companion object {
        val Saver = listSaver<AddDraft, String>({ listOf(it.code, it.smdp, it.cc) },
            { AddDraft().apply { code = it[0]; smdp = it[1]; cc = it[2] } })
    }
}

/** Add eSIM, as a full screen: the code form, then download progress, then naming the new eSIM. */
@Composable
fun AddScreen(ui: LpaUi, vm: LpaViewModel, draft: AddDraft, pad: PaddingValues) {
    val installed = ui.installed
    val busy = ui.busy
    Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
        when {
            installed != null -> InstallDone(installed, ui, vm)
            busy?.step != null -> InstallProgress(busy, ui.profiles) // other work on this screen shows the busy dialog
            else -> InstallForm(ui, vm, draft.code, { draft.code = it }, draft.smdp, { draft.smdp = it },
                draft.cc, { draft.cc = it })
        }
    }
}

private suspend fun Clipboard.readText(context: Context): String? =
    getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()

@Composable
private fun InstallForm(ui: LpaUi, vm: LpaViewModel, code: String, setCode: (String) -> Unit,
                        smdp: String, setSmdp: (String) -> Unit, cc: String, setCc: (String) -> Unit) {
    // From what's typed: this form is gone during an install, and a code kept from a failed one mustn't go out unseen:
    // Install would send it, field hidden or not.
    var showCc by rememberSaveable { mutableStateOf(cc.isNotEmpty()) }
    // Stays open from here on, whatever comes next.
    LaunchedEffect(ui.ccNeeded) { if (ui.ccNeeded != null) showCc = true }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    var scanned by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { QrScan.prefetch(context) } }

    // A scanned QR code fills in the field; installing still waits for the user's tap.
    fun useQr(text: String?) {
        val found = text?.let(ActivationCode::parse)
        scanError = when {
            text == null -> "No QR code found in that image. Try a sharper screenshot, or enter the code."
            found == null -> "That QR code isn't an eSIM activation code."
            else -> null
        }
        scanned = found?.let { "Code added. Provider: ${it.smdp}." }
        if (found != null) { setCode(found.toString()); setSmdp("") }
    }
    val scan = rememberQrScanner(::useQr) { scanError = it }
    val pickImage = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            useQr(withContext(Dispatchers.Default) { runCatching { QrImage.decode(context, uri) }.getOrNull() })
        }
    }

    val input = code.trim()
    val full = ActivationCode.find(input)
    // Just the code on its own: providers then list the SM-DP+ address next to it.
    val bare = full == null && isBareCode(input)
    val ac = full ?: if (bare) ActivationCode.of(smdp, input) else null
    val needsCc = needsConfirmationCode(ac, ui.ccNeeded)
    val prior = remember(ac, ui.profiles) { ac?.let(vm::priorInstall) }
    val onCard = prior?.second
    val canInstall = ui.eid != null && ac != null && (!needsCc || cc.isNotBlank()) && onCard == null

    Column(Modifier.fillMaxSize().semantics { paneTitle = "Add eSIM" }) {
        Column(Modifier.weight(1f).clearFocusOnTap().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Back from a dropped connection, the card may not read yet. Installing needs it, and nothing else on this
            // screen says so or tries again, while closing the form would lose the code.
            if (ui.eid == null) {
                if (ui.loading) Text("Reading your card…", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                else {
                    ErrorNote(ui.error?.takeIf { ui.errorTitle == null } ?: "Couldn't read your eSIM card.")
                    OutlinedButton(onClick = vm::loadCard, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
            }
            // Says a scan worked out loud too, so a text that's always here (a new one wouldn't be announced), and not
            // the field's own line, which also changes as people type.
            Text(scanned ?: "Scan the QR code from your eSIM provider, or enter the activation code printed under it.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { if (scanned != null) liveRegion = LiveRegionMode.Polite })
            Button(onClick = { scanError = null; scan() },
                modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Scan QR code")
            }
            OutlinedButton(onClick = { scanError = null; pickImage.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_image), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Use a screenshot")
            }
            scanError?.let { ErrorNote(it) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Text("or enter manually", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }
            OutlinedTextField(
                value = code, onValueChange = { setCode(it); scanError = null; scanned = null },
                label = { Text("Activation code") },
                placeholder = { Text("LPA:1\$…") },
                trailingIcon = {
                    // Copied text can be a whole message or link: the code in it, if there is one.
                    TextButton(onClick = { scope.launch { clipboard.readText(context)?.let { t ->
                        setCode(ActivationCode.find(t)?.toString() ?: t.trim()); scanned = null } } }) {
                        Text("Paste")
                    }
                },
                supportingText = {
                    Text(when {
                        full != null -> "Provider: ${full.smdp}"
                        bare -> "Now enter the SM-DP+ address below."
                        input.isEmpty() -> "Starts with LPA:1\$, or enter just the code."
                        else -> "This doesn't look like an activation code."
                    })
                },
                isError = input.isNotEmpty() && full == null && !bare,
                textStyle = FieldText,
                // Shown, but not for the keyboard to learn: whoever has this code can install the eSIM.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.PasswordVisible, imeAction = ImeAction.Next),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (bare) OutlinedTextField(
                value = smdp, onValueChange = setSmdp,
                label = { Text("SM-DP+ address") },
                placeholder = { Text("smdp.example.com") },
                supportingText = { Text("Also called the server address. Your provider lists it with the code.") },
                isError = smdp.isNotBlank() && ac == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (needsCc || showCc) OutlinedTextField(
                value = cc, onValueChange = setCc,
                label = { Text("Confirmation code") },
                supportingText = { Text(if (needsCc) "Required for this eSIM." else "Only if your provider gave you one.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.PasswordVisible,
                    imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            ) else TextButton(onClick = { showCc = true }) { Text("I have a confirmation code") }
            prior?.let { (install, existing) -> DuplicateNote(install, existing) { existing?.let(vm::showDuplicate) } }
            ui.eid?.let { EidCard(it, "Some providers ask for it when you buy an eSIM.") }
            WaitingEsims(ui.waiting, enabled = ui.eid != null, onCheck = vm::checkWaiting) {
                vm.download(it.code, cc.trim().ifEmpty { null })
            }
            Text(installNote(ui.profiles), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(tonalElevation = 0.dp, color = MaterialTheme.colorScheme.surface) {
            Button(onClick = { ac?.let { vm.download(it, cc.trim().ifEmpty { null }, force = prior != null) } },
                enabled = canInstall, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(if (prior != null && onCard == null) "Install anyway" else "Install")
            }
        }
    }
}

/** Whether the form needs a confirmation code before it can install [ac]: the code says so, or the provider did when
 *  it was tried ([asked]). Another code on the form doesn't inherit that. With none, [asked] was an eSIM from the
 *  waiting list, which installs with what's in the field too. */
internal fun needsConfirmationCode(ac: ActivationCode?, asked: ActivationCode?) =
    ac?.ccRequired == true || (asked != null && (ac == null || ac == asked))

/** The card can be empty, or have nothing on. And setup said any Wi-Fi will do, "even without internet": not here. */
internal fun installNote(profiles: List<EuiccProfile>) = if (profiles.any { it.enabled })
    "Your current eSIM keeps working while the new one installs." else "You need internet for this. Wi-Fi is fine."

/** eSIMs a provider sent straight to this card's EID, no QR code involved; found with [onCheck]. */
@Composable
private fun WaitingEsims(waiting: List<Lpa.WaitingEsim>?, enabled: Boolean, onCheck: () -> Unit,
                         onInstall: (Lpa.WaitingEsim) -> Unit) {
    if (waiting.isNullOrEmpty()) {
        OutlinedButton(onClick = onCheck, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_cloud_download), contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Check for eSIMs sent to this card (SM-DS)")
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Only for eSIMs a provider really sent: the card's default provider is just listed in case it has one.
        if (waiting.any { !it.fromDefault })
            Text("Sent to this card", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        waiting.forEach { w ->
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = cardColor())) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(w.smdp, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (w.fromDefault) "Your card's default provider. It may have an eSIM for this card."
                            else "Waiting at this provider", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onInstall(w) }, enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "Install from ${w.smdp}" }) { Text("Install") }
                }
            }
        }
        OutlinedButton(onClick = onCheck, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Check again")
        }
    }
}

/** This code was installed by this app before: still on the card, or used once already. */
@Composable
private fun DuplicateNote(install: com.esimonsim.data.ProfileStore.Install, onCard: EuiccProfile?, onShow: () -> Unit) {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(install.at))
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (onCard != null) "Already on your card" else "Code used before",
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (onCard != null) "You installed this eSIM on $date. It's on your card as \"${onCard.displayName}\"."
                        else "You installed this code on $date (${install.name}). Most codes work only once, so the " +
                            "provider will probably refuse it.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (onCard != null) TextButton(onClick = onShow, modifier = Modifier.align(Alignment.End)) { Text("Show it") }
        }
    }
}

/** The seven download steps, told as four. */
private val Stages = listOf(
    "Contacting your eSIM provider", "Checking your card with the provider", "Downloading the eSIM", "Installing on the card")

private fun stageOf(step: Lpa.Step?) = when (step) {
    null, Lpa.Step.READ_CARD, Lpa.Step.CONNECT -> 0
    Lpa.Step.VERIFY_SERVER, Lpa.Step.VERIFY_CARD -> 1
    Lpa.Step.PREPARE, Lpa.Step.DOWNLOAD -> 2
    Lpa.Step.INSTALL -> 3
}

@Composable
private fun InstallProgress(busy: Busy, profiles: List<EuiccProfile>) {
    BackHandler {} // leaving now could cut the card off mid-install
    val view = LocalView.current
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val stage = stageOf(busy.step)
    // 16dp at the sides like the form before it, so nothing jumps when Install is tapped.
    Column(Modifier.fillMaxSize().semantics { paneTitle = "Installing your eSIM" }.verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Installing your eSIM", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        val p by animateFloatAsState(busy.progress ?: 0f, label = "progress")
        LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
        Stages.forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        i < stage -> Icon(painterResource(R.drawable.ic_check_circle), contentDescription = "Done",
                            tint = MaterialTheme.colorScheme.primary)
                        i == stage -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else -> {}
                    }
                }
                Spacer(Modifier.width(16.dp))
                val pct = if (i == 3 && i == stage && busy.progress != null)
                    " (${((busy.progress - 0.3f) / 0.7f * 100).toInt().coerceIn(0, 100)}%)" else ""
                Text(label + pct, style = MaterialTheme.typography.bodyLarge,
                    color = if (i > stage) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clearAndSetSemantics {
                        // The stage moving on is news; each percent would queue up behind the last for minutes. It
                        // stays on screen, and on the progress bar.
                        contentDescription = label
                        if (i == stage) liveRegion = LiveRegionMode.Polite
                    })
            }
        }
        Text("Keep the app open until this finishes. ${installNote(profiles)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Right after an install: the best moment to give the eSIM a recognisable name. */
@Composable
private fun InstallDone(p: EuiccProfile, ui: LpaUi, vm: LpaViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var setup by rememberSaveable { mutableStateOf(ui.switchSetup) } // kept with the name through rotation
    val active = ui.profiles.firstOrNull { it.enabled && it.iccid != p.iccid }
    val tooLong = name.trim().toByteArray(Charsets.UTF_8).size > Lpa.MAX_NICKNAME_BYTES
    val taken = ui.profiles.firstOrNull { it.iccid != p.iccid && nameKey(it.displayName) == nameKey(name.ifBlank { p.displayName }) }
    Column(Modifier.fillMaxSize().semantics { paneTitle = "eSIM added" }) {
        // The same 16dp sides as the buttons below it.
        Column(Modifier.weight(1f).clearFocusOnTap().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary)
            Text("eSIM added", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("${p.displayName} is now on your card.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            OutlinedTextField(value = name, onValueChange = { name = it },
                label = { Text("Name (optional)") }, placeholder = { Text(p.displayName) },
                supportingText = {
                    Text(when {
                        tooLong -> "Too long. Use a shorter name."
                        taken != null -> "Another eSIM on this card is already called \"${taken.displayName}\". " +
                            "A name helps tell them apart."
                        else -> "Give it a name you recognise, you can leave it empty if you want to show its default name. It's saved on the card."
                    })
                },
                isError = tooLong, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(switchNote(active), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Not only the card screen's banner: that one stays away once dismissed.
            if (ui.usbTip) Text("To switch to it later without Wi-Fi, turn on \"USB debugging\" first. See " +
                "${path("Details", "Card access")}.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.canSetUpSim) SwitchOptions(setup) { setup = it }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { vm.finishInstall(name, switch = true, setup) }, enabled = !tooLong, modifier = Modifier.fillMaxWidth()) {
                Text(if (active == null) "Turn it on now" else "Switch to it now")
            }
            TextButton(onClick = { vm.finishInstall(name, switch = false) }, enabled = !tooLong,
                modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        }
    }
}

/** Fallback for the duplicate check in [LpaViewModel.download]; the form normally warns inline first. */
@Composable
fun DuplicateDialog(d: DuplicateCode, vm: LpaViewModel) {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(d.install.at))
    val onCard = d.onCard
    AlertDialog(
        onDismissRequest = vm::dismissDuplicate,
        icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null) },
        title = { Text(if (onCard != null) "Already on your card" else "Code used before") },
        text = {
            Text(if (onCard != null) "You installed this eSIM on $date. It's on your card as \"${onCard.displayName}\"."
                else "You installed this code on $date (${d.install.name}). Most codes work only once, so the provider " +
                    "will probably refuse it.", Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            if (onCard != null) TextButton(onClick = { vm.showDuplicate(onCard) }) { Text("Show it") }
            else TextButton(onClick = { vm.download(d.code, d.confirmationCode, force = true) }) { Text("Try anyway") }
        },
        dismissButton = { TextButton(onClick = vm::dismissDuplicate) { Text("Cancel") } },
    )
}

/** Just the code on its own, as providers list it next to the SM-DP+ address. One word: a sentence or a web link with
 *  no code in it isn't one. */
internal fun isBareCode(input: String) = input.isNotEmpty() && input.none { it == '$' || it == '/' || it.isWhitespace() }
