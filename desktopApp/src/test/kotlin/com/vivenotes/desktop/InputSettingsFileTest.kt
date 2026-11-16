package com.vivenotes.desktop

import com.vivenotes.workspace.InputSettings
import com.vivenotes.workspace.PenAction
import com.vivenotes.workspace.PenButtonSettings
import com.vivenotes.workspace.PenKeyBinding
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class InputSettingsFileTest {
    @Test
    fun aFingerDoesNotDrawUntilChosenAndTheChoiceIsKept() {
        val directory = Files.createTempDirectory("input-settings").toFile()
        try {
            val store = InputSettingsFile(File(directory, "config/input.properties"))
            assertEquals(InputSettings(drawWithFinger = false), store.load())
            store.save(InputSettings(drawWithFinger = true))
            assertEquals(InputSettings(drawWithFinger = true),
                InputSettingsFile(File(directory, "config/input.properties")).load())
            store.save(InputSettings(drawWithFinger = false))
            assertEquals(InputSettings(drawWithFinger = false), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** The pen's barrels and its three key slots, an empty slot included, survive their file. */
    @Test
    fun thePenSButtonsAndKeysSurviveTheirFile() {
        val directory = Files.createTempDirectory("input-settings").toFile()
        try {
            val file = File(directory, "config/input.properties")
            val settings = InputSettings(
                drawWithFinger = true,
                penButtons = PenButtonSettings(
                    barrel = PenAction.Lasso,
                    barrel2 = PenAction.CyclePens,
                    keys = listOf(
                        PenKeyBinding(1337, "Print Screen", PenAction.Pen2),
                        null,
                        // A label may itself contain bars, as some keyboards name keys.
                        PenKeyBinding(42, "Left |Bar| Right", PenAction.Undo),
                    ),
                ),
            )
            InputSettingsFile(file).save(settings)
            assertEquals(settings, InputSettingsFile(file).load())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** The pen's motor is on by default, and a file that turned it off keeps it off. */
    @Test
    fun thePenSMotorCanBeTurnedOffAndStaysOff() {
        val directory = Files.createTempDirectory("input-settings").toFile()
        try {
            val file = File(directory, "config/input.properties")
            assertEquals(InputSettings(), InputSettingsFile(file).load())
            InputSettingsFile(file).save(InputSettings(penHaptics = false))
            assertEquals(InputSettings(penHaptics = false), InputSettingsFile(file).load())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Unknown actions and bindings that are not bindings become the fields' defaults. */
    @Test
    fun unreadablePenValuesFallBackToTheirDefaults() {
        val file = Files.createTempFile("input-settings-invalid", ".properties").toFile()
        try {
            file.writeText(
                """
                penBarrel=Someday
                penBarrel2=NoSuchActionEither
                penKey1=no bars at all
                penKey2=|no code|Pen1
                penKey3=42|Label|NoSuchAction
                """.trimIndent() + "\n"
            )
            assertEquals(InputSettings(), InputSettingsFile(file).load())
        } finally {
            file.delete()
        }
    }

    @Test
    fun anUnreadableValueFallsBackToPanning() {
        val file = Files.createTempFile("input-settings-invalid", ".properties").toFile()
        try {
            file.writeText("drawWithFinger=sometimes\n")
            assertEquals(InputSettings(), InputSettingsFile(file).load())
        } finally {
            file.delete()
        }
    }
}
