package com.vivenotes.workspace

/**
 * How this device's pointing hardware is used: Settings → Hardware → Stylus.
 *
 * Android's `drawWithFinger`, which it keeps in its own preferences because it describes the
 * machine — is there a pen, and should a finger write without one — rather than the notes. The desktop
 * keeps it in its config directory for the same reason; it never syncs.
 */
data class InputSettings(
    /**
     * Whether a finger uses the armed drawing tool. Off, as on Android: a finger moves the page and
     * leaves the pen, eraser, shape and lasso tools to a stylus or mouse.
     */
    val drawWithFinger: Boolean = false,
    /**
     * Whether the pen's own motor vibrates: a texture while it writes and a click on its
     * commands. On until turned off — a pen that has one earns its feel by default.
     */
    val penHaptics: Boolean = true,
    /** What the pen's barrel buttons and extra keys do, kept here as Android keeps its click counts. */
    val penButtons: PenButtonSettings = PenButtonSettings(),
)
