package com.vivenotes.desktop.pen

import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.workspace.PenHapticFeel
import com.vivenotes.workspace.PenHaptics
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

/**
 * The waveform a stroke plays: the feel's own when the motor has it, else plain ink — the least
 * the pen can hum — and nothing when the motor has neither.
 */
internal fun inkFeedbackWaveform(supported: List<Int>, wanted: Int, plain: Int): Int? =
    if (wanted in supported) wanted else if (plain in supported) plain else null

/**
 * The waveform a command clicks with: the click itself, else a press — both impulses that end on
 * their own — and nothing when the motor has neither. A continuous waveform would hum until
 * stopped, which is no way to click.
 */
internal fun commandFeedbackWaveform(supported: List<Int>, click: Int, press: Int): Int? =
    if (click in supported) click else if (press in supported) press else null

/** An interface id's tail: Java's bytes are signed, Windows' ids are not. */
private fun iidBytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

// WinRT interface slots, after IUnknown's three and IInspectable's three: the dump of the
// platform's own metadata names what each interface's sixth method onwards is.
private const val SLOT_QUERY_INTERFACE = 0L
private const val SLOT_RELEASE = 2L
private const val SLOT_PEN_FROM_POINTER_ID = 6L // IPenDeviceStatics::GetFromPointerId
private const val SLOT_PEN_HAPTICS_CONTROLLER = 6L // IPenDevice2::SimpleHapticsController
private const val SLOT_SUPPORTED_FEEDBACK = 7L // ISimpleHapticsController::SupportedFeedback
private const val SLOT_STOP_FEEDBACK = 12L // ISimpleHapticsController::StopFeedback
private const val SLOT_SEND_FEEDBACK = 13L // ISimpleHapticsController::SendHapticFeedback
private const val SLOT_FEEDBACK_WAVEFORM = 6L // ISimpleHapticsControllerFeedback::Waveform
private const val SLOT_VECTOR_GET_AT = 6L // IVectorView::GetAt
private const val SLOT_VECTOR_SIZE = 7L // IVectorView::Size
private const val SLOT_KNOWN_CLICK = 6L // IKnownStatics: Click, Buzz, Rumble, Press, Release…
private const val SLOT_KNOWN_PRESS = 9L
private const val SLOT_KNOWN_CHISEL_MARKER = 7L // IKnownStatics2, alphabetical: Brush, ChiselMarker, Eraser…
private const val SLOT_KNOWN_ERASER = 8L
private const val SLOT_KNOWN_INK = 12L
private const val SLOT_KNOWN_MARKER = 13L
private const val SLOT_KNOWN_PENCIL = 14L

/**
 * Drives the pen's motor over Windows' own pen-device stack: the controller Windows 11's ink uses
 * for a Surface Slim Pen 2. Every call stays on the Compose thread, where strokes and commands
 * arrive; the motor's links are resolved once per pen and kept until another pen takes the page,
 * and a pen that resolves to nothing is remembered as nothing rather than asked again — a pen
 * without a motor stays silent without a native call per stroke.
 */
internal class WindowsPenHaptics private constructor(
    private val native: Native,
    private val log: DebugLog,
) : PenHaptics {

    companion object {
        /** Null when this machine cannot drive a pen's motor — the workspace writes on without it. */
        fun create(log: DebugLog): WindowsPenHaptics? {
            if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return null
            return try {
                val haptics = WindowsPenHaptics(Native(), log)
                log.event("input") { "pen haptics driver ready" }
                haptics
            } catch (failure: Throwable) {
                log.failure("input", "start pen haptics", failure)
                null
            }
        }
    }

    private var closed = false
    private var vibrating = false
    /** The pointer id [resolved] was asked about — a pen, or the failure not to ask again. */
    private var resolvedId = -1L
    private var resolved: Pen? = null

    override fun inkStart(pointerId: Long, feel: PenHapticFeel) {
        // Compose numbers injected pointers itself; the native id the motor needs is kept at
        // the first pen sample. Fall back to the gesture's id only before one has arrived.
        val nativeId = PenPointerIdentity.stylusId.takeIf { it > 0L } ?: pointerId
        val pen = penFor(nativeId) ?: return
        val wanted = native.waveformOf(feel)
        val waveform = inkFeedbackWaveform(pen.waveforms, wanted, native.ink)
        log.event("input") {
            "ink haptics compose=$pointerId native=$nativeId feel=$feel wanted=$wanted chosen=$waveform"
        }
        if (waveform == null) return
        val feedback = pen.feedbackOf(waveform) ?: return
        val result = native.sendFeedback(pen.controller, feedback)
        log.event("input") { "ink haptics sent result=$result" }
        if (result == 0) vibrating = true
    }

    override fun inkEnd() {
        if (!vibrating) return
        vibrating = false
        resolved?.let { pen -> native.stopFeedback(pen.controller) }
    }

    override fun commandClick() {
        // The pen behind the pressed key: the one Windows last spoke of, which stays valid while
        // the pen is in range — a pen that left leaves its clicks silent.
        val id = PenPointerIdentity.stylusId.takeIf { it > 0L } ?: WindowsPenWatch.lastPenPointerId
        log.event("input") { "pen key click pointer=$id" }
        if (id <= 0) return
        val pen = penFor(id) ?: return
        val waveform = commandFeedbackWaveform(pen.waveforms, native.click, native.press)
        log.event("input") { "pen key click chosen waveform=$waveform" }
        if (waveform == null) return
        val feedback = pen.feedbackOf(waveform) ?: return
        native.sendFeedback(pen.controller, feedback)
    }

    /** Releases the pen and everything Windows gave to reach it. */
    fun close() {
        if (closed) return
        closed = true
        val pen = resolved
        resolved = null
        resolvedId = -1
        if (vibrating) {
            vibrating = false
            pen?.let { native.stopFeedback(it.controller) }
        }
        pen?.release(native)
        WindowsPenWatch.onBarrel = null
        WindowsPenWatch.detach()
        native.close()
    }

    /** The pen behind [pointerId], keeping the last answer for the id it was for. */
    private fun penFor(pointerId: Long): Pen? {
        if (closed) return null
        if (pointerId == resolvedId) return resolved
        resolved?.release(native)
        resolvedId = pointerId
        resolved = try { resolve(pointerId) } catch (failure: Throwable) {
            log.failure("input", "resolve pen motor for pointer $pointerId", failure)
            null
        }
        log.event("input") {
            "pen motor for pointer $pointerId: ${if (resolved == null) "none (${native.diagnosis})" else "linked"}"
        }
        return resolved
    }

    /** PenDevice.GetFromPointerId → the pen's controller, and what the controller can play. */
    private fun resolve(pointerId: Long): Pen? {
        native.diagnosis = "start"
        val device = native.fromPointerId(pointerId) ?: return null
        val pen2 = native.queryInterface(device, native.penDevice2Id) ?: return null
        native.diagnosis = "IPenDevice2"
        val controller = native.readObject(pen2, SLOT_PEN_HAPTICS_CONTROLLER) ?: return null
        native.diagnosis = "controller"
        val vector = native.readObject(controller, SLOT_SUPPORTED_FEEDBACK) ?: return null
        native.diagnosis = "supported feedback"
        val size = native.vectorSize(vector)
        if (size == 0) { native.diagnosis = "no supported feedback"; return null }
        val feedbacks = ArrayList<MemorySegment>(size)
        val waveforms = ArrayList<Int>(size)
        for (index in 0 until size) {
            val feedback = native.elementAt(vector, index) ?: continue
            val waveform = native.feedbackWaveform(feedback) ?: continue
            feedbacks.add(feedback)
            waveforms.add(waveform)
        }
        return Pen(controller, feedbacks, waveforms, feedbacks.asReversed() + listOf(controller, pen2, device))
    }

    /** One pen's haptic chain: every link ref-counted by its getter, released together. */
    private class Pen(
        val controller: MemorySegment,
        val feedbacks: List<MemorySegment>,
        val waveforms: List<Int>,
        private val owned: List<MemorySegment>,
    ) {
        fun feedbackOf(waveform: Int): MemorySegment? = feedbacks.getOrNull(waveforms.indexOf(waveform))
        fun release(native: Native) { owned.forEach { native.release(it) } }
    }

    /** The WinRT calls and objects the motor needs, held for as long as the driver lives. */
    private class Native : AutoCloseable {
        val arena = Arena.ofAuto()
        private val linker = Linker.nativeLinker()
        private val combase = SymbolLookup.libraryLookup("combase", arena)

        private val createString: MethodHandle = linker.downcallHandle(combase.find("WindowsCreateString").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
        private val getFactory: MethodHandle = linker.downcallHandle(combase.find("RoGetActivationFactory").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        private val initialize: MethodHandle = linker.downcallHandle(combase.find("RoInitialize").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT))

        // HRESULT-returning vtable signatures shared by every interface called below.
        private val fQueryInterface = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
        private val fGetProperty = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
        private val fGetAt = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
        private val fThisOnly = FunctionDescriptor.of(JAVA_INT, ADDRESS)
        private val fSendFeedback = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
        // Object pointers are read through a window big enough to reach any slot called.
        private val vtWindow = MemoryLayout.sequenceLayout(32, ADDRESS)

        /** IPenDevice2, the interface the controller hangs from. */
        val penDevice2Id = guid(34067239, 32696, 21862, iidBytes(140, 52, 248, 52, 32, 55, 183, 249))

        val penDeviceStatics: MemorySegment
        /** Where the last resolve stopped, for the debug log when a pen has no motor. */
        var diagnosis: String = ""
        val click: Int
        val press: Int
        val ink: Int
        val pencil: Int
        val marker: Int
        val chiselMarker: Int
        val eraser: Int
        private val owned = mutableListOf<MemorySegment>()

        init {
            // This thread — the one every later call stays on. One Windows has already given an
            // apartment of its own (RPC_E_CHANGED_MODE) keeps it: the factories answer either way.
            invokeInt(initialize, 0)
            penDeviceStatics = activationFactory("Windows.Devices.Input.PenDevice",
                guid(2650521089L.toInt(), 2406, 20864, iidBytes(188, 180, 184, 80, 96, 227, 148, 121)))
                ?: throw IllegalStateException("Windows offers no PenDevice factory")
            owned.add(penDeviceStatics)
            val known = activationFactory("Windows.Devices.Haptics.KnownSimpleHapticsControllerWaveforms",
                guid(1029144311, 19694, 4582, iidBytes(181, 53, 0, 27, 220, 6, 171, 59)))
                ?: throw IllegalStateException("Windows offers no known haptic waveforms")
            owned.add(known)
            val known2 = queryInterface(known,
                guid(2815577127L.toInt(), 47005.toShort(), 20746, iidBytes(191, 121, 255, 109, 73, 23, 62, 29)))
                ?: throw IllegalStateException("Windows offers no second waveforms interface")
            owned.add(known2)
            click = readConstant(known, SLOT_KNOWN_CLICK, "Click")
            press = readConstant(known, SLOT_KNOWN_PRESS, "Press")
            chiselMarker = readConstant(known2, SLOT_KNOWN_CHISEL_MARKER, "ChiselMarkerContinuous")
            eraser = readConstant(known2, SLOT_KNOWN_ERASER, "EraserContinuous")
            ink = readConstant(known2, SLOT_KNOWN_INK, "InkContinuous")
            marker = readConstant(known2, SLOT_KNOWN_MARKER, "MarkerContinuous")
            pencil = readConstant(known2, SLOT_KNOWN_PENCIL, "PencilContinuous")
        }

        /** Reads a waveform constant the platform names, refusing to run on a Windows without it. */
        private fun readConstant(statics: MemorySegment, slot: Long, name: String): Int =
            readU16(statics, slot) ?: throw IllegalStateException("Windows names no waveform $name")

        override fun close() {
            owned.asReversed().forEach { release(it) }
            // The arena came from Arena.ofAuto and frees itself; only objects are ours to release.
        }

        fun waveformOf(feel: PenHapticFeel): Int = when (feel) {
            PenHapticFeel.Ink -> ink
            PenHapticFeel.Pencil -> pencil
            PenHapticFeel.ChiselMarker -> chiselMarker
            PenHapticFeel.Marker -> marker
            PenHapticFeel.Eraser -> eraser
        }

        /** PenDevice.GetFromPointerId: the pen behind an id, while that pen is in range. */
        fun fromPointerId(pointerId: Long): MemorySegment? {
            val out = arena.allocate(8L)
            val hr = call(penDeviceStatics, SLOT_PEN_FROM_POINTER_ID, fGetAt, pointerId.toInt(), out)
            if (hr != 0) {
                diagnosis = "GetFromPointerId hr=0x${hr.toUInt().toString(16)}"
                return null
            }
            val device = readObjectAt(out)
            if (device == null) diagnosis = "GetFromPointerId returned no device"
            return device
        }

        fun queryInterface(target: MemorySegment, iid: MemorySegment): MemorySegment? {
            val out = arena.allocate(8L)
            val hr = call(target, SLOT_QUERY_INTERFACE, fQueryInterface, iid, out)
            if (hr != 0) {
                diagnosis = "QueryInterface hr=0x${hr.toUInt().toString(16)}"
                return null
            }
            val result = readObjectAt(out)
            if (result == null) diagnosis = "QueryInterface returned no IPenDevice2"
            return result
        }

        fun readObject(target: MemorySegment, slot: Long): MemorySegment? {
            val out = arena.allocate(8L)
            val hr = call(target, slot, fGetProperty, out)
            if (hr != 0) {
                diagnosis = "property slot $slot hr=0x${hr.toUInt().toString(16)}"
                return null
            }
            val result = readObjectAt(out)
            if (result == null) diagnosis = when (slot) {
                SLOT_PEN_HAPTICS_CONTROLLER -> "no motor: pen not connected over Bluetooth"
                else -> "property slot $slot returned null"
            }
            return result
        }

        fun readU16(target: MemorySegment, slot: Long): Int? {
            val out = arena.allocate(2L)
            if (call(target, slot, fGetProperty, out) != 0) return null
            return out.get(JAVA_SHORT, 0).toInt() and 0xffff
        }

        fun vectorSize(vector: MemorySegment): Int {
            val out = arena.allocate(4L)
            if (call(vector, SLOT_VECTOR_SIZE, fGetProperty, out) != 0) return 0
            return out.get(JAVA_INT, 0)
        }

        fun elementAt(vector: MemorySegment, index: Int): MemorySegment? {
            val out = arena.allocate(8L)
            if (call(vector, SLOT_VECTOR_GET_AT, fGetAt, index, out) != 0) return null
            return readObjectAt(out)
        }

        fun feedbackWaveform(feedback: MemorySegment): Int? = readU16(feedback, SLOT_FEEDBACK_WAVEFORM)

        fun sendFeedback(controller: MemorySegment, feedback: MemorySegment): Int =
            call(controller, SLOT_SEND_FEEDBACK, fSendFeedback, feedback)

        fun stopFeedback(controller: MemorySegment): Int = call(controller, SLOT_STOP_FEEDBACK, fThisOnly)

        fun release(target: MemorySegment): Int = call(target, SLOT_RELEASE, fThisOnly)

        /** Calls a vtable slot through the window that reads the object's table from. */
        private fun call(target: MemorySegment, slot: Long, signature: FunctionDescriptor, vararg arguments: Any?): Int {
            val vtable = target.get(ADDRESS.withTargetLayout(vtWindow), 0)
            val handle = linker.downcallHandle(vtable.getAtIndex(ADDRESS, slot), signature)
            val all = arrayOfNulls<Any?>(arguments.size + 1)
            all[0] = target
            System.arraycopy(arguments, 0, all, 1, arguments.size)
            return handle.invokeWithArguments(*all) as Int
        }

        private fun readObjectAt(out: MemorySegment): MemorySegment? =
            out.get(ADDRESS.withTargetLayout(vtWindow), 0).takeIf { it.address() != 0L }

        /** An HSTRING-backed activation factory, kept ref-counted in [owned] until [close]. */
        private fun activationFactory(runtimeClass: String, iid: MemorySegment): MemorySegment? {
            val chars = arena.allocate((runtimeClass.length + 1) * 2L)
            for (index in runtimeClass.indices) chars.setAtIndex(JAVA_CHAR, index.toLong(), runtimeClass[index])
            val hstringOut = arena.allocate(8L)
            if (invokeInt(createString, chars, runtimeClass.length, hstringOut) != 0) return null
            val factoryOut = arena.allocate(8L)
            if (invokeInt(getFactory, hstringOut.get(ADDRESS, 0), iid, factoryOut) != 0) return null
            return readObjectAt(factoryOut)
        }

        private fun invokeInt(handle: MethodHandle, vararg arguments: Any?): Int =
            handle.invokeWithArguments(*arguments) as Int

        private fun guid(d1: Int, d2: Short, d3: Short, d4: ByteArray): MemorySegment {
            val value = arena.allocate(16L)
            value.set(JAVA_INT, 0, d1)
            value.set(JAVA_SHORT, 4, d2)
            value.set(JAVA_SHORT, 6, d3)
            d4.forEachIndexed { index, byte -> value.set(JAVA_BYTE, 8L + index, byte) }
            return value
        }
    }
}
