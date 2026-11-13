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

/**
 * Remembers which pen Windows is talking about, by watching the same messages the ink capture
 * reads: WM_POINTER events carry the pointer id the pen-device stack resolves a motor from. The
 * capture only sees the pen pressed — a hovering pen draws nothing — while a pen's keys arrive as
 * plain key presses with no pointer in sight, so without this watch the first command click would
 * have no pen behind it. The id stays valid while the pen is in range; the last one seen is kept
 * when it leaves, and a pen that left leaves its clicks silent.
 */
internal object WindowsPenWatch {
    /** The pen pointer id most recently seen, or -1 when no pen has been seen yet. */
    @Volatile var lastPenPointerId: Long = -1
        private set

    private const val WH_GETMESSAGE = 3
    private const val PM_REMOVE = 1L
    private const val WM_POINTERUPDATE = 0x245
    private const val WM_POINTERDOWN = 0x246
    private const val WM_POINTERUP = 0x247
    private const val WM_POINTERENTER = 0x249
    private const val WM_POINTERLEAVE = 0x24a
    private const val PT_PEN = 3

    private val runtime by lazy { Runtime() }
    @Volatile private var hook: MemorySegment? = null

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
                hook = installed
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
        runtime.unhook(installed)
    }

    /** A WH_GETMESSAGE callback: never blocks a message, never unwinds through the native chain. */
    private fun observe(code: Int, removed: Long, address: Long): Long {
        try {
            if (code >= 0 && removed == PM_REMOVE && address != 0L) {
                val message = MemorySegment.ofAddress(address).reinterpret(48).get(JAVA_INT, 8)
                if (message == WM_POINTERUPDATE || message == WM_POINTERDOWN || message == WM_POINTERUP ||
                    message == WM_POINTERENTER || message == WM_POINTERLEAVE) {
                    val id = (MemorySegment.ofAddress(address).reinterpret(48).get(JAVA_LONG, 16) and 0xffff).toInt()
                    if (runtime.isPen(id, PT_PEN)) lastPenPointerId = id.toLong()
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
            MethodHandles.lookup().findVirtual(WindowsPenWatch::class.java, "observe",
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
        private val hPointerType: MethodHandle = linker.downcallHandle(user32.find("GetPointerType").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS))
        private val pointerTypeOut = arena.allocate(JAVA_INT)

        fun setHook(kind: Int, stub: MemorySegment, module: MemorySegment, thread: Int): MemorySegment =
            hSetHook.invokeWithArguments(kind, stub, module, thread) as MemorySegment

        fun unhook(hook: MemorySegment) { hUnhook.invokeWithArguments(hook) }

        fun callNextHook(next: MemorySegment, code: Int, wparam: Long, lparam: Long): Long =
            hCallNextHook.invokeWithArguments(next, code, wparam, lparam) as Long

        fun currentThreadId(): Int = hCurrentThreadId.invokeWithArguments() as Int

        /** True when Windows calls [id] a [kind] pointer (PT_PEN). One reusable out slot, same thread. */
        fun isPen(id: Int, kind: Int): Boolean {
            pointerTypeOut.set(JAVA_INT, 0, 0)
            return hPointerType.invokeWithArguments(id, pointerTypeOut) == 0 &&
                pointerTypeOut.get(JAVA_INT, 0) == kind
        }
    }
}
