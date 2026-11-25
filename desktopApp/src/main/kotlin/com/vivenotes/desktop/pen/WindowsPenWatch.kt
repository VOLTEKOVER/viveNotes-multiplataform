package com.vivenotes.desktop.pen

import com.vivenotes.diagnostics.DebugLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import javax.swing.SwingUtilities

/** PEN_FLAG_BARREL, the lower side button Windows reports for the pen. */
private const val PEN_FLAG_BARREL = 0x1
/** PEN_FLAG_INVERTED, set when the pen is turned over. */
private const val PEN_FLAG_INVERTED = 0x2
/** PEN_FLAG_ERASER, set by pens whose eraser tip is down instead of INVERTED. */
private const val PEN_FLAG_ERASER = 0x4
/** PEN_MASK_ROTATION: POINTER_PEN_INFO.rotation carries the pen's twist about its own axis. */
private const val PEN_MASK_ROTATION = 0x2
/** POINTER_MESSAGE_FLAG_SECONDBUTTON, the barrel button in a WM_POINTER wParam. */
private const val POINTER_SECONDBUTTON = 0x20L
/** The extended pointer buttons some pen drivers use for the upper barrel button. */
private const val POINTER_FOURTHBUTTON = 0x80L
private const val POINTER_FIFTHBUTTON = 0x100L

/** Whether Windows' PEN_FLAGS says the barrel button is held. Pure, so it can be tested. */
internal fun barrelPressed(flags: Int): Boolean = flags and PEN_FLAG_BARREL != 0

/** A barrel press is only the rising edge; holding it must not run the command again. */
internal fun barrelWentDown(previous: Boolean, flags: Int): Boolean = !previous && barrelPressed(flags)

/** Whether the eraser tip is down, for the flip-to-erase pointer type. Pure, so it can be tested. */
internal fun penInverted(flags: Int): Boolean = flags and (PEN_FLAG_INVERTED or PEN_FLAG_ERASER) != 0

/** The twist a pen reports, in degrees, or null when the device does not report one. */
internal fun penRotationDegrees(mask: Int, rotation: Int): Float? =
    if (mask and PEN_MASK_ROTATION != 0 && rotation in 0..359) rotation.toFloat() else null

/** The two pen buttons as Windows reports them: the flags and the message's button bits. */
internal data class PenButtons(val barrel: Boolean = false, val barrel2: Boolean = false)

internal fun penButtons(wParam: Long, flags: Int): PenButtons = PenButtons(
    barrel = barrelPressed(flags) || wParam and POINTER_SECONDBUTTON != 0L,
    barrel2 = wParam and (POINTER_FOURTHBUTTON or POINTER_FIFTHBUTTON) != 0L,
)

/** The button indices that just went down: 0 is the barrel, 1 is the upper button. */
internal fun penButtonEdges(previous: PenButtons, current: PenButtons): List<Int> = buildList {
    if (!previous.barrel && current.barrel) add(0)
    if (!previous.barrel2 && current.barrel2) add(1)
}

/** Pen data read while a WM_POINTER message is current, before ByteInk consumes it. */
internal data class PenInfoSnapshot(
    val flags: Int,
    val penMask: Int = 0,
    val rotationDegrees: Float? = null,
    val tiltX: Int = 0,
    val tiltY: Int = 0,
)

/**
 * Remembers which pen Windows is talking about and what its message said. ByteInk forwards pen
 * samples but not their PEN_FLAGS or twist, and nulls the WM_POINTER message for later hooks;
 * Compose then numbers injected pointers itself. This watch is installed after ByteInk's source, so
 * it sees the original message first. Compose frames use the cached flags for flip-to-erase and the
 * cached twist for a highlighter's nib angle.
 */
internal object WindowsPenWatch {
    /** The pen pointer id most recently seen, or -1 when no pen has been seen yet. */
    @Volatile var lastPenPointerId: Long = -1
        private set

    /** Called once when a pen button goes down: 0 is the barrel, 1 the upper button. */
    @Volatile var onButton: ((Int) -> Unit)? = null

    /** The lower barrel's legacy callback, for listeners that only know one button. */
    @Volatile var onBarrel: (() -> Unit)? = null

    /** Pen data captured while the WM_POINTER message was current, by native pointer id. */
    private val infoById = HashMap<Long, PenInfoSnapshot>()
    private val buttonsById = HashMap<Long, PenButtons>()

    @Synchronized
    internal fun noteFlags(pointerId: Long, flags: Int) {
        noteInfo(pointerId, PenInfoSnapshot(flags))
    }

    @Synchronized
    internal fun noteInfo(pointerId: Long, info: PenInfoSnapshot) {
        infoById[pointerId] = info
        lastPenPointerId = pointerId
    }

    @Synchronized
    internal fun forgetFlags(pointerId: Long) {
        infoById.remove(pointerId)
        buttonsById.remove(pointerId)
    }

    /** Dispatches any new button edges through the workspace, still inside this synchronized call. */
    @Synchronized
    private fun acceptButtons(pointerId: Long, wParam: Long, flags: Int) {
        val current = penButtons(wParam, flags)
        val previous = buttonsById[pointerId] ?: PenButtons()
        buttonsById[pointerId] = current
        lastPenPointerId = pointerId
        penButtonEdges(previous, current).forEach { index ->
            val source = if (index == 0) "barrel" else "second barrel"
            log?.event("input") {
                "pen $source down on pointer $pointerId wParam=0x${wParam.toString(16)} flags=0x${flags.toString(16)}"
            }
            onButton?.invoke(index)
            if (index == 0) onBarrel?.invoke()
        }
    }

    /**
     * Uses the data captured in the hook. GetPointerPenInfo only answers for the current pointer
     * message, which ByteInk has already replaced by the time a Compose frame reaches us.
     */
    fun observePenSample(pointerId: Long) {
        try {
            if (pointerId < 0L || pointerId > 0xffffL) return
            val info = synchronized(this) {
                lastPenPointerId = pointerId
                infoById[pointerId]
            } ?: return
            // A hook edge and a Compose frame can see the same press; the per-pointer button state
            // makes the second observation a no-op.
            acceptButtons(pointerId, 0L, info.flags)
        } catch (ignored: Throwable) { /* Never unwind through ByteInk's native hook callback. */ }
    }

    /** The tip lifted or the pen left: the next press is a new edge even with the button held. */
    fun clearPenSample(pointerId: Long = lastPenPointerId) {
        if (pointerId >= 0L) forgetFlags(pointerId)
    }

    /** True while the eraser tip is down, from the flags captured in the hook. */
    fun penIsInverted(pointerId: Long): Boolean = synchronized(this) {
        infoById[pointerId]?.flags?.let(::penInverted) ?: false
    }

    /** The pen's twist in degrees, or null when this pen does not report one. */
    fun penTwistDegrees(pointerId: Long): Float? = synchronized(this) {
        infoById[pointerId]?.rotationDegrees
    }

    private const val WH_GETMESSAGE = 3
    private const val PM_REMOVE = 1L
    private const val WM_POINTERUPDATE = 0x245
    private const val WM_POINTERDOWN = 0x246
    private const val WM_POINTERUP = 0x247
    private const val WM_POINTERENTER = 0x249
    private const val WM_POINTERLEAVE = 0x24a
    /** penFlags follows POINTER_INFO in POINTER_PEN_INFO, as ByteInk's own bridge lays it out. */
    private const val PEN_FLAGS_OFFSET = 96L
    private const val PEN_MASK_OFFSET = 100L
    private const val PRESSURE_OFFSET = 104L
    private const val ROTATION_OFFSET = 108L
    private const val TILT_X_OFFSET = 112L
    private const val TILT_Y_OFFSET = 116L

    private val runtime by lazy { Runtime() }
    @Volatile private var hook: MemorySegment? = null
    @Volatile private var log: DebugLog? = null
    private var sawPointer = false
    private var sawInfo = false
    private var sawAnyMessage = false
    private var sawPointerMessage = false
    private var sawCallback = false

    /** Installs the watch on this thread's message loop — the Compose thread. */
    fun attach(log: DebugLog): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (hook != null) return true
        return try {
            val installed = runtime.setHook(WH_GETMESSAGE, runtime.stub, MemorySegment.NULL,
                runtime.currentThreadId())
            if (installed.address() == 0L) {
                log.failure("input", "watch pen pointer ids", IllegalStateException("SetWindowsHookExW refused"))
                false
            } else {
                this.log = log
                hook = installed
                log.event("input") {
                    "pen pointer watch installed thread=${runtime.currentThreadId()} " +
                        "handle=0x${installed.address().toString(16)} stub=0x${runtime.stub.address().toString(16)}"
                }
                true
            }
        } catch (failure: Throwable) {
            log.failure("input", "watch pen pointer ids", failure)
            false
        }
    }

    /** Takes the watch down; the last id seen stays until a new one arrives. */
    fun detach() {
        val installed = hook ?: return
        hook = null
        log = null
        runtime.unhook(installed)
    }

    /** A WH_GETMESSAGE callback: never blocks a message, never unwinds through the native chain. */
    private fun observe(code: Int, removed: Long, address: Long): Long {
        try {
            if (!sawCallback) {
                sawCallback = true
                log?.event("input") { "pen watch callback code=$code removed=$removed address=0x${address.toString(16)}" }
            }
            if (code >= 0 && removed == PM_REMOVE && address != 0L) {
                val msg = MemorySegment.ofAddress(address).reinterpret(48)
                val message = msg.get(JAVA_INT, 8)
                if (!sawAnyMessage) {
                    sawAnyMessage = true
                    log?.event("input") { "pen watch first message=0x${message.toString(16)} code=$code" }
                }
                if (message == WM_POINTERUPDATE || message == WM_POINTERDOWN || message == WM_POINTERUP ||
                    message == WM_POINTERENTER || message == WM_POINTERLEAVE) {
                    val wParam = msg.get(JAVA_LONG, 16)
                    val id = (wParam and 0xffff).toInt()
                    val pointer = id.toLong()
                    if (!sawPointerMessage) {
                        sawPointerMessage = true
                        log?.event("input") { "pen watch pointer message=0x${message.toString(16)} id=$id" }
                    }
                    if (message == WM_POINTERLEAVE) {
                        forgetFlags(pointer)
                    } else {
                        // GetPointerType can fail once the message's pointer context has gone;
                        // a successful GetPointerPenInfo is proof enough that this pointer is a pen.
                        val info = runtime.penInfo(id)
                        if (info != null) {
                            noteInfo(pointer, info)
                            if (!sawPointer) {
                                sawPointer = true
                                log?.event("input") { "pen watch sees native pointer $id" }
                            }
                            if (!sawInfo) {
                                sawInfo = true
                                log?.event("input") {
                                    "pen flags for pointer $id = 0x${info.flags.toString(16)} " +
                                        "barrel=${barrelPressed(info.flags)} eraser=${penInverted(info.flags)} " +
                                        "twist=${info.rotationDegrees}"
                                }
                            }
                            acceptButtons(pointer, wParam, info.flags)
                        }
                    }
                }
            }
        } catch (ignored: Throwable) { /* No Java exception may unwind through a Win32 callback. */ }
        return try { runtime.callNextHook(MemorySegment.NULL, code, removed, address) } catch (ignored: Throwable) { 0L }
    }

    /** The calls this watch makes, kept for the process once the pen asks for them. */
    private class Runtime {
        private val arena = Arena.ofAuto()
        private val linker = Linker.nativeLinker()
        private val user32 = SymbolLookup.libraryLookup("user32", arena)
        private val kernel32 = SymbolLookup.libraryLookup("kernel32", arena)

        val stub: MemorySegment = linker.upcallStub(
            MethodHandles.privateLookupIn(WindowsPenWatch::class.java, MethodHandles.lookup())
                .findVirtual(WindowsPenWatch::class.java, "observe",
                    MethodType.methodType(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)).bindTo(WindowsPenWatch),
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG), arena)

        private val hSetHook: MethodHandle = linker.downcallHandle(user32.find("SetWindowsHookExW").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
        private val hUnhook: MethodHandle = linker.downcallHandle(user32.find("UnhookWindowsHookEx").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS))
        private val hCallNextHook: MethodHandle = linker.downcallHandle(user32.find("CallNextHookEx").orElseThrow(),
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG))
        private val hCurrentThreadId: MethodHandle = linker.downcallHandle(kernel32.find("GetCurrentThreadId").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT))
        private val hPenInfo: MethodHandle = linker.downcallHandle(user32.find("GetPointerPenInfo").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS))
        /** POINTER_PEN_INFO is 120 bytes; ByteInk allocates it 8-byte aligned for the same calls. */
        private val penInfo = arena.allocate(120L, 8)
        private var penInfoFailureLogged = false

        fun setHook(kind: Int, stub: MemorySegment, module: MemorySegment, thread: Int): MemorySegment =
            hSetHook.invokeWithArguments(kind, stub, module, thread) as MemorySegment

        fun unhook(hook: MemorySegment) { hUnhook.invokeWithArguments(hook) }

        fun callNextHook(next: MemorySegment, code: Int, wparam: Long, lparam: Long): Long =
            hCallNextHook.invokeWithArguments(next, code, wparam, lparam) as Long

        fun currentThreadId(): Int = hCurrentThreadId.invokeWithArguments() as Int

        /** The pen data behind [id], or null when Windows no longer knows the pointer. */
        fun penInfo(id: Int): PenInfoSnapshot? {
            val ok = hPenInfo.invokeWithArguments(id, penInfo) as Int != 0
            if (!ok && !penInfoFailureLogged) {
                penInfoFailureLogged = true
                log?.event("input") { "GetPointerPenInfo failed for pointer $id" }
            }
            if (!ok) return null
            val mask = penInfo.get(JAVA_INT, PEN_MASK_OFFSET)
            return PenInfoSnapshot(
                flags = penInfo.get(JAVA_INT, PEN_FLAGS_OFFSET),
                penMask = mask,
                rotationDegrees = penRotationDegrees(mask, penInfo.get(JAVA_INT, ROTATION_OFFSET)),
                tiltX = penInfo.get(JAVA_INT, TILT_X_OFFSET),
                tiltY = penInfo.get(JAVA_INT, TILT_Y_OFFSET),
            )
        }
    }
}
