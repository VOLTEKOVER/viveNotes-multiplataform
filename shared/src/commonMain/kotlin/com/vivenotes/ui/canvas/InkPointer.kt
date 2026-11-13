package com.vivenotes.ui.canvas

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import com.vivenotes.workspace.InkTool

// Pointer rules every platform's ink layer follows.

/** A resting hand does not have to lift between pen strokes, including while holding the ruler. */
internal suspend fun PointerInputScope.awaitEachInkGesture(block: suspend AwaitPointerEventScope.() -> Unit) {
    awaitPointerEventScope { while (true) block() }
}

/** The next ink-starting down: a pen first, else a mouse or a finger while no pen is pressed. */
internal suspend fun AwaitPointerEventScope.awaitInkDown(): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        val downs = event.changes.filter { it.changedToDown() }
        downs.firstOrNull { it.type == PointerType.Stylus || it.type == PointerType.Eraser }?.let { return it }
        downs.firstOrNull { it.type == PointerType.Mouse ||
            event.changes.none { change -> change.pressed && (change.type == PointerType.Stylus || change.type == PointerType.Eraser) }
        }?.let { return it }
    }
}

/** Only a pen measures pressure; a mouse or finger reports none rather than a synthetic value. */
internal fun PointerInputChange.measuredPenPressure(include: Boolean = true): Float? =
    pressure.takeIf { include && (type == PointerType.Stylus || type == PointerType.Eraser) && it.isFinite() && it in 0f..1f }

/**
 * The tool a stroke uses, given what is armed and what the pointer is: a pen held upside down
 * erases — flipping it is the fastest way to a correction, and it needs no setting — so an eraser
 * pointer always erases, whatever is armed; any other pointer uses the armed tool.
 *
 * Pure, so the rule is testable without a device: no test framework can synthesize a pen.
 */
internal fun strokeTool(tool: InkTool?, pointerType: PointerType): InkTool? =
    if (pointerType == PointerType.Eraser) InkTool.Eraser else tool
