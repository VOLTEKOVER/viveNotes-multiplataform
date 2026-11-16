package com.vivenotes.desktop.pen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How the driver picks a waveform from what a pen's motor says it can play: a writing texture
 * prefers the tool's own and falls back to plain ink, while a command click is only ever an
 * impulse — a continuous waveform would hum until stopped.
 */
class WindowsPenHapticsSelectionTest {
    @Test
    fun theWritingTextureIsTheFeelsOwnOrPlainInk() {
        assertEquals(7, inkFeedbackWaveform(supported = listOf(7, 9), wanted = 7, plain = 9))
        assertEquals(9, inkFeedbackWaveform(supported = listOf(9), wanted = 7, plain = 9))
        assertNull(inkFeedbackWaveform(supported = emptyList(), wanted = 7, plain = 9))
        assertNull(inkFeedbackWaveform(supported = listOf(3), wanted = 7, plain = 9))
    }

    @Test
    fun aCommandClicksWithAnImpulseAndNeverAContinuousWaveform() {
        assertEquals(1, commandFeedbackWaveform(supported = listOf(1, 2), click = 1, press = 2))
        assertEquals(2, commandFeedbackWaveform(supported = listOf(2), click = 1, press = 2))
        assertNull(commandFeedbackWaveform(supported = emptyList(), click = 1, press = 2))
        assertNull(commandFeedbackWaveform(supported = listOf(3), click = 1, press = 2))
    }
}