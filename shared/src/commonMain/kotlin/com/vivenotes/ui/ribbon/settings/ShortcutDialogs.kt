package com.vivenotes.ui.ribbon.settings

import com.vivenotes.localization.Strings
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.ui.icons.SettingsSymbols
import com.vivenotes.ui.keyboard.isModifier
import com.vivenotes.ui.keyboard.penKeyLabel
import com.vivenotes.ui.keyboard.toChord
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.PenAction
import com.vivenotes.workspace.PenButtonSettings
import com.vivenotes.workspace.PenKeyBinding
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutKey

/** Semantics identifiers for the shortcut dialogs. */
object ShortcutDialogTags {
    const val Capture = "hardware-shortcut-capture"
    const val Captured = "hardware-shortcut-captured"
    const val Message = "hardware-shortcut-message"
    const val Replace = "hardware-shortcut-replace"
    const val Cancel = "hardware-shortcut-cancel"
    const val ResetAll = "hardware-reset-all-dialog"
    const val ConfirmResetAll = "hardware-reset-all-confirm"
    const val CancelResetAll = "hardware-reset-all-cancel"
    const val PenKeyCapture = "hardware-pen-key-capture"
    const val PenKeyCaptured = "hardware-pen-key-captured"
    const val PenKeyMessage = "hardware-pen-key-message"
    const val PenKeyReplace = "hardware-pen-key-replace"
    const val PenKeyCancel = "hardware-pen-key-cancel"
}

/**
 * Asks for [action]'s new keys, as GNOME Settings does: the next chord pressed becomes the shortcut,
 * Esc cancels, and Backspace disables it. A chord another command already uses is held until
 * Replace (or Enter) confirms taking it; one that needs a modifier to be [action]'s shortcut — a key
 * that types, for a command that can run in a text field — is refused.
 * [onSet] receives the chord, or null to disable.
 */
@Composable
internal fun ShortcutCaptureDialog(
    action: ShortcutAction,
    bindings: KeyBindings,
    onSet: (KeyChord?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember(action) { mutableStateOf<KeyChord?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val held = pending
    val conflict = held?.takeIf(action::accepts)?.let { bindings.conflict(action, it) }

    fun capture(chord: KeyChord) {
        when {
            // Its own keys again: nothing changes, and a zoom keeps its numpad and Shift spellings.
            chord in bindings.chords(action) -> onDismiss()
            action.accepts(chord) && bindings.conflict(action, chord) == null -> onSet(chord)
            else -> pending = chord
        }
    }

    DesktopDialogFrame(
        title = Strings.set_shortcut,
        icon = SettingsSymbols.Keyboard,
        onDismiss = onDismiss,
        maxWidth = 420.dp,
        modifier = Modifier.testTag(ShortcutDialogTags.Capture),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .focusable()
                    // Every key is the answer, so none may reach anything else. Esc never gets here:
                    // the dialog frame takes it first and cancels.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key.isModifier()) return@onPreviewKeyEvent true
                        val chord = event.toChord() ?: return@onPreviewKeyEvent true
                        when {
                            chord == KeyChord(ShortcutKey.Backspace) -> onSet(null)
                            chord == KeyChord(ShortcutKey.Enter) && conflict != null -> onSet(held)
                            else -> capture(chord)
                        }
                        true
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(Strings.press_the_new_keys_for_value(action.label), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Keycap(held?.label ?: "…", large = true,
                    modifier = Modifier.padding(vertical = 4.dp).testTag(ShortcutDialogTags.Captured))
                Text(
                    text = when {
                        held != null && !action.accepts(held) ->
                            Strings.value_can_t_be_a_shortcut_on_its_own_add_ctrl(held.label)
                        conflict != null ->
                            Strings.value_is_already_used_for_value_replacing_it_removes_it_from(held?.label, conflict.label, conflict.label)
                        else -> Strings.now_value(bindings.primary(action)?.label ?: Strings.disabled) +
                            Strings.esc_cancels_backspace_disables_the_shortcut
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (held != null && !action.accepts(held)) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.heightIn(min = 32.dp).testTag(ShortcutDialogTags.Message),
                )
            }
        },
        actions = {
            OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(ShortcutDialogTags.Cancel)) { Text(Strings.cancel) }
            if (conflict != null) {
                Button(onClick = { onSet(held) }, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.testTag(ShortcutDialogTags.Replace)) { Text(Strings.replace) }
            }
        },
    )
}

/**
 * Asks for the hardware key [slot] holds, as the shortcut capture asks for its chords: the next
 * key pressed takes the slot, Esc cancels, and Backspace clears it. A key another slot already
 * has is held until Replace (or Enter) confirms taking it — the key moves, and what it does moves
 * with it. [onSet] receives the binding, or null to clear the slot.
 */
@Composable
internal fun PenKeyCaptureDialog(
    slot: Int,
    buttons: PenButtonSettings,
    onSet: (PenKeyBinding?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember(slot) { mutableStateOf<PenKeyBinding?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val held = pending
    val conflict = held?.let { buttons.keyConflict(slot, it.code) }
    val current = buttons.key(slot)

    DesktopDialogFrame(
        title = Strings.set_hardware_key,
        icon = SettingsSymbols.Stylus,
        onDismiss = onDismiss,
        maxWidth = 420.dp,
        modifier = Modifier.testTag(ShortcutDialogTags.PenKeyCapture),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .focusable()
                    // Every key is the answer, so none may reach anything else. Esc never gets
                    // here: the dialog frame takes it first and cancels. A modifier key is on its
                    // way to a chord, never a pen's own key.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key.isModifier()) return@onPreviewKeyEvent true
                        when {
                            event.key == Key.Backspace -> onSet(null)
                            event.key == Key.Enter && conflict != null -> onSet(held)
                            else -> {
                                val code = event.key.keyCode
                                // Key 0 is Compose's "unknown key", which nothing can bind. A key
                                // keeps doing what it did when it moves slots; a new key on a
                                // configured row keeps the row's action; an empty one starts with
                                // Undo, so the button works before anything is picked.
                                val binding = if (code == 0L) null else PenKeyBinding(code, event.key.penKeyLabel(),
                                    current?.action ?: buttons.bindingFor(code)?.action ?: PenAction.Undo)
                                // A key no other slot has is taken at once, as a chord without a
                                // conflict is; one another slot has waits for Replace.
                                if (binding != null) {
                                    if (buttons.keyConflict(slot, code) == null) onSet(binding) else pending = binding
                                }
                            }
                        }
                        true
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(Strings.press_the_new_key_for_value(Strings.pen_hardware_key_value(slot + 1)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Keycap(held?.label ?: "…", large = true,
                    modifier = Modifier.padding(vertical = 4.dp).testTag(ShortcutDialogTags.PenKeyCaptured))
                Text(
                    text = when {
                        conflict != null -> Strings.value_is_already_captured_for_value(
                            held?.label ?: "", Strings.pen_hardware_key_value(conflict + 1))
                        else -> Strings.now_value(current?.label ?: Strings.not_set) +
                            Strings.esc_cancels_backspace_clears_the_key
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.heightIn(min = 32.dp).testTag(ShortcutDialogTags.PenKeyMessage),
                )
            }
        },
        actions = {
            OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(ShortcutDialogTags.PenKeyCancel)) { Text(Strings.cancel) }
            if (conflict != null) {
                Button(onClick = { onSet(held) }, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.testTag(ShortcutDialogTags.PenKeyReplace)) { Text(Strings.replace) }
            }
        },
    )
}

/** Confirms Reset All: it throws away every change at once, and there is no undoing it. */
@Composable
internal fun ResetAllShortcutsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DesktopDialogFrame(
        title = Strings.reset_all_shortcuts,
        icon = SettingsSymbols.RestartAlt,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(ShortcutDialogTags.ResetAll),
        content = {
            Text(Strings.every_keyboard_shortcut_goes_back_to_its_default_and_your_changes,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        actions = {
            OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.small,
                modifier = Modifier.focusRequester(focus).testTag(ShortcutDialogTags.CancelResetAll)) { Text(Strings.cancel) }
            Button(
                onClick = onConfirm,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalDesktopColors.current.destructive,
                    contentColor = Color.White,
                ),
                modifier = Modifier.testTag(ShortcutDialogTags.ConfirmResetAll),
            ) { Text(Strings.reset_all_58f5fb) }
        },
    )
}
