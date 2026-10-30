package com.esimonsim.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.esimonsim.R
import com.esimonsim.apdu.AccessMethod
import com.esimonsim.apdu.ShizukuStatus
import com.esimonsim.data.ProfileStore
import com.esimonsim.lpa.EuiccProfile
import com.esimonsim.lpa.Lpa
import com.esimonsim.lpa.nameKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LpaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppTheme { LpaScreen(vm) } }
        // Not when reopened from Recents: that hands back the share or link that first started the task.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) receive(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receive(intent) }

    override fun onResume() { super.onResume(); vm.refreshConnection() }
    override fun onPause() { super.onPause(); vm.leftApp() }

    /** An activation code shared from another app, or opened from a link (lpa:, "Install on iPhone"), goes into
     *  the Add eSIM form. A shared image is read as a QR code. */
    private fun receive(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(vm::receive)
            Intent.ACTION_SEND -> {
                // Shared text can be styled (a CharSequence), and a screenshot can come with the code as text too.
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val image = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (image != null && intent.type?.startsWith("image/") == true) vm.receiveImage(image, text)
                else vm.receive(text.orEmpty())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LpaScreen(vm: LpaViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    ClearFocusWhenKeyboardHides()
    val snackbar = remember { SnackbarHostState() }
    val ready = ui.access == AccessStatus.READY
    // The install screen stays up through the download and its result, then hands back to the card.
    val installing = ready && (ui.adding || ui.installed != null)
    // A fresh bar state per screen, so a checklist scrolled down doesn't leave the card screen's bar tinted.
    val scroll = TopAppBarDefaults.pinnedScrollBehavior(key(ready, installing) { rememberTopAppBarState() })
    var cardDetails by rememberSaveable { mutableStateOf(false) }
    var stopAccess by rememberSaveable { mutableStateOf(false) }
    // Optional, with direct card access: allowing this app in Shizuku, or pairing Wireless debugging (on Android 10
    // and older, allowing this app's debugging access), just so the app can set mobile data and roaming.
    var simSetup by rememberSaveable { mutableStateOf(false) }
    val draft = rememberSaveable(saver = AddDraft.Saver) { AddDraft() }
    LaunchedEffect(ui.adding) { if (!ui.adding && !ui.keepDraft) draft.clear() }
    // Card-only sheets and dialogs don't come back by themselves after a reconnect.
    LaunchedEffect(ready) { if (!ready) { cardDetails = false; stopAccess = false; simSetup = false } }

    // Shown from a scope that outlives the message, so the next operation clearing it doesn't cut it short.
    val scope = rememberCoroutineScope()
    var undoHide by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current
    LaunchedEffect(ui.info) {
        ui.info?.let { msg ->
            val simSettings = ui.infoSimSettings
            // Only OMAPI's slot numbers are Android's SIM slots; the helper's are physical ones.
            val slot = ui.slot.takeIf { ui.method == AccessMethod.DIRECT }
            val before = ui.subBeforeSwitch
            vm.clearInfo()
            if (simSettings) {
                scope.launch {
                    if (snackbar.showSnackbar(msg, actionLabel = "SIM settings", withDismissAction = true,
                            duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) openSimSettings(context, slot, before)
                }
                return@LaunchedEffect
            }
            // A sentence of advice (what to change in Settings after a switch) needs more than 4 seconds.
            val long = msg.length > 60
            scope.launch { snackbar.showSnackbar(msg, withDismissAction = long,
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short) }
        }
    }
    // A code from another app fills in the Add eSIM form once the card has been read and is free: at once if
    // it already is, after the "eSIM added" screen if one is up.
    val free = ready && !ui.loading && ui.eid != null && ui.busy == null && ui.installed == null
    LaunchedEffect(ui.incoming, free) {
        val code = ui.incoming
        if (code == null || !free) return@LaunchedEffect
        vm.clearIncoming()
        draft.clear(); draft.code = code
        vm.openAdd()
    }
    BackHandler(enabled = installing && ui.busy == null) { if (ui.installed != null) vm.finishInstall("", false) else vm.closeAdd() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    // The phone's own SIM settings go by slot, so the card's is kept in sight. Styled like
                    // Material's app bar subtitle, which material3 1.4 keeps internal.
                    Column {
                        Text(if (installing) "Add eSIM" else "eSIM On-SIM")
                        if (ready && !installing && ui.eid != null) Text("Card in SIM ${ui.slot + 1}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    // Disabled, not hidden, while busy: removing it shifted the title sideways.
                    if (installing) IconButton(onClick = {
                        if (ui.installed != null) vm.finishInstall("", false) else vm.closeAdd()
                    }, enabled = ui.busy == null) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close") }
                    else if (ready && simSetup) IconButton(onClick = { vm.cancelPairing(); simSetup = false }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Close")
                    }
                },
                scrollBehavior = scroll,
                actions = {
                    val onAbout = { AboutActivity.open(context, ui) }
                    if (ready && !installing && !simSetup) CardActions(enabled = !ui.loading && ui.busy == null,
                        cardRead = ui.eid != null, onRefresh = vm::loadCard, onDetails = { cardDetails = true },
                        onStop = { stopAccess = true }, onAbout = onAbout)
                    // Setup and reconnecting: what the app is, before setting anything up.
                    else if (!ready) MoreMenu { close -> DropdownMenuItem(text = { Text("About") }, onClick = { close(); onAbout() }) }
                },
            )
        },
        floatingActionButton = {
            if (ready && !installing && !simSetup && ui.eid != null && ui.profiles.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = vm::openAdd,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("Add eSIM") },
                // The default container is a pale grey in this palette; the main action is ink.
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        when {
            // Panes, each on its own screen's root: the screens swap inside one window, and a new pane is what a screen
            // reader announces. Not on anything above that stays through the swap, and never nested.
            !ready -> SetupScreen(ui, vm, Modifier.padding(pad).consumeWindowInsets(pad).semantics {
                paneTitle = if (ui.access == AccessStatus.CONNECTING) "Connecting to your card" else "Card access"
            })
            simSetup -> SimSettingsSetup(ui, vm, Modifier.padding(pad).consumeWindowInsets(pad)
                .semantics { paneTitle = "Mobile data and roaming" }) { simSetup = false }
            installing -> AddScreen(ui, vm, draft, pad)
            else -> CardScreen(ui, vm, pad)
        }
    }

    // Sheets and dialogs, in stacking order: each opens above the ones before it.
    val details = ui.details?.let { id -> ui.profiles.firstOrNull { it.iccid == id } }
    if (ready && details != null) ProfileSheet(details, ui, vm, onHide = { p ->
        vm.closeDetails(); vm.setHidden(p, true)
        // Hiding several in a row: each Undo replaces the last one, shown or still queued, so the one on screen
        // is always for the eSIM just hidden.
        undoHide?.cancel()
        undoHide = scope.launch {
            if (snackbar.showSnackbar("${p.displayName} hidden", actionLabel = "Undo", duration = SnackbarDuration.Short) ==
                SnackbarResult.ActionPerformed) vm.setHidden(p, false)
        }
    })
    if (ready && cardDetails && ui.eid != null) CardDetailsSheet(ui, onShown = vm::loadCardInfo, onDismiss = { cardDetails = false },
        onStop = { cardDetails = false; stopAccess = true }, onSimSetup = { cardDetails = false; simSetup = true })
    if (ready && stopAccess) StopAccessDialog(ui, onDismiss = { stopAccess = false }, onStop = { stopAccess = false; vm.disconnect() })
    if (ready) ui.duplicate?.let { DuplicateDialog(it, vm) }
    val errorTitle = ui.errorTitle
    val error = ui.error
    if (errorTitle != null && error != null) ErrorDialog(errorTitle, error, vm::dismissError)
    // The install screen shows its own progress; other work on it (checking for eSIMs) gets the dialog.
    ui.busy?.let { if (!installing || it.step == null) BusyDialog(it, onCancel = vm::cancelBusy) }
}

@Composable
private fun CardActions(enabled: Boolean, cardRead: Boolean, onRefresh: () -> Unit, onDetails: () -> Unit,
                        onStop: () -> Unit, onAbout: () -> Unit) {
    IconButton(onClick = onRefresh, enabled = enabled) {
        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh")
    }
    MoreMenu { close ->
        // Also while the card is busy, unlike the others, but it's nothing until the card has been read.
        DropdownMenuItem(text = { Text("Details") }, enabled = cardRead, onClick = { close(); onDetails() })
        DropdownMenuItem(text = { Text("Stop card access") }, enabled = enabled, onClick = { close(); onStop() })
        DropdownMenuItem(text = { Text("About") }, onClick = { close(); onAbout() })
    }
}

/** The app bar's overflow menu; [items] get a function that closes it. */
@Composable
private fun MoreMenu(items: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardScreen(ui: LpaUi, vm: LpaViewModel, pad: PaddingValues) {
    if (ui.eid == null) {
        Box(Modifier.padding(pad)) {
            when {
                ui.loading -> Loading()
                ui.builtInChip != null -> BuiltInChip(onRetry = vm::loadCard, onMine = vm::ownCard)
                else -> LoadFailed(ui.error, vm::loadCard)
            }
        }
        return
    }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    // Made here, where Compose keeps one instance: made in the LazyColumn block below, it was new on every
    // pass, so each "Use" row recomposed whenever anything in the UI state changed.
    val onUse: (EuiccProfile) -> Unit = { confirm = Confirm(Action.SWITCH, it) }
    // Hidden eSIMs fold away below the others. The active one always shows: it's the one in use.
    val (active, others, hidden) = remember(ui.profiles, ui.hidden) {
        val (on, off) = ui.profiles.partition { it.enabled }
        val (tucked, shown) = off.partition { it.iccid in ui.hidden }
        Triple(on, shown, tucked)
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showHidden by rememberSaveable { mutableStateOf(false) }
    // Set together with showHidden, so a tap can wait for the unfolding to finish.
    val hiddenOpen = remember { MutableTransitionState(showHidden) }
    val toggleHidden: () -> Unit = {
        showHidden = !showHidden
        hiddenOpen.targetState = showHidden
        // At the end of a long list they unfold below the screen, behind Add eSIM: once open, scroll them up.
        if (showHidden) scope.launch {
            snapshotFlow { hiddenOpen.isIdle }.first { it }
            if (hiddenOpen.currentState) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
    // Folded again once none are left, so the next one hidden doesn't open the list by itself.
    LaunchedEffect(hidden.isEmpty()) { if (hidden.isEmpty()) { showHidden = false; hiddenOpen.targetState = false } }
    val sameName = remember(ui.profiles, ui.dismissedSameName) {
        ui.profiles.groupBy { nameKey(it.displayName) }.values
            .firstOrNull { it.size > 1 && sameNameKey(it) !in ui.dismissedSameName }
    }
    val dir = LocalLayoutDirection.current
    // Every side but the bottom, where the list scrolls on behind the navigation bar. The sides matter in
    // landscape, where 3-button navigation sits at the edge.
    PullToRefreshBox(isRefreshing = ui.loading, onRefresh = vm::loadCard,
        modifier = Modifier.fillMaxSize().padding(start = pad.calculateStartPadding(dir),
            top = pad.calculateTopPadding(), end = pad.calculateEndPadding(dir)).semantics { paneTitle = "Your eSIMs" }) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            // Bottom padding clears the navigation bar and the Add button.
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, pad.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.profiles.isEmpty()) {
                item(contentType = "empty") { EmptyCard(vm::openAdd) }
            } else {
                if (ui.usbTip && !ui.usbTipDismissed) item(contentType = "banner") {
                    val context = LocalContext.current
                    Banner("Travelling? Without Wi-Fi this app can't reach your card, so you couldn't switch eSIMs. With " +
                        "\"USB debugging\" on too, it keeps working without Wi-Fi until your phone restarts or this app " +
                        "is updated. After a restart, open this app on Wi-Fi once before you leave. Some banking apps " +
                        "won't open while it's on.", "Open settings", onDismiss = vm::dismissUsbTip, onAction = {
                        // Android 17 may never show it as on, so the banner couldn't clear itself.
                        if (ui.settings.usbDebugging == null) vm.dismissUsbTip()
                        openDeveloperOptions(context)
                    })
                }
                if (sameName != null) item(contentType = "banner") {
                    // Rename the one without a name of its own, or an inactive one.
                    val target = sameName.firstOrNull { !it.hasNickname && !it.enabled }
                        ?: sameName.firstOrNull { !it.enabled } ?: sameName.last()
                    Banner("${sameName.size} eSIMs are called \"${sameName.first().displayName}\". Rename one so you can " +
                        "tell them apart.", "Rename", onAction = { vm.openDetails(target) },
                        onDismiss = { vm.dismissSameName(sameNameKey(sameName)) })
                }
                item(contentType = "header") { SectionHeader("Active eSIM") }
                if (active.isEmpty()) item(contentType = "note") {
                    Text("No eSIM is turned on. Choose one below to get service.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
                profileGroup("active", active, ui.notes, onOpen = vm::openDetails, onUse = null)
                if (others.isNotEmpty()) {
                    item(contentType = "header") { SectionHeader("Other eSIMs on this card") }
                    profileGroup("others", others, ui.notes, onOpen = vm::openDetails, onUse = onUse)
                }
                if (hidden.isNotEmpty()) item(key = "hidden", contentType = "hidden") {
                    HiddenSection(hidden, ui.notes, hiddenOpen, onToggle = toggleHidden, onOpen = vm::openDetails, onUse = onUse)
                }
            }
        }
    }
    confirm?.let { c -> ConfirmDialog(c, ui, onDismiss = { confirm = null }) { confirm = null; c.run(vm, it) } }
}

private fun LazyListScope.profileGroup(
    key: String, group: List<EuiccProfile>, notes: Map<String, String>, onOpen: (EuiccProfile) -> Unit,
    onUse: ((EuiccProfile) -> Unit)?,
) {
    if (group.isEmpty()) return
    item(key = key, contentType = "group") { ProfileCard(group, notes, onOpen, onUse) }
}

/** One card holding a group of eSIM rows, divided like a settings list. */
@Composable
private fun ProfileCard(group: List<EuiccProfile>, notes: Map<String, String>, onOpen: (EuiccProfile) -> Unit,
                        onUse: ((EuiccProfile) -> Unit)?) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = cardColor())) {
        group.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            key(p.isdpAid.ifEmpty { p.iccid }) {
                ProfileRow(p, notes[p.iccid], onClick = { onOpen(p) }, onUse = onUse?.let { use -> { use(p) } })
            }
        }
    }
}

/** The hidden eSIMs, folded under a header that opens them; [open]'s target says whether they're shown. */
@Composable
private fun HiddenSection(group: List<EuiccProfile>, notes: Map<String, String>, open: MutableTransitionState<Boolean>,
                          onToggle: () -> Unit, onOpen: (EuiccProfile) -> Unit, onUse: (EuiccProfile) -> Unit) {
    val expanded = open.targetState
    Column {
        val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
        // In line with the other section headers, but the whole width is a 48dp target.
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
                .heightIn(min = 48.dp).padding(start = 4.dp, end = 8.dp)
                .semantics(mergeDescendants = true) { heading(); stateDescription = if (expanded) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically) {
            Text("Hidden eSIMs", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            SmallBadge("${group.size}")
            Spacer(Modifier.weight(1f))
            // Turned in the layer, so the animation only redraws.
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null,
                Modifier.graphicsLayer { rotationZ = turn }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Tucked 6dp up under the 48dp header, so it sits as close to it as the other cards do to theirs. An
        // offset, not negative spacing: the item keeps its height as the card appears, so the list doesn't jump.
        AnimatedVisibility(open, Modifier.offset(y = (-6).dp), enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()) {
            ProfileCard(group, notes, onOpen, onUse)
        }
    }
}

@Composable
private fun ProfileRow(p: EuiccProfile, note: String?, onClick: () -> Unit, onUse: (() -> Unit)?) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = cardColor()),
        leadingContent = {
            Icon(painterResource(if (p.enabled) R.drawable.ic_check_circle else R.drawable.ic_sim),
                contentDescription = null, tint = if (p.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (p.profileClass in 0..1) { Spacer(Modifier.width(8.dp)); SmallBadge(p.classLabel) }
            }
        },
        // Two separate slot contents, not one Column with an optional note: after its note was deleted, that
        // Column kept being measured as two lines, so the row stayed three-line height.
        supportingContent = if (note == null) {{ MetaLine(p) }} else {{
            Column {
                MetaLine(p)
                Text(note.lineSequence().first(), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }},
        trailingContent = when {
            p.enabled -> {{ ActiveBadge() }}
            onUse != null -> {{
                OutlinedButton(onClick = onUse, Modifier.semantics { contentDescription = "Use ${p.displayName}" }) { Text("Use") }
            }}
            else -> null
        },
    )
}

/** Carrier and ICCID on one small line: "1GLOBAL · ICCID 8944 4786 …". Read out, only the last digits: this comes
 *  before the row's state, on every row. The eSIM's sheet reads the whole number. */
@Composable
fun MetaLine(p: EuiccProfile) {
    val carrier = p.carrierName
    Text(
        (carrier?.let { "$it · " } ?: "") + "ICCID " + groupDigits(p.iccid),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = metaSpeech(p) },
    )
}

internal fun metaSpeech(p: EuiccProfile) =
    (p.carrierName?.let { "Network $it. " } ?: "") + "ICCID ending ${spokenDigits(p.iccid.takeLast(4))}"

fun groupDigits(s: String) = s.chunked(4).joinToString(" ")

/** One digit at a time: a screen reader reads [groupDigits]' groups as large numbers. */
fun spokenDigits(s: String) = s.toList().joinToString(" ")

@Composable
fun ActiveBadge(text: String = "Active") {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Text(text, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun SmallBadge(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 0.dp).semantics { heading() })
}

/** The eSIMs in a same-name group, as one key: dismissing the banner lasts until the group changes. */
private fun sameNameKey(group: List<EuiccProfile>) = group.map { it.iccid }.sorted().joinToString(",")

/** A note above the list; [action] is short, as its row doesn't wrap. */
@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.ic_info), contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun EmptyCard(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_sim), contentDescription = null, Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("No eSIMs yet", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text("Scan the QR code from your eSIM provider to add one. Your card can hold several.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Add eSIM")
        }
    }
}

@Composable
private fun Loading() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        // Same style as "Connecting to your card…", which it directly follows.
        Text("Reading your eSIM card…", style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun LoadFailed(error: String?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Text("Couldn't read the eSIM card", style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(error ?: "Make sure the card is in a SIM slot, then try again.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** Only the phone's own chip answered, so nothing on it shows: to this app it would look like the user's card. */
@Composable
private fun BuiltInChip(onRetry: () -> Unit, onMine: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Text("This looks like your phone's own eSIM", style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text("The only eSIM chip found may be the one built into this phone, not a removable card. Manage that one in " +
                "your phone's Settings. If you have a removable eSIM card, check that it's in the SIM tray, then try again.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) { Text("Try again") }
            TextButton(onClick = onMine) { Text("It's my removable card") }
        }
    }
}

/** The technical details, out of the main view: the card's own (EID, slot, free space, versions), then how this
 *  phone reaches it and what's set up for the app here (card access, pairing, eSIM links). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardDetailsSheet(ui: LpaUi, onShown: () -> Unit, onDismiss: () -> Unit, onStop: () -> Unit,
                             onSimSetup: () -> Unit) {
    val eid = ui.eid ?: return
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LaunchedEffect(Unit) { onShown() }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Details", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            DetailsSection("Card") {
                EidCard(eid, "Your eSIM card's own ID number. Some providers ask for it.")
                InfoCard {
                    InfoRow("SIM slot", "SIM ${ui.slot + 1}", "Most eSIMs need data roaming on for this SIM to work.")
                    InfoDivider()
                    val hidden = ui.profiles.count { !it.enabled && it.iccid in ui.hidden }
                    InfoRow("eSIMs on the card", "${ui.profiles.size}" + if (hidden > 0) " ($hidden hidden)" else "")
                    InfoDivider()
                    val info = ui.cardInfo
                    fun reported(value: String?) = value ?: if (ui.cardInfoLoading) "Checking…" else "Not reported"
                    InfoRow("Free space", reported(info?.freeBytes?.let(::formatBytes)), "Room left on the card for new eSIMs.")
                    InfoDivider()
                    InfoRow("eSIM standard", reported(info?.standard?.let { "SGP.22 v$it" }))
                    InfoDivider()
                    InfoRow("Firmware", reported(info?.firmware))
                    InfoDivider()
                    InfoRow("Certificates", reported(info?.rootNames?.takeIf { it.isNotEmpty() }?.joinToString(", ")),
                        "The card only installs eSIMs from providers certified under these. Mainstream providers use GSMA.")
                    InfoDivider()
                    InfoRow("Discovery server", reported(info?.rootDs), if (info?.rootDs?.let(Lpa::isTestServer) == true)
                        "The GSMA's test server, set by the card's maker. Real eSIMs are never sent there, so \"Check for " +
                            "eSIMs sent to this card (SM-DS)\" on the Add eSIM screen asks the GSMA's and Google's instead."
                        else "Where providers can leave an eSIM for this card's EID. \"Check for eSIMs sent to this card (SM-DS)\" " +
                            "on the Add eSIM screen also asks the GSMA's and Google's.")
                    info?.defaultDp?.let {
                        InfoDivider()
                        InfoRow("Default provider", it, "Set on the card by its maker. \"Check for eSIMs sent to this " +
                            "card\" on the Add eSIM screen lists it, in case it has one for you.")
                    }
                }
            }
            // How this phone reaches the card, and what's set up for the app here: none of it moves with the card.
            DetailsSection("On this phone") {
                InfoCard {
                    val adb = ui.method == AccessMethod.ADB
                    // Android 10 and older: no Wireless debugging. A computer turned on Android's debugging port, which
                    // lasts until the phone restarts, and needs USB debugging on to listen.
                    val legacy = Build.VERSION.SDK_INT < 30
                    when {
                        ui.method == AccessMethod.DIRECT -> InfoRow("Card access", "Direct",
                            "Your card lets this app manage it, so nothing needs setting up.")
                        ui.method == AccessMethod.ROOT -> InfoRow("Card access", "Root",
                            "Works without Wi-Fi or Wireless debugging. This app's helper ends when you swipe the app " +
                                "away from Recent apps or tap \"Stop card access\" below.")
                        ui.method == AccessMethod.SHIZUKU -> InfoRow("Card access", "Shizuku",
                            "Works while Shizuku is running. This app's helper ends when you swipe the app away from " +
                                "Recent apps or tap \"Stop card access\" below." +
                                // Only a Shizuku started with root leaves Android's debugging service behind.
                                if (ui.settings.usbDebugging == true) "" else " If you started Shizuku with Wireless " +
                                    "debugging, Android can stop it when Wi-Fi drops. Turning on \"USB debugging\" in " +
                                    "Developer options may help, but some banking apps won't open while it's on.")
                        legacy && !ui.background -> InfoRow("Card access", "USB debugging",
                            "Set up with a computer. Works while USB debugging is on, until your phone restarts. After a " +
                                "restart, it needs the computer again.")
                        // Android 7.1 and older leave the helper running when their debugging service stops.
                        legacy && Build.VERSION.SDK_INT < 26 -> InfoRow("Card access", "Background helper",
                            "Set up with a computer. Usually keeps working until your phone restarts, even with USB " +
                                "debugging off. After a restart, it needs the computer again.")
                        legacy -> InfoRow("Card access", "Background helper",
                            "Set up with a computer. Keeps working while USB debugging is on, until your phone restarts. " +
                                "After a restart, it needs the computer again.")
                        // Started where the background helper can't run: requests go over Wireless debugging itself.
                        !ui.background -> InfoRow("Card access", "Wireless debugging", "Works while Wireless debugging is on.")
                        ui.settings.usbDebugging == true -> InfoRow("Card access", "Background helper",
                            "Keeps working without Wi-Fi until your phone restarts or this app is updated.")
                        // Android 17 may not tell apps whether USB debugging is on.
                        ui.settings.usbDebugging == null -> InfoRow("Card access", "Background helper",
                            "Keeps working without Wi-Fi until your phone restarts or this app is updated, as long as " +
                                "\"USB debugging\" is on in Developer options. Without it, it stops when Wireless debugging " +
                                "goes off.") { openDeveloperOptions(context) }
                        // Android stops its debugging service, and the helper with it, once both kinds are off.
                        else -> InfoRow("Card access", "Background helper", "Stops when Wireless debugging goes off, " +
                            "which Android also does when Wi-Fi drops or changes. Turn on \"USB debugging\" in Developer " +
                            "options to keep it working without Wi-Fi until your phone restarts or this app is updated. " +
                            "Some banking apps won't open while it's on.") { openDeveloperOptions(context) }
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
                    // Unverified links open in the browser until people allow them for an app (Android 12+).
                    if (Build.VERSION.SDK_INT >= 31) {
                        InfoDivider()
                        val on = rememberLinksAllowed()
                        InfoRow("eSIM links", if (on) "On" else "Set up",
                            if (on) "\"Install on iPhone\" links from eSIM shops open in this app."
                            else "Opens \"Install on iPhone\" links from eSIM shops in this app: tap, then add " +
                                "esimsetup.apple.com under \"Add link\".") { openLinkSettings(context) }
                    }
                }
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop card access") }
        }
    }
}

/** One fact: label on the left, value on the right (or below, when they don't fit side by side), an optional
 *  line of help below. A flow, not a Row: a long value, like the card's certificate list, would otherwise
 *  squeeze the label to nothing. With [onClick], the whole row is a button. */
@Composable
private fun InfoRow(label: String, value: String, help: String? = null, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 16.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = if (onClick != null)
                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        help?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable private fun InfoDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/** A labelled group on the Details sheet. */
@Composable
private fun DetailsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        content()
    }
}

/** [InfoRow]s in one card, divided by [InfoDivider]s. */
@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) =
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = cardColor()), content = content)

/** Whether people let this app open "Install on iPhone" links; read again each time the app comes back. */
@androidx.annotation.RequiresApi(31)
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
@androidx.annotation.RequiresApi(31)
private fun openLinkSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
        "package:${context.packageName}".toUri())) }
}

private fun formatBytes(bytes: Long) =
    if (bytes >= 1024 * 1024) String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else "${(bytes + 512) / 1024} KB"

/** The EID as one tappable block that copies it. Two lines of 16 digits, so it never wraps mid-number. */
@Composable
fun EidCard(eid: String, help: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by rememberCopied()
    val copy: () -> Unit = {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("EID", eid))) }
        copied = true
    }
    Surface(onClick = copy, shape = MaterialTheme.shapes.medium, color = cardColor(),
        // Styled like the other cards: a plain fill all but vanished on the Details sheet.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            onClick(label = "Copy") { copy(); true }
            if (copied) stateDescription = "Copied"
        }) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (copied) "EID copied" else "EID", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // On this line, not the card: on the card it would replace the label and the help too.
                Text(eid.chunked(16).joinToString("\n") { groupDigits(it) }, style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.semantics { contentDescription = spokenDigits(eid) })
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Icon(painterResource(if (copied) R.drawable.ic_check_circle else R.drawable.ic_copy),
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

enum class Action { SWITCH, TURN_OFF, DELETE }

data class Confirm(val action: Action, val profile: EuiccProfile) {
    /** [setup] is what was chosen in the dialog; only a switch uses it. */
    fun run(vm: LpaViewModel, setup: ProfileStore.SwitchSetup) = when (action) {
        Action.SWITCH -> vm.enable(profile, setup = setup)
        Action.TURN_OFF -> vm.disable(profile)
        Action.DELETE -> vm.delete(profile)
    }
}

fun switchNote(active: EuiccProfile?) =
    (active?.let { "${it.displayName} will be turned off. " } ?: "") +
        "The card restarts, so your phone may have no signal for up to a minute."

/** What the phone does once the eSIM is on. Checked items are changed; unchecked ones are left as they are. */
@Composable
fun SwitchOptions(setup: ProfileStore.SwitchSetup, onChange: (ProfileStore.SwitchSetup) -> Unit) {
    Column {
        CheckRow("Use for mobile data", "Makes it your mobile data SIM and turns mobile data on.", setup.mobileData) {
            onChange(setup.copy(mobileData = it))
        }
        CheckRow("Turn on data roaming", "Travel eSIMs usually need it. Your home SIM may charge for roaming abroad.",
            setup.roaming) {
            onChange(setup.copy(roaming = it))
        }
    }
}

@Composable
private fun CheckRow(label: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // 48dp: with onCheckedChange = null the Checkbox adds no touch target of its own.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** With [onHide], a delete also offers to hide the eSIM instead. */
@Composable
fun ConfirmDialog(c: Confirm, ui: LpaUi, onDismiss: () -> Unit, onHide: (() -> Unit)? = null,
                  onConfirm: (ProfileStore.SwitchSetup) -> Unit) {
    val name = c.profile.displayName
    val active = ui.profiles.firstOrNull { it.enabled }
    val hide = onHide.takeIf { c.action == Action.DELETE }
    var setup by remember { mutableStateOf(ui.switchSetup) }
    val (title, body, button) = when (c.action) {
        Action.SWITCH -> Triple(if (active == null) "Turn on $name?" else "Switch to $name?", switchNote(active),
            if (active == null) "Turn on" else "Switch")
        Action.TURN_OFF -> Triple("Turn off $name?",
            "You'll have no service from this card until you turn on this eSIM or another one.", "Turn off")
        Action.DELETE -> Triple("Delete $name?",
            "This permanently removes it from the card. Most eSIMs can be downloaded only once, so the same QR " +
                "code probably won't work again, and you'd lose any data or credit left on it." +
                (if (ui.notes.containsKey(c.profile.iccid)) "\n\nIts note on this phone will be deleted too." else "") +
                (if (hide != null) "\n\nOnly want it out of the list? Hide it: it stays on the card, ready to use again." else ""),
            "Delete")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(body)
                // Only where the app can change SIM settings: Android keeps them from ordinary apps.
                if (c.action == Action.SWITCH && ui.canSetUpSim) {
                    Spacer(Modifier.height(16.dp))
                    SwitchOptions(setup) { setup = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(setup) }, colors = if (c.action == Action.DELETE)
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors()) { Text(button) }
        },
        // Hide sits between Cancel and Delete: a way out that still clears the list.
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (hide != null) TextButton(onClick = hide) { Text("Hide") }
            }
        },
    )
}

@Composable
private fun ErrorDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null) },
        title = { Text(title) },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Blocks input (and back) while the card is busy, and keeps the screen on. Appears only after a
 *  moment, so quick operations don't flash a dialog. A [Busy.cancellable] one offers Cancel, and back cancels. */
@Composable
private fun BusyDialog(busy: Busy, onCancel: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val canCancel = busy.cancellable && !busy.cancelling && shown
    Dialog(onDismissRequest = { if (canCancel) onCancel() },
        properties = DialogProperties(dismissOnBackPress = busy.cancellable, dismissOnClickOutside = false)) {
        val view = LocalView.current
        DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
        LaunchedEffect(Unit) { delay(300); shown = true }
        val fade = animateFloatAsState(if (shown) 1f else 0f, label = "busy")
        // Read in the layer, not in composition: the fade only redraws instead of recomposing the dialog each frame.
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.graphicsLayer { alpha = fade.value; clip = true }) {
            Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val progress = busy.progress
                if (progress == null) CircularProgressIndicator()
                else {
                    val p by animateFloatAsState(progress, label = "progress")
                    LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(16.dp))
                Text(busy.label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height(4.dp))
                Text("Keep the app open until this finishes.", style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Kept while cancelling, just disabled, so the dialog doesn't change size. Not tappable while it's
                // still invisible: a quick second tap on the button that started this would land on it.
                if (busy.cancellable) TextButton(onClick = onCancel, enabled = canCancel,
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("Cancel") }
            }
        }
    }
}

/** "Copied" shown in place for 2 seconds. A snackbar would be hidden behind a bottom sheet, and
 *  only Android 13+ confirms copies itself. */
@Composable
fun rememberCopied(): MutableState<Boolean> {
    val copied = remember { mutableStateOf(false) }
    LaunchedEffect(copied.value) { if (copied.value) { delay(2000); copied.value = false } }
    return copied
}
