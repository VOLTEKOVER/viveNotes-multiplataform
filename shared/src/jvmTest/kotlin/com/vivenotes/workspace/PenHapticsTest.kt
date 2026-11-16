package com.vivenotes.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The texture each tool writes with, and that the pen's motor is on until its owner turns it off.
 */
class PenHapticsTest {
    @Test
    fun thePenWritesWithTheFeelItsBrushNames() {
        assertEquals(PenHapticFeel.Ink, penHapticFeel(InkTool.Pen, InkBrush.Marker))
        assertEquals(PenHapticFeel.Pencil, penHapticFeel(InkTool.Pen, InkBrush.PressurePen))
        assertEquals(PenHapticFeel.ChiselMarker, penHapticFeel(InkTool.Pen, InkBrush.Calligraphy))
        assertEquals(PenHapticFeel.Ink, penHapticFeel(InkTool.Pen, InkBrush.DashedLine))
    }

    @Test
    fun theHighlighterAndTheEraserFeelLikeThemselves() {
        assertEquals(PenHapticFeel.Marker, penHapticFeel(InkTool.Highlighter, InkBrush.Marker))
        assertEquals(PenHapticFeel.Eraser, penHapticFeel(InkTool.Eraser, InkBrush.Marker))
    }

    @Test
    fun theLaserLaysNothingDownSoItHasNoTexture() {
        assertNull(penHapticFeel(InkTool.Laser, InkBrush.Marker))
        assertNull(penHapticFeel(null, InkBrush.Marker))
    }

    @Test
    fun theMotorsSwitchIsOnUntilTurnedOff() {
        assertTrue(InputSettings().penHaptics)
        assertFalse(InputSettings(penHaptics = false).penHaptics)
    }
}