package com.vivenotes.ui.ribbon.settings

import com.vivenotes.localization.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.PaneGroup
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.components.ToolPane
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.SettingsSymbols
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.InputSettings
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.PenAction
import com.vivenotes.workspace.PenButtonSettings
import com.vivenotes.workspace.PenKeyBinding
import com.vivenotes.workspace.ShortcutAction

/** Semantics identifiers for the Hardware pane. */
object HardwareTags {
    const val Open = "settings-hardware-open"
    const val Shortcuts = "hardware-shortcuts"
    const val ResetAll = "hardware-shortcuts-reset-all"
    const val FingerDraws = "hardware-stylus-finger-draws"
    const val PenHaptics = "hardware-pen-haptics"
    const val PenBarrel = "hardware-pen-barrel"
    const val PenBarrel2 = "hardware-pen-barrel-2"
    fun kind(kind: HardwareKind): String = "hardware-${kind.name.lowercase()}"
    fun shortcut(action: ShortcutAction): String = "hardware-shortcut-${action.name}"
    fun reset(action: ShortcutAction): String = "hardware-shortcut-reset-${action.name}"
    fun penKey(slot: Int): String = "hardware-pen-key-$slot"
    fun penKeyAction(slot: Int): String = "hardware-pen-key-action-$slot"
    fun penAction(action: PenAction): String = "hardware-pen-action-${action.name}"
}

/** The devices the Hardware pane has settings for, as Android's picker names them. */
enum class HardwareKind(private val labelText: () -> String) {
    Stylus({ Strings.stylus }),
    Keyboard({ Strings.keyboard });

    val label: String get() = labelText()
}

/**
 * Settings → Hardware, docked beside the canvas as Android's pane is: the device picker, then that
 * device's settings. Stylus holds whether a finger draws, whether the pen's motor vibrates, and
 * what the pen's buttons do. Keyboard lists the shortcuts in their groups; a row opens the
 * shortcut's key capture, a changed shortcut has its own reset, and Reset All asks first. It opens
 * on Keyboard, the device every desktop has.
 */
@Composable
internal fun HardwarePane(
    input: InputSettings,
    onInputChange: (InputSettings) -> Unit,
    bindings: KeyBindings,
    onEdit: (ShortcutAction) -> Unit,
    /** Opens the capture dialog for a hardware-key slot (0 until [PenButtonSettings.KEY_SLOTS]). */
    onEditPenKey: (Int) -> Unit,
    onReset: (ShortcutAction) -> Unit,
    onResetAll: () -> Unit,
    onClose: () -> Unit,
) {
    var kind by remember { mutableStateOf(HardwareKind.Keyboard) }
    ToolPane(title = Strings.hardware, onClose = onClose) {
        HardwareKindPicker(selected = kind, onSelect = { kind = it })
        when (kind) {
            HardwareKind.Stylus -> StylusSettings(input, onInputChange, onEditPenKey)
            HardwareKind.Keyboard -> KeyboardSettings(bindings, onEdit, onReset, onResetAll)
        }
    }
}

/**
 * Android's *Let a finger draw*. Off, a finger moves the page and two zoom it, and only a stylus or
 * mouse uses the drawing tools; on, a finger draws, erases and lassos too.
 *
 * Below it, the pen's own controls: its motor's feedback, the two barrel buttons Windows reports
 * as raw state, and the extra keys a pen's driver spells as keyboard presses, bound in three slots.
 */
@Composable
private fun ColumnScope.StylusSettings(
    input: InputSettings,
    onInputChange: (InputSettings) -> Unit,
    onEditPenKey: (Int) -> Unit,
) {
    PaneGroup(Strings.touch) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .toggleable(value = input.drawWithFinger, role = Role.Switch,
                    onValueChange = { onInputChange(input.copy(drawWithFinger = it)) })
                .testTag(HardwareTags.FingerDraws)
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(Strings.let_a_finger_draw, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(Strings.otherwise_a_finger_moves_the_page, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The row is the control; the switch only shows its state.
            Switch(checked = input.drawWithFinger, onCheckedChange = null)
        }
    }
    PaneGroup(Strings.stylus) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .toggleable(value = input.penHaptics, role = Role.Switch,
                    onValueChange = { onInputChange(input.copy(penHaptics = it)) })
                .testTag(HardwareTags.PenHaptics)
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(Strings.pen_haptics, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(Strings.pen_haptics_hint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The row is the control; the switch only shows its state.
            Switch(checked = input.penHaptics, onCheckedChange = null)
        }
    }
    Text(Strings.pen_key_hint, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp))
    PaneGroup(Strings.pen_buttons) {
        PenActionRow(
            label = Strings.pen_button,
            action = input.penButtons.barrel,
            tag = HardwareTags.PenBarrel,
            first = true,
            onPick = { onInputChange(input.copy(penButtons = input.penButtons.withBarrel(it))) },
        )
        PenActionRow(
            label = Strings.pen_second_button,
            action = input.penButtons.barrel2,
            tag = HardwareTags.PenBarrel2,
            first = false,
            onPick = { onInputChange(input.copy(penButtons = input.penButtons.withBarrel2(it))) },
        )
        repeat(PenButtonSettings.KEY_SLOTS) { slot ->
            PenKeyRow(
                slot = slot,
                binding = input.penButtons.key(slot),
                onCapture = { onEditPenKey(slot) },
                onPick = { onInputChange(input.copy(penButtons = input.penButtons.withKeyAction(slot, it))) },
            )
        }
    }
}

/**
 * One barrel button: the whole row picks what its press does, from the same list Android offers a
 * click count — the tools, the page's Undo and Redo, the lasso, and Nothing.
 */
@Composable
private fun PenActionRow(
    label: String,
    action: PenAction,
    tag: String,
    first: Boolean,
    onPick: (PenAction) -> Unit,
) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .clickable(role = Role.DropdownList, onClick = { open = true })
                .testTag(tag)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(action.label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(DocumentSymbols.ArrowDropDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        ScaledDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PenAction.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        open = false
                        onPick(entry)
                    },
                    modifier = Modifier.testTag(HardwareTags.penAction(entry)),
                )
            }
        }
    }
}

/**
 * One hardware-key slot: the key itself — click to capture it, Backspace in the dialog clears it —
 * and what pressing it does. The action waits for a key: an empty slot has nothing to pick for.
 */
@Composable
private fun PenKeyRow(
    slot: Int,
    binding: PenKeyBinding?,
    onCapture: () -> Unit,
    onPick: (PenAction) -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Strings.pen_hardware_key_value(slot + 1), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Keycap(
            binding?.label ?: Strings.not_set,
            modifier = Modifier
                .clickable(role = Role.Button, onClickLabel = Strings.capture_a_hardware_key, onClick = onCapture)
                .testTag(HardwareTags.penKey(slot)),
        )
        Spacer(Modifier.width(8.dp))
        Box {
            Row(
                modifier = Modifier
                    .clickable(enabled = binding != null, role = Role.DropdownList, onClick = { open = true })
                    .alpha(if (binding == null) DisabledAlpha else 1f)
                    .testTag(HardwareTags.penKeyAction(slot)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(binding?.action?.label ?: Strings.not_set, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(DocumentSymbols.ArrowDropDown, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            ScaledDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                PenAction.entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entry.label) },
                        onClick = {
                            open = false
                            onPick(entry)
                        },
                        modifier = Modifier.testTag(HardwareTags.penAction(entry)),
                    )
                }
            }
        }
    }
}

/** The look of a control that cannot be used yet, as the panes' fields dim theirs. */
private const val DisabledAlpha = 0.42f

@Composable
private fun ColumnScope.KeyboardSettings(
    bindings: KeyBindings,
    onEdit: (ShortcutAction) -> Unit,
    onReset: (ShortcutAction) -> Unit,
    onResetAll: () -> Unit,
) {
    Text(Strings.click_a_shortcut_to_change_it, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp))
    Column(Modifier.fillMaxWidth().testTag(HardwareTags.Shortcuts)) {
        ShortcutAction.groups.forEach { (group, actions) ->
            PaneGroup(group) {
                actions.forEachIndexed { index, action ->
                    ShortcutRow(
                        action = action,
                        chord = bindings.primary(action),
                        customized = bindings.isCustomized(action),
                        first = index == 0,
                        onEdit = { onEdit(action) },
                        onReset = { onReset(action) },
                    )
                }
            }
        }
    }
    OutlinedButton(
        onClick = onResetAll,
        enabled = bindings.isCustomized,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.align(Alignment.End).padding(top = 16.dp).testTag(HardwareTags.ResetAll),
    ) { Text(Strings.reset_all) }
}

/** Stylus and Keyboard as one linked pair of buttons, the way GTK groups a view switcher. */
@Composable
private fun HardwareKindPicker(selected: HardwareKind, onSelect: (HardwareKind) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(34.dp)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        HardwareKind.entries.forEachIndexed { index, kind ->
            if (index > 0) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier.fillMaxSize()
                        .background(if (kind == selected) LocalDesktopColors.current.selection else Color.Transparent)
                        .selectable(selected = kind == selected, role = Role.Tab, onClick = { onSelect(kind) })
                        .testTag(HardwareTags.kind(kind)),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (kind == HardwareKind.Stylus) SettingsSymbols.Stylus else SettingsSymbols.Keyboard,
                        contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(kind.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** One shortcut: its name, its keys (or Disabled), and a reset once it has been changed. */
@Composable
private fun ShortcutRow(
    action: ShortcutAction,
    chord: KeyChord?,
    customized: Boolean,
    first: Boolean,
    onEdit: () -> Unit,
    onReset: () -> Unit,
) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .clickable(role = Role.Button, onClickLabel = Strings.change_shortcut, onClick = onEdit)
            .testTag(HardwareTags.shortcut(action))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(action.label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (chord != null) {
            Keycap(chord.label)
        } else {
            Text(Strings.disabled, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The slot stays when empty, so every row's keys line up.
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (customized) {
                TooltipIconButton(Strings.reset_value_shortcut(action.label), onClick = onReset,
                    modifier = Modifier.size(32.dp).testTag(HardwareTags.reset(action))) {
                    Icon(SettingsSymbols.RestartAlt, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** A chord as it is printed on the keys: "Ctrl+Shift+Z" in a small raised box. */
@Composable
internal fun Keycap(label: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = if (large) 14.dp else 7.dp, vertical = if (large) 8.dp else 3.dp),
    ) {
        Text(label, style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}
