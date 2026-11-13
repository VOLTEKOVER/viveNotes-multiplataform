package com.vivenotes.workspace

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The pen's own haptic motor, as the workspace drives it — Windows offers it through the pen
 * device stack, the same one that feeds Windows 11's ink on a Surface Slim Pen 2. A texture while
 * the pen writes and a click when one of its commands runs; a pen without a motor, or a machine
 * that cannot reach one, offers nothing, and the pen keeps writing.
 */
interface PenHaptics {
    /**
     * Begins the writing texture of [feel] on the pen behind [pointerId] — the id the platform's
     * pointer events carry — and holds it until [inkEnd].
     */
    fun inkStart(pointerId: Long, feel: PenHapticFeel)

    /** Ends the texture: the pen lifting, the gesture cancelled, the layer going away. */
    fun inkEnd()

    /** The pen's own click, when one of its buttons or keys has run a command. */
    fun commandClick()
}

/** What the motor plays while writing, one waveform per tool and brush. */
enum class PenHapticFeel { Ink, Pencil, ChiselMarker, Marker, Eraser }

/**
 * The texture a stroke of [tool] in [brush] writes with: the pen as plain ink, the pressure pen
 * as pencil, calligraphy as a chisel marker, the highlighter as a broad marker, and the eraser as
 * itself. A tool that lays nothing down — the laser — and no tool at all have nothing to feel.
 */
fun penHapticFeel(tool: InkTool?, brush: InkBrush): PenHapticFeel? = when (tool) {
    InkTool.Eraser -> PenHapticFeel.Eraser
    InkTool.Pen -> when (brush) {
        InkBrush.Marker -> PenHapticFeel.Ink
        InkBrush.PressurePen -> PenHapticFeel.Pencil
        InkBrush.Calligraphy -> PenHapticFeel.ChiselMarker
        InkBrush.DashedLine -> PenHapticFeel.Ink
    }
    InkTool.Highlighter -> PenHapticFeel.Marker
    InkTool.Laser, null -> null
}

/** Where the pen's motor driver is, when this machine has one to offer. */
val LocalPenHaptics = staticCompositionLocalOf<PenHaptics?> { null }
