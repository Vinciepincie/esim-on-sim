package com.esimonsim.ui

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.esimonsim.R
import com.esimonsim.lpa.EuiccProfile
import com.esimonsim.lpa.Lpa
import com.esimonsim.lpa.nameKey
import kotlinx.coroutines.launch

private enum class Edit { NAME, NOTE }

/** One eSIM: its name, note and ICCID, and the less common actions (turn off, hide, delete). [onHide] closes the
 *  sheet and hides the eSIM. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(p: EuiccProfile, ui: LpaUi, vm: LpaViewModel, onHide: (EuiccProfile) -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val note = ui.notes[p.iccid]
    val hidden = p.iccid in ui.hidden
    var edit by rememberSaveable { mutableStateOf<Edit?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var copied by rememberCopied()
    val enabled = ui.busy == null && !ui.loading
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Slides the sheet away first: the "hidden" message, with its Undo, shows below it. Once only: a second tap
    // would cut the slide short, and both would hide it and post a message.
    var hiding by remember { mutableStateOf(false) }
    val hide: () -> Unit = {
        if (!hiding) { hiding = true; scope.launch { sheet.hide() }.invokeOnCompletion { onHide(p) } }
    }

    // Rotating with a dialog's keyboard up can leave the sheet hidden behind its own scrim, which then swallows the
    // next tap. Whatever happened meanwhile, it's back once the dialog closes.
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(edit) {
        if (edit != null) editing = true
        else if (editing) { editing = false; if (!hiding) sheet.show() }
    }

    ModalBottomSheet(onDismissRequest = vm::closeDetails, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.displayName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                // A flow, not a Row: at large font sizes the badges would squeeze the carrier's name to nothing.
                FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (p.enabled) ActiveBadge() else SmallBadge("Off")
                    // The active eSIM shows in the list anyway; it's hidden again once it's off.
                    if (hidden) SmallBadge(if (p.enabled) "Hidden when off" else "Hidden")
                    if (p.profileClass in 0..1) SmallBadge("${p.classLabel} eSIM")
                    p.carrierName?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!p.enabled) Button(onClick = { confirm = Confirm(Action.SWITCH, p) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Use this eSIM") }
            }
            Spacer(Modifier.height(8.dp))
            SheetRow(R.drawable.ic_edit, "Name", if (p.hasNickname) p.displayName else "Add a name you'll recognise",
                placeholder = !p.hasNickname, enabled = enabled) { edit = Edit.NAME }
            SheetRow(R.drawable.ic_notes, "Note", note ?: "Add a note: data plan, expiry, shop",
                placeholder = note == null) { edit = Edit.NOTE }
            SheetRow(R.drawable.ic_copy, if (copied) "ICCID copied" else "ICCID", groupDigits(p.iccid), mono = true,
                speech = "ICCID ${spokenDigits(p.iccid)}", clickLabel = "Copy", copied = copied) {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ICCID", p.iccid))) }
                copied = true
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(horizontal = 12.dp)) {
                if (p.enabled) TextButton(onClick = { confirm = Confirm(Action.TURN_OFF, p) }, enabled = enabled) {
                    Text("Turn off this eSIM")
                }
                // Only this phone's list changes, so it never needs the card. The active eSIM always shows, so
                // hiding is offered once it's off. One button that changes, not two: TalkBack keeps its place on it
                // and reads the new state.
                if (hidden || !p.enabled) TextButton(onClick = if (hidden) {{ vm.setHidden(p, false) }} else hide,
                    enabled = !hiding, modifier = Modifier.semantics {
                        stateDescription = when { !hidden -> "Shown in list"; p.enabled -> "Hidden when off"; else -> "Hidden" }
                    }) {
                    Icon(painterResource(if (hidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                        contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(when { !hidden -> "Hide from list"; p.enabled -> "Keep in list"; else -> "Show in list" })
                }
                TextButton(onClick = { confirm = Confirm(Action.DELETE, p) }, enabled = enabled && !p.enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Delete from card")
                }
                if (p.enabled) Text("Turn this eSIM off before deleting it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }

    when (edit) {
        Edit.NAME -> RenameDialog(p, ui.profiles, onDismiss = { edit = null }) { edit = null; vm.rename(p, it) }
        Edit.NOTE -> NoteDialog(p, note, onDismiss = { edit = null }) { edit = null; vm.setNote(p, it) }
        null -> {}
    }
    confirm?.let { c ->
        ConfirmDialog(c, ui, onDismiss = { confirm = null }, onHide = if (hidden) null else {{ confirm = null; hide() }}) {
            confirm = null; c.run(vm, it)
        }
    }
}

@Composable
private fun SheetRow(icon: Int, label: String, value: String, placeholder: Boolean = false, mono: Boolean = false,
                     enabled: Boolean = true, speech: String? = null, clickLabel: String? = null, copied: Boolean = false,
                     onClick: () -> Unit) {
    ListItem(
        // 8dp more inset puts the icons on the same 24dp line as the title above and Delete below.
        modifier = Modifier.clickable(enabled = enabled, onClickLabel = clickLabel, onClick = onClick)
            .then(if (speech != null) Modifier.semantics(mergeDescendants = true) {
                contentDescription = speech
                if (copied) stateDescription = "Copied"
            } else Modifier)
            .padding(horizontal = 8.dp),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        overlineContent = { Text(label) },
        headlineContent = {
            Text(value, fontFamily = if (mono) FontFamily.Monospace else null,
                color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        },
    )
}

/** Names are stored on the card, which holds at most 64 bytes of UTF-8. */
private fun nameBytes(s: String) = s.trim().toByteArray(Charsets.UTF_8).size


/** Ready to add to what's there, not to type in front of it. */
private fun caretAtEnd(text: String) = TextFieldValue(text, TextRange(text.length))

@Composable
fun RenameDialog(p: EuiccProfile, all: List<EuiccProfile>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(caretAtEnd(p.nickname.orEmpty())) }
    val name = field.text
    val fallback = p.copy(nickname = null).displayName // what shows once the name is cleared
    val tooLong = nameBytes(name) > Lpa.MAX_NICKNAME_BYTES
    val taken = all.firstOrNull { it.iccid != p.iccid && nameKey(it.displayName) == nameKey(name.ifBlank { fallback }) }
    val changed = name.trim() != p.nickname.orEmpty().trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename eSIM") },
        text = {
            ClearFocusWhenKeyboardHides()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = field, onValueChange = { field = it },
                    label = { Text("Name") }, placeholder = { Text(fallback) },
                    supportingText = {
                        Text(when {
                            tooLong -> "Too long. Use a shorter name."
                            taken != null -> "Another eSIM on this card is already called \"${taken.displayName}\"."
                            else -> "Saved on the card, so other phones and eSIM apps show it too.\nLeave empty to use \"$fallback\"."
                        }, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    },
                    isError = tooLong, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth())
                p.carrierName?.let {
                    Text("\"$it\" is the network name stored in this eSIM. It can differ from the shop you bought it " +
                        "from: Saily eSIMs, for example, show 1GLOBAL.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = changed && !tooLong) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NoteDialog(p: EuiccProfile, current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(caretAtEnd(current.orEmpty())) }
    val text = field.text
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note for ${p.displayName}") },
        text = {
            ClearFocusWhenKeyboardHides()
            OutlinedTextField(value = field, onValueChange = { field = it.copy(text = it.text.take(500)) },
                label = { Text("Note") }, placeholder = { Text("For example: 10 GB, expires 12 March, bought on Saily") },
                supportingText = { Text("Saved only on this phone, not on the card.") },
                minLines = 3, maxLines = 6, textStyle = FieldText,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.trim() != current.orEmpty()) { Text("Save") } },
        dismissButton = {
            FlowRow {
                if (current != null) TextButton(onClick = { onSave("") },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete note") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
