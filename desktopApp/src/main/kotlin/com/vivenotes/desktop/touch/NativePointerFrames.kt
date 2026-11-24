package com.vivenotes.desktop.touch

import androidx.compose.ui.input.pointer.PointerType
import androidx.ink.brush.InputToolType
import com.vivenotes.byteink.compose.InkInputEvent
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.desktop.pen.PenPointerIdentity
import com.vivenotes.desktop.pen.WindowsPenWatch
import java.awt.Window

/** Retains each device's identity and every real historical pressure sample. EDT only. */
internal class NativePointerFrames(
    private val window: Window,
    private val mouse: Boolean,
    private val touch: Boolean,
    private val emit: (TouchFrame) -> Unit,
    private val offset: () -> Pair<Double, Double> = { 0.0 to 0.0 },
    private val inverted: (Long) -> Boolean = { WindowsPenWatch.penIsInverted(it) },
) {
    private val active = mutableMapOf<Long, InkPointerSample>()

    fun accept(event: InkInputEvent) {
        when (event) {
            is InkInputEvent.Begin -> {
                val sample = event.sample
                if (sample.toolType == InputToolType.MOUSE && !mouse || sample.toolType == InputToolType.TOUCH && !touch) return
                // The pen's motor is addressed by the native id, which Compose's injected
                // pointer does not carry. Keep it before the frame hands the pointer on.
                if (sample.toolType == InputToolType.STYLUS) {
                    PenPointerIdentity.note(event.pointerId)
                    WindowsPenWatch.observePenSample(event.pointerId)
                }
                active[event.pointerId] = sample
                send(event.pointerId, TouchPhase.Down, sample)
            }
            is InkInputEvent.Move -> move(event.pointerId, event.sample)
            is InkInputEvent.Batch -> event.samples.forEach { move(event.pointerId, it) }
            is InkInputEvent.Finish -> {
                val previous = active.remove(event.pointerId) ?: return
                val sample = event.sample ?: previous
                // Touch sources historically retain the last move on up (Wayland has no up position).
                // Send the real final position first so Windows touch does not lose that observation.
                send(event.pointerId, TouchPhase.Move, sample)
                send(event.pointerId, TouchPhase.Up, sample)
                if (sample.toolType == InputToolType.STYLUS) WindowsPenWatch.clearPenSample()
            }
            is InkInputEvent.CancelPointer -> {
                if (event.pointerId in active) WindowsPenWatch.clearPenSample()
                if (event.pointerId in active) cancel()
            }
            InkInputEvent.Cancel -> {
                WindowsPenWatch.clearPenSample()
                cancel()
            }
            is InkInputEvent.Predict -> Unit
        }
    }

    fun cancel() {
        if (active.isEmpty()) return
        val time = active.values.maxOf { it.uptimeMillis }
        active.clear()
        emit(TouchFrame(time, emptyList(), cancelled = true, cancelledWindow = window))
    }

    private fun move(id: Long, sample: InkPointerSample) {
        if (id !in active) return
        active[id] = sample
        if (sample.toolType == InputToolType.STYLUS) WindowsPenWatch.observePenSample(id)
        send(id, TouchPhase.Move, sample)
    }

    private fun send(id: Long, phase: TouchPhase, sample: InkPointerSample) {
        val (dx, dy) = offset()
        val type = when (sample.toolType) {
            // ByteInk names every Windows pen sample STYLUS: Windows' own inverted flag is the
            // only way to tell the eraser tip, and flip-to-erase must work without a setting.
            InputToolType.STYLUS -> if (inverted(id)) PointerType.Eraser else PointerType.Stylus
            InputToolType.TOUCH -> PointerType.Touch
            else -> PointerType.Mouse
        }
        emit(TouchFrame(sample.uptimeMillis, listOf(TouchChange(
            id xor Long.MIN_VALUE, phase, window, sample.x + dx, sample.y + dy, type, sample.pressure ?: Float.NaN))))
    }
}
