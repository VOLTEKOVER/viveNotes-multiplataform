package com.vivenotes.desktop

import com.vivenotes.workspace.InputSettings
import com.vivenotes.workspace.PenAction
import com.vivenotes.workspace.PenButtonSettings
import com.vivenotes.workspace.PenKeyBinding
import java.io.File
import java.util.Properties

/** This device's Settings → Hardware input choices, beside the other device preferences. */
internal class InputSettingsFile(private val file: File) {
    fun load(): InputSettings {
        val values = file.readProperties() ?: return InputSettings()
        return InputSettings(
            drawWithFinger = values.getProperty("drawWithFinger")?.toBooleanStrictOrNull() ?: false,
            // On until turned off: a pen that has a motor earns its feel by default.
            penHaptics = values.getProperty("penHaptics")?.toBooleanStrictOrNull() ?: true,
            // An unknown name — a newer app's entry, or hand-edited junk — is the field's default
            // rather than the entry that happened to take its place in this version.
            penButtons = PenButtonSettings(
                barrel = values.getProperty("penBarrel")?.let(PenAction::decode)
                    ?: PenButtonSettings().barrel,
                barrel2 = values.getProperty("penBarrel2")?.let(PenAction::decode) ?: PenAction.None,
                keys = List(PenButtonSettings.KEY_SLOTS) { slot ->
                    values.getProperty("penKey${slot + 1}")
                        ?.takeIf(String::isNotEmpty)?.let(PenKeyBinding::decode)
                },
            ),
        )
    }

    fun save(settings: InputSettings) {
        file.writePropertiesAtomically(Properties().apply {
            setProperty("drawWithFinger", settings.drawWithFinger.toString())
            setProperty("penHaptics", settings.penHaptics.toString())
            setProperty("penBarrel", settings.penButtons.barrel.name)
            setProperty("penBarrel2", settings.penButtons.barrel2.name)
            repeat(PenButtonSettings.KEY_SLOTS) { slot ->
                setProperty("penKey${slot + 1}", settings.penButtons.key(slot)?.encode() ?: "")
            }
        }, "ViveNotes input settings")
    }
}
