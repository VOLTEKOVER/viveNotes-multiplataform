package com.vivenotes.workspace

import com.vivenotes.localization.Strings

/**
 * What one press of a pen's controls does, as Android's StylusAction does it: the commands a pen
 * barrel or one of its keys can run, chosen in Settings → Hardware → Stylus.
 *
 * Serialized by name, as Android serializes it, so renaming an entry is a stored-value change:
 * an old file naming the entry that used to exist decodes to the field's default rather than to
 * its replacement.
 */
enum class PenAction(private val labelText: () -> String) {
    /**
     * Unbound. A key bound to this is left unclaimed, so it falls through to the keyboard, and it
     * is the answer for a press nothing has asked for.
     */
    None({ Strings.pen_action_none }),
    TogglePenEraser({ Strings.pen_action_toggle_pen_eraser }),
    CyclePens({ Strings.pen_action_cycle_pens }),
    Pen1({ Strings.pen_action_pen_1 }),
    Pen2({ Strings.pen_action_pen_2 }),
    Pen3({ Strings.pen_action_pen_3 }),
    Highlighter({ Strings.pen_action_highlighter }),
    Eraser({ Strings.pen_action_eraser }),
    Lasso({ Strings.pen_action_lasso }),
    Undo({ Strings.pen_action_undo }),
    Redo({ Strings.pen_action_redo });

    val label: String get() = labelText()

    companion object {
        /** The entry a stored name means, or null for one this version has never heard of. */
        fun decode(name: String): PenAction? = entries.firstOrNull { it.name == name }
    }
}

/**
 * One hardware key bound to a pen action: the Compose key code that identifies the physical key
 * on every platform, the name the keyboard gives it for the Hardware pane, and the action the
 * key runs. Pens of every brand differ in what they send; the code is whatever arrives.
 */
data class PenKeyBinding(val code: Long, val label: String, val action: PenAction) {
    /**
     * `"code|label|Action"`, split back apart at the first and last bar so a label may itself
     * contain bars — some keyboards name keys that way.
     */
    fun encode(): String = "$code|$label|$action"

    companion object {
        /** The binding a stored line means, or null for one that is not a binding at all. */
        fun decode(text: String): PenKeyBinding? {
            val first = text.indexOf('|')
            val last = text.lastIndexOf('|')
            if (first <= 0 || last <= first) return null
            val code = text.substring(0, first).toLongOrNull() ?: return null
            val action = PenAction.decode(text.substring(last + 1)) ?: return null
            return PenKeyBinding(code, text.substring(first + 1, last), action)
        }
    }
}

/**
 * What the pen's buttons and keys do — Android's StylusButtonMap, as the desktop can have it.
 *
 * Windows reports a pen's buttons as raw button state rather than counting the clicks the pen's
 * own firmware times, so the two barrel buttons are two fixed rows rather than three click
 * counts. The extra keys many pens' drivers send as keyboard presses go in three slots: what the
 * driver spells decides the key, and the slots only choose what it does.
 */
data class PenButtonSettings(
    /** The lower barrel button: the side button selects by default, as the stylus key does. */
    val barrel: PenAction = PenAction.Lasso,
    /** The upper barrel button, when there is one: Windows reports it as the tertiary button. */
    val barrel2: PenAction = PenAction.None,
    /** The pen's extra keys, in the Hardware pane's three slots; null is a slot with no key. */
    val keys: List<PenKeyBinding?> = List(KEY_SLOTS) { null },
) {
    /** The binding in [slot] (0 until [KEY_SLOTS]), or null when that slot has no key. */
    fun key(slot: Int): PenKeyBinding? = keys.getOrNull(slot)

    /**
     * The binding the pressed key's [code] has, in whichever slot holds it. Code 0 is Compose's
     * "unknown key", which no press can mean.
     */
    fun bindingFor(code: Long): PenKeyBinding? {
        if (code == 0L) return null
        return keys.asSequence().mapNotNull { it }.firstOrNull { it.code == code }
    }

    /** The other slot [code] is bound in, if any, for the capture dialog to ask about first. */
    fun keyConflict(slot: Int, code: Long): Int? =
        keys.indexOfFirst { it?.code == code }.takeIf { it >= 0 && it != slot }

    /**
     * Sets [slot]'s key. Taking a key another slot has moves it: the key stops doing what it did
     * where it was and does what this slot asks, so one key is bound in one place only.
     */
    fun withKey(slot: Int, binding: PenKeyBinding?): PenButtonSettings {
        val next = keys.toMutableList()
        next[slot] = binding
        if (binding != null) for (index in next.indices) {
            if (index != slot && next[index]?.code == binding.code) next[index] = null
        }
        return copy(keys = next)
    }

    /** Sets [slot]'s action, keeping its key; a slot with no key has nothing to set. */
    fun withKeyAction(slot: Int, action: PenAction): PenButtonSettings {
        val current = key(slot) ?: return this
        return withKey(slot, current.copy(action = action))
    }

    fun withBarrel(action: PenAction): PenButtonSettings = copy(barrel = action)

    fun withBarrel2(action: PenAction): PenButtonSettings = copy(barrel2 = action)

    companion object {
        /** The Hardware pane's hardware-key slots, as many as Android's click counts. */
        const val KEY_SLOTS = 3
    }
}

/**
 * Pen hardware a platform sees outside Compose's pointer stream. Windows reads the barrel
 * button from a WM_POINTER flag ByteInk does not forward, so its driver calls here with the
 * press; the workspace answers with whatever its Hardware pane bound to that button.
 */
object PenHardwareEvents {
    /** Set while the workspace is on screen; null when no workspace is listening. */
    @Volatile var onBarrel: (() -> Unit)? = null
}

/**
 * The action a newly pressed barrel button runs, from the button that just went down: the
 * secondary button the barrel's action, the tertiary the second barrel's. A button that was
 * already held runs nothing — holding barrel and tip together is one press of each, not a repeat.
 *
 * Pure, so the rules are testable without a device: no test framework can synthesize a pen.
 */
internal fun penBarrelAction(
    secondaryWas: Boolean,
    tertiaryWas: Boolean,
    secondary: Boolean,
    tertiary: Boolean,
    buttons: PenButtonSettings,
): PenAction? = when {
    secondary && !secondaryWas -> buttons.barrel
    tertiary && !tertiaryWas -> buttons.barrel2
    else -> null
}

/**
 * The tool a bound action arms, given what is in hand — or null for the actions that arm nothing.
 * Android's StylusAction.toolFrom, over the desktop's tools.
 *
 * Null is three things that come to the same: [PenAction.None] is unbound, [PenAction.Undo] and
 * [PenAction.Redo] act on the page rather than on the hand, and [PenAction.Lasso] arms the
 * desktop's object lasso rather than a tool, which [WorkspaceState.armPenAction] handles first.
 *
 * [PenAction.Lasso] is the barrel's default, so the side button selects objects.
 * [PenAction.TogglePenEraser] is the one entry with a rule rather than an answer: with a pen in
 * hand it reaches for the eraser, with anything else in hand it reaches back for [currentPen] —
 * the pen last in hand — so writing in blue, erasing, and clicking again carries on in blue.
 * [PenAction.CyclePens] is the other rule — it walks the three pens and wraps.
 */
internal fun inkToolFrom(action: PenAction, current: InkTool?, currentPen: Int): Pair<InkTool, Int>? = when (action) {
    PenAction.None, PenAction.Undo, PenAction.Redo, PenAction.Lasso -> null
    PenAction.TogglePenEraser ->
        if (current == InkTool.Pen) InkTool.Eraser to currentPen else InkTool.Pen to currentPen
    PenAction.CyclePens -> InkTool.Pen to if (current == InkTool.Pen) (currentPen + 1) % 3 else 0
    PenAction.Pen1 -> InkTool.Pen to 0
    PenAction.Pen2 -> InkTool.Pen to 1
    PenAction.Pen3 -> InkTool.Pen to 2
    PenAction.Highlighter -> InkTool.Highlighter to currentPen
    PenAction.Eraser -> InkTool.Eraser to currentPen
}
