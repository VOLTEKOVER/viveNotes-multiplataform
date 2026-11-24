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

/** Whether Windows' PEN_FLAGS says the barrel button is held. Pure, so it can be tested. */
internal fun barrelPressed(flags: Int): Boolean = flags and PEN_FLAG_BARREL != 0

/** A barrel press is only the rising edge; holding it must not run the command again. */
internal fun barrelWentDown(previous: Boolean, flags: Int): Boolean = !previous && barrelPressed(flags)

/** Whether the eraser tip is down, for the flip-to-erase pointer type. Pure, so it can be tested. */
internal fun penInverted(flags: Int): Boolean = flags and (PEN_FLAG_INVERTED or PEN_FLAG_ERASER) != 0

/**
 * Remembers which pen Windows is talking about, and reads the flags the Compose pointer stream
 * cannot carry. ByteInk forwards a pen's samples but not its PEN_FLAGS, and nulls the WM_POINTER
 * message for later hooks; Compose then numbers injected pointers itself. This watch is installed
 * after ByteInk's source, so it sees the original message first: the native id and PEN_FLAGS are
 * captured there, and Compose frames read the cached flags for the eraser tip and barrel button.
 */
internal object WindowsPenWatch {
    /** The pen pointer id most recently seen, or -1 when no pen has been seen yet. */
    @Volatile var lastPenPointerId: Long = -1
        private set

    /** Called once when the pen's barrel button goes down, on the AWT event thread. */
    @Volatile var onBarrel: (() -> Unit)? = null

    /** PEN_FLAGS captured while the WM_POINTER message was current, by native pointer id. */
    private val flagsById = HashMap<Long, Int>()

    @Synchronized
    internal fun noteFlags(pointerId: Long, flags: Int) {
        flagsById[pointerId] = flags
        lastPenPointerId = pointerId
    }

    @Synchronized
    internal fun forgetFlags(pointerId: Long) {
        flagsById.remove(pointerId)
    }

    /**
     * Uses the flags captured in the hook. GetPointerPenInfo only answers for the current pointer
     * message, which ByteInk has already replaced by the time a Compose frame reaches us.
     */
    fun observePenSample(pointerId: Long) {
        try {
            if (pointerId < 0L || pointerId > 0xffffL) return
            val flags = synchronized(this) {
                lastPenPointerId = pointerId
                flagsById[pointerId]
            } ?: return
            val down = barrelWentDown(barrel, flags)
            barrel = barrelPressed(flags)
            if (down) {
                log?.event("input") { "pen barrel down on pointer $pointerId flags=0x${flags.toString(16)}" }
                onBarrel?.invoke()
            }
        } catch (ignored: Throwable) { /* Never unwind through ByteInk's native hook callback. */ }
    }

    /** The tip lifted or the pen left: the next press is a new edge even with the button held. */
    fun clearPenSample() { barrel = false }

    /** True while the eraser tip is down, from the flags captured in the hook. */
    fun penIsInverted(pointerId: Long): Boolean = synchronized(this) {
        flagsById[pointerId]?.let(::penInverted) ?: false
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

    private val runtime by lazy { Runtime() }
    @Volatile private var hook: MemorySegment? = null
    @Volatile private var log: DebugLog? = null
    private var barrel = false
    private var sawPointer = false
    private var sawFlags = false
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
                    val id = (msg.get(JAVA_LONG, 16) and 0xffff).toInt()
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
                        val flags = runtime.penFlags(id)
                        if (flags != null) {
                            noteFlags(pointer, flags)
                            if (!sawPointer) {
                                sawPointer = true
                                log?.event("input") { "pen watch sees native pointer $id" }
                            }
                            if (!sawFlags) {
                                sawFlags = true
                                log?.event("input") {
                                    "pen flags for pointer $id = 0x${flags.toString(16)} " +
                                        "barrel=${barrelPressed(flags)} eraser=${penInverted(flags)}"
                                }
                            }
                            if (barrelWentDown(barrel, flags)) {
                                log?.event("input") { "pen barrel down on pointer $id flags=0x${flags.toString(16)}" }
                                onBarrel?.invoke()
                            }
                            barrel = barrelPressed(flags)
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

        /** PEN_FLAGS of the pen behind [id], or null when Windows no longer knows the pointer. */
        fun penFlags(id: Int): Int? {
            val ok = hPenInfo.invokeWithArguments(id, penInfo) as Int != 0
            if (!ok && !penInfoFailureLogged) {
                penInfoFailureLogged = true
                log?.event("input") { "GetPointerPenInfo failed for pointer $id" }
            }
            return if (ok) penInfo.get(JAVA_INT, PEN_FLAGS_OFFSET) else null
        }
    }
}
