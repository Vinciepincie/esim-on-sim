package com.esimonsim.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.esimonsim.R
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

    // Android 11 and older: the Theme setting, before anything reads a resource (see ThemeSetting).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        ThemeSetting.override(newBase)?.let(::applyOverrideConfiguration)
    }

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
    // This app's Settings, over whatever pane is below. Not closed by a reconnect: it's where the way in can change
    // while there is none.
    var settings by rememberSaveable { mutableStateOf(false) }
    // The Card access dialog: null closed, -1 opened in Settings, or the SIM (from 0) of a card direct access can't
    // open. Settings opened for that card closes once card access switched: switchesAtOpen counts from then. Cancel,
    // or the dialog opened again from Settings' own row, leaves Settings open after a switch, to see the choice change.
    var accessDialogFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var switchesAtOpen by rememberSaveable { mutableIntStateOf(-1) }
    val openAccess: (Int) -> Unit = { sim -> settings = true; accessDialogFor = sim; switchesAtOpen = ui.accessSwitches }
    val closeSettings = { settings = false; accessDialogFor = null; switchesAtOpen = -1 }
    LaunchedEffect(ui.accessSwitches) {
        // Above the count: from before Android stopped the app, and the count started again from 0.
        if (switchesAtOpen > ui.accessSwitches) switchesAtOpen = -1
        else if (switchesAtOpen in 0 until ui.accessSwitches) closeSettings()
    }
    // A fresh bar state per screen, so a checklist scrolled down doesn't leave the card screen's bar tinted.
    val scroll = TopAppBarDefaults.pinnedScrollBehavior(key(ready, installing, settings) { rememberTopAppBarState() })
    var cardDetails by rememberSaveable { mutableStateOf(false) }
    // Optional, with direct card access: allowing this app in Shizuku, or pairing Wireless debugging (on Android 10
    // and older, allowing this app's debugging access), just so the app can set mobile data and roaming. From Settings.
    var simSetup by rememberSaveable { mutableStateOf(false) }
    val draft = rememberSaveable(saver = AddDraft.Saver) { AddDraft() }
    LaunchedEffect(ui.adding) { if (!ui.adding && !ui.keepDraft) draft.clear() }
    // Card-only sheets and dialogs don't come back by themselves after a reconnect.
    LaunchedEffect(ready) { if (!ready) { cardDetails = false; simSetup = false } }
    // A code from another app, or an Add eSIM form back after Android stopped the app, is never hidden behind Settings.
    LaunchedEffect(installing) { if (installing) closeSettings() }
    val pane = when {
        ready && ui.accessSetup -> Pane.SWITCH
        ready && simSetup -> Pane.SIM_SETTINGS
        settings -> Pane.SETTINGS
        !ready -> Pane.SETUP
        installing -> Pane.ADD
        else -> Pane.CARD
    }
    // Opening Settings, or a pane from it, keeps the one below as it was: half-done setup steps, a pairing under way,
    // the card list's scroll. Any other move starts the pane left afresh next time, as ever.
    val panes = rememberSaveableStateHolder()
    var shown by remember { mutableStateOf(pane) }
    LaunchedEffect(pane) {
        if (pane != shown && pane.rank <= shown.rank) panes.removeState(shown.name)
        shown = pane
    }
    // Left under Settings and over with meanwhile: setup once card access is up, the card screen once it went.
    LaunchedEffect(ready) { panes.removeState((if (ready) Pane.SETUP else Pane.CARD).name) }

    // Shown from a scope that outlives the message, so the next operation clearing it doesn't cut it short.
    val scope = rememberCoroutineScope()
    var undoHide by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current
    LaunchedEffect(ui.info) {
        ui.info?.let { msg ->
            val simSettings = ui.infoSimSettings
            // Only OMAPI's slot numbers are Android's SIM slots; the helper's are physical ones, unless it said otherwise.
            val slot = ui.androidSlot.takeIf { it >= 0 }
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
    // it already is, after the "eSIM added" screen if one is up. Not over a pane that sets something up: a pairing
    // there would end.
    val free = ready && !ui.loading && ui.eid != null && ui.busy == null && ui.installed == null && !simSetup &&
        !ui.accessSetup
    LaunchedEffect(ui.incoming, free) {
        val code = ui.incoming
        if (code == null || !free) return@LaunchedEffect
        vm.clearIncoming()
        draft.clear(); draft.code = code
        vm.openAddFromOutside()
    }
    BackHandler(enabled = installing && ui.busy == null) { if (ui.installed != null) vm.finishInstall("", false) else vm.closeAdd() }
    // Below the panes' own: those that set something up go back to Settings first.
    BackHandler(enabled = settings) { closeSettings() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    if (pane == Pane.SETTINGS) return@TopAppBar Text("Settings")
                    // The phone's own SIM settings go by slot, so the card's is kept in sight. Styled like
                    // Material's app bar subtitle, which material3 1.4 keeps internal. With several cards it's where
                    // to change card, also while one can't be read, as another may be the way out.
                    val switcher = (pane == Pane.CARD || pane == Pane.ADD) && ui.installed == null && ui.switcher
                    // Closed when the switcher goes, so it can't pop up again by itself when it's back.
                    var open by remember(switcher) { mutableStateOf(false) }
                    Box {
                        Column(if (!switcher) Modifier else Modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                            .clickable(enabled = !ui.loading && ui.busy == null, onClickLabel = "Change card",
                                role = Role.DropdownList) { open = true }
                            .semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.Center) {
                            Text(if (installing) "Add eSIM" else "eSIM On-SIM")
                            if (switcher) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if ((installing && ui.pickCard) || ui.slot < 0) "Choose a card" else "Card in SIM ${ui.simNumber}",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else if (ready && !installing && ui.eid != null) Text("Card in SIM ${ui.simNumber}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (switcher) CardMenu(ui, vm, open, onLocked = openAccess) { open = false }
                    }
                },
                navigationIcon = {
                    when (pane) {
                        // Disabled, not hidden, while busy: removing it shifted the title sideways.
                        Pane.ADD -> IconButton(onClick = {
                            if (ui.installed != null) vm.finishInstall("", false) else vm.closeAdd()
                        }, enabled = ui.busy == null) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close") }
                        // Card access carries on as it is.
                        Pane.SWITCH -> IconButton(onClick = vm::closeAccessSetup) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Close")
                        }
                        Pane.SIM_SETTINGS -> IconButton(onClick = { vm.cancelPairing(); simSetup = false }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Close")
                        }
                        Pane.SETTINGS -> IconButton(onClick = closeSettings) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                        }
                        else -> {}
                    }
                },
                scrollBehavior = scroll,
                actions = {
                    val onAbout = { AboutActivity.open(context, ui) }
                    val onSettings = { settings = true }
                    when (pane) {
                        Pane.CARD -> CardActions(enabled = !ui.loading && ui.busy == null, cardRead = ui.eid != null,
                            onRefresh = vm::loadCard, onSettings = onSettings, onDetails = { cardDetails = true },
                            onAbout = onAbout)
                        // Setup and reconnecting: how to reach the card, and what the app is, before setting anything up.
                        Pane.SETUP -> MoreMenu { close ->
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { close(); onSettings() })
                            DropdownMenuItem(text = { Text("About") }, onClick = { close(); onAbout() })
                        }
                        else -> {}
                    }
                },
            )
        },
        floatingActionButton = {
            if (pane == Pane.CARD && ui.eid != null && ui.profiles.isNotEmpty()) ExtendedFloatingActionButton(
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
        val inset = Modifier.padding(pad).consumeWindowInsets(pad)
        // Panes, each on its own screen's root: the screens swap inside one window, and a new pane is what a screen
        // reader announces. Not on anything above that stays through the swap, and never nested.
        panes.SaveableStateProvider(pane.name) {
            when (pane) {
                Pane.SWITCH -> SimSettingsSetup(ui, vm, inset.semantics {
                    paneTitle = if (Build.VERSION.SDK_INT >= 30) "Set up Wireless debugging" else "Set up USB debugging"
                }, SetupFor.SWITCH, onDone = vm::closeAccessSetup)
                Pane.SIM_SETTINGS -> SimSettingsSetup(ui, vm, inset.semantics { paneTitle = "Mobile data and roaming" },
                    SetupFor.SIM_SETTINGS) { simSetup = false }
                Pane.SETTINGS -> SettingsScreen(ui, vm, inset.semantics { paneTitle = "Settings" }, accessDialogFor,
                    onAccessDialog = { if (it == -1) switchesAtOpen = -1; accessDialogFor = it },
                    onAccessCancel = { accessDialogFor = null; switchesAtOpen = -1 }, onSimSetup = { simSetup = true },
                    onClose = closeSettings)
                Pane.SETUP -> SetupScreen(ui, vm, inset.semantics {
                    paneTitle = if (ui.access == AccessStatus.CONNECTING) "Connecting to your card" else "Card access"
                })
                Pane.ADD -> AddScreen(ui, vm, draft, pad)
                Pane.CARD -> CardScreen(ui, vm, pad, onCardAccess = openAccess)
            }
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
    if (ready && cardDetails && ui.eid != null) CardDetailsSheet(ui, onShown = vm::loadCardInfo, onDismiss = { cardDetails = false })
    if (ready) ui.duplicate?.let { DuplicateDialog(it, vm) }
    val errorTitle = ui.errorTitle
    val error = ui.error
    if (errorTitle != null && error != null) ErrorDialog(errorTitle, error, vm::dismissError)
    // The install screen shows its own progress; other work on it (checking for eSIMs) gets the dialog.
    ui.busy?.let { if (!installing || it.step == null) BusyDialog(it, onCancel = vm::cancelBusy) }
}

/** The screens [LpaScreen] swaps between. What opens from Settings shows over it, and it over the rest: going up a
 *  [rank] keeps the pane below as it was, for coming back to. */
private enum class Pane(val rank: Int) { SWITCH(2), SIM_SETTINGS(2), SETTINGS(1), SETUP(0), ADD(0), CARD(0) }

@Composable
private fun CardActions(enabled: Boolean, cardRead: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit,
                        onDetails: () -> Unit, onAbout: () -> Unit) {
    IconButton(onClick = onRefresh, enabled = enabled) {
        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh")
    }
    MoreMenu { close ->
        // Settings and Details also while the card is busy, unlike Refresh: Settings disables what can't change then,
        // and Details is nothing until the card has been read.
        DropdownMenuItem(text = { Text("Settings") }, onClick = { close(); onSettings() })
        DropdownMenuItem(text = { Text("Details") }, enabled = cardRead, onClick = { close(); onDetails() })
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

/** [onCardAccess]: opens Card access in Settings for the card in that SIM (from 0), which direct access can't open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardScreen(ui: LpaUi, vm: LpaViewModel, pad: PaddingValues, onCardAccess: (Int) -> Unit) {
    if (ui.eid == null) {
        Box(Modifier.padding(pad)) {
            when {
                ui.loading -> Loading()
                ui.builtInChip != null -> BuiltInChip(idle = ui.idleSlot >= 0, onRetry = vm::loadCard, onMine = vm::ownCard)
                else -> LoadFailed(ui.error, vm::loadCard,
                    Lpa.lockedSim(ui.error)?.let { sim -> { onCardAccess(sim - 1) } })
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
            top = pad.calculateTopPadding(), end = pad.calculateEndPadding(dir))
            .semantics { paneTitle = if (ui.switcher) "Your eSIMs, SIM ${ui.simNumber}" else "Your eSIMs" }) {
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

/** [onCardAccess]: the only eSIM card found doesn't let direct access in, so another way in is what helps. */
@Composable
private fun LoadFailed(error: String?, onRetry: () -> Unit, onCardAccess: (() -> Unit)?) {
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
            onCardAccess?.let { TextButton(onClick = it) { Text("Change card access") } }
        }
    }
}

/** Only the phone's own chip answered, so nothing on it shows: to this app it would look like the user's card. [idle]:
 *  the phone isn't using a slot with another eSIM card in it, which is likely the removable one. Then saying this chip is
 *  it would be a mistake that hands Android's own eSIMs to this app, so that's not offered. */
@Composable
private fun BuiltInChip(idle: Boolean, onRetry: () -> Unit, onMine: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Text("This looks like your phone's own eSIM", style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(if (idle) "The only eSIM chip this app can open may be the one built into this phone. Your phone isn't " +
                "using another SIM slot that has an eSIM card in it, which may be your removable card. Turn that SIM on in " +
                "your phone's SIM settings, then try again."
                else "The only eSIM chip found may be the one built into this phone, not a removable card. Manage that one in " +
                "your phone's Settings. If you have a removable eSIM card, check that it's in the SIM tray, then try again.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            if (idle) {
                Button(onClick = { openSimSettings(context, null, -1) }) { Text("SIM settings") }
                TextButton(onClick = onRetry) { Text("Try again") }
            } else {
                Button(onClick = onRetry) { Text("Try again") }
                TextButton(onClick = onMine) { Text("It's my removable card") }
            }
        }
    }
}

/** The cards in the phone, for the switcher: the one in view checked, unless the Add eSIM form waits for a pick. One the
 *  phone isn't using leads to SIM settings, as this app can't open it there. One that direct access can't open leads
 *  to [onLocked], with its SIM (from 0): another way in opens it. Under the Add eSIM form there's none ([LpaUi.menuRows]). */
@Composable
fun CardMenu(ui: LpaUi, vm: LpaViewModel, open: Boolean, onLocked: (sim: Int) -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        ui.menuRows.forEach { row ->
            val current = row.eid != null && row.slot == ui.slot && !(ui.adding && ui.pickCard)
            DropdownMenuItem(
                text = {
                    Column {
                        Text(row.title, style = MaterialTheme.typography.bodyLarge)
                        row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                leadingIcon = {
                    Icon(painterResource(if (current) R.drawable.ic_check_circle else R.drawable.ic_sim), contentDescription = null,
                        tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                },
                onClick = {
                    onDismiss()
                    when {
                        row.locked -> onLocked(row.sim)
                        row.eid != null -> vm.selectCard(row.eid)
                        // A slot the phone isn't using has no SIM page: the list of SIMs, where it can be turned on.
                        else -> openSimSettings(context, null, -1)
                    }
                },
                modifier = Modifier.semantics { selected = current },
            )
        }
    }
}

/** The card's own details, out of the main view: EID, slot, free space, versions. What's set up for the app on this
 *  phone is in Settings, so nothing shows in both. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardDetailsSheet(ui: LpaUi, onShown: () -> Unit, onDismiss: () -> Unit) {
    val eid = ui.eid ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LaunchedEffect(Unit) { onShown() }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Details", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            EidCard(eid, "Your eSIM card's own ID number. Some providers ask for it.")
            InfoCard {
                InfoRow("SIM slot", "SIM ${ui.simNumber}", "Most eSIMs need data roaming on for this SIM to work.")
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
    }
}

/** One fact: label on the left, value on the right (or below, when they don't fit side by side), an optional
 *  line of help below. A flow, not a Row: a long value, like the card's certificate list, would otherwise
 *  squeeze the label to nothing. With [onClick], the whole row is a button. */
@Composable
internal fun InfoRow(label: String, value: String, help: String? = null, onClick: (() -> Unit)? = null) {
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

@Composable internal fun InfoDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/** A labelled group of [InfoCard]s, as in Settings. */
@Composable
internal fun DetailsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        content()
    }
}

/** [InfoRow]s in one card, divided by [InfoDivider]s. */
@Composable
internal fun InfoCard(content: @Composable ColumnScope.() -> Unit) =
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = cardColor()), content = content)

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

enum class Action { SWITCH, TURN_OFF, DELETE }

data class Confirm(val action: Action, val profile: EuiccProfile) {
    /** [setup] is what was chosen in the dialog; only a switch uses it. */
    fun run(vm: LpaViewModel, setup: ProfileStore.SwitchSetup) = when (action) {
        Action.SWITCH -> vm.enable(profile, setup = setup)
        Action.TURN_OFF -> vm.disable(profile)
        Action.DELETE -> vm.delete(profile)
    }
}

/** Some phones stop seeing a card with no eSIM on, and then only another phone can turn one on again. With [others] on
 *  the card, switching to one is the way that never leaves it with none. */
fun turnOffNote(others: Boolean) = (if (others) "To use another eSIM on this card, tap Use next to it instead: that " +
    "turns this one off for you.\n\n" else "") + "With no eSIM on, you'll have no service from this card. Some phones " +
    "also stop seeing the card until an eSIM is turned on in another phone."

/** With [sim], the card's SIM number when there are several: the other card's SIM keeps its signal. */
fun switchNote(active: EuiccProfile?, sim: Int? = null) =
    (active?.let { "${it.displayName} will be turned off. " } ?: "") +
        "The card restarts, so ${sim?.let { "SIM $it" } ?: "your phone"} may have no signal for up to a minute."

/** What the phone does once the eSIM is on. Checked items are changed; unchecked ones are left as they are.
 *  [dataElsewhere]: another SIM has mobile data, which Android turns off there when this one takes it. */
@Composable
fun SwitchOptions(setup: ProfileStore.SwitchSetup, dataElsewhere: Boolean = false, onChange: (ProfileStore.SwitchSetup) -> Unit) {
    Column {
        CheckRow("Use for mobile data", if (dataElsewhere) "Moves mobile data from your other SIM and turns it off there."
            else "Makes it your mobile data SIM and turns mobile data on.", setup.mobileData) {
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
    val context = LocalContext.current
    // With several cards, mobile data starts ticked where it is now, or with no SIM having it: a choice remembered from
    // the other card would take it from there.
    val elsewhere = remember { if (ui.manageable >= 2) dataElsewhere(context, ui.androidSlot) else null }
    var setup by remember { mutableStateOf(elsewhere?.let { ui.switchSetup.copy(mobileData = !it) } ?: ui.switchSetup) }
    val (title, body, button) = when (c.action) {
        Action.SWITCH -> Triple(if (active == null) "Turn on $name?" else "Switch to $name?",
            switchNote(active, ui.simNumber.takeIf { ui.manageable >= 2 }),
            if (active == null) "Turn on" else "Switch")
        Action.TURN_OFF -> Triple("Turn off $name?", turnOffNote(others = ui.profiles.size > 1), "Turn off")
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
                    SwitchOptions(setup, dataElsewhere = elsewhere == true) { setup = it }
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
