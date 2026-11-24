package com.vivenotes.desktop.pen

/**
 * The native pointer id of the pen Windows last saw draw, as the pen-device stack names it.
 *
 * Compose gives every injected pointer its own id ([NativePointerFrames] hands the touch
 * pipeline a fresh one), so the id a stroke carries cannot be passed to
 * `PenDevice.GetFromPointerId`. The native id is kept here as the first sample arrives, and
 * stays after the stroke ends: a pen's keys arrive later with no pointer of their own.
 */
internal object PenPointerIdentity {
    @Volatile var stylusId: Long = -1L
        private set

    fun note(id: Long) {
        if (id > 0L) stylusId = id
    }

    /** Drops the remembered pen, for tests and for a capture that starts over from scratch. */
    internal fun clear() {
        stylusId = -1L
    }
}
