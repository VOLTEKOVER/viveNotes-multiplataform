package com.vivenotes.ui.canvas

import androidx.compose.ui.input.pointer.PointerType
import com.vivenotes.workspace.InkTool
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which tool a pointer writes with: the one in hand, unless the pointer is an eraser — a pen held
 * upside down erases whatever is armed, without a setting.
 */
class InkPointerTest {
    @Test
    fun anEraserPointerErasesWhateverIsArmed() {
        for (tool in InkTool.entries + listOf(null)) {
            assertEquals(InkTool.Eraser, strokeTool(tool, PointerType.Eraser))
        }
    }

    @Test
    fun everyOtherPointerKeepsTheArmedTool() {
        val pointerTypes = listOf(PointerType.Stylus, PointerType.Mouse, PointerType.Touch, PointerType.Unknown)
        for (type in pointerTypes) {
            for (tool in InkTool.entries + listOf(null)) {
                assertEquals(tool, strokeTool(tool, type))
            }
        }
    }
}