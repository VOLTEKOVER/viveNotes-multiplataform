package com.vivenotes.desktop.pen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPenWatchTest {
    @Test fun barrelFlagIsReadAlongsideTheOtherPenFlags() {
        // PEN_FLAG_INVERTED and PEN_FLAG_ERASER must not be mistaken for the barrel button.
        assertFalse(barrelPressed(0))
        assertFalse(barrelPressed(0x2))
        assertFalse(barrelPressed(0x4))
        assertTrue(barrelPressed(0x1))
        assertTrue(barrelPressed(0x1 or 0x2 or 0x4))
    }

    @Test fun eitherPenEraserFlagMeansFlipToErase() {
        assertFalse(penInverted(0))
        assertFalse(penInverted(0x1))
        assertTrue(penInverted(0x2))
        assertTrue(penInverted(0x4))
        assertTrue(penInverted(0x1 or 0x4))
    }

    @Test fun flipToEraseReadsTheFlagsCapturedFromTheNativeMessage() {
        WindowsPenWatch.noteFlags(77L, 0x4)
        try {
            assertTrue(WindowsPenWatch.penIsInverted(77L))
        } finally {
            WindowsPenWatch.forgetFlags(77L)
        }
        assertFalse(WindowsPenWatch.penIsInverted(77L))
    }

    @Test fun onlyTheRisingEdgeOfABarrelPressRunsACommand() {
        assertTrue(barrelWentDown(false, 0x1))
        assertFalse(barrelWentDown(true, 0x1))
        assertFalse(barrelWentDown(false, 0x2))
        // A new contact starts from false again even if the button never came up.
        assertTrue(barrelWentDown(false, 0x1 or 0x4))
    }
}
