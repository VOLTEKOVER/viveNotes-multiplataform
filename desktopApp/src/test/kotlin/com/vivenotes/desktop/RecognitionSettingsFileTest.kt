package com.vivenotes.desktop

import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.workspace.ShapeRecognitionSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecognitionSettingsFileTest {

    @Test
    fun missingFileRecognizesEveryKindAndTheChosenSubsetSurvivesRestart() {
        val directory = Files.createTempDirectory("recognition-settings").toFile()
        try {
            val file = File(directory, "config/recognition.properties")
            val store = RecognitionSettingsFile(file)
            assertEquals(ShapeRecognitionSettings.Default, store.load())

            val chosen = ShapeRecognitionSettings(automatic = false,
                enabledKinds = setOf(ShapeKind.Cube, ShapeKind.Arrow, ShapeKind.Curve))
            store.save(chosen)
            val reopened = RecognitionSettingsFile(file).load()
            assertEquals(chosen, reopened)
            assertFalse(reopened.automatic)
            assertTrue(reopened.enabledKinds.contains(ShapeKind.Cube))
            assertFalse(reopened.enabledKinds.contains(ShapeKind.Rectangle))
            val armed = reopened.copy(automatic = true)
            assertTrue(armed.allows(ShapeKind.Cube))
            assertFalse(armed.allows(ShapeKind.Rectangle))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun anUncheckedListIsSavedAsEmptyAndUnknownKindsAreIgnored() {
        val file = Files.createTempFile("recognition-settings-legacy", ".properties").toFile()
        try {
            val store = RecognitionSettingsFile(file)
            store.save(ShapeRecognitionSettings(enabledKinds = emptySet()))
            assertEquals(emptySet<ShapeKind>(), RecognitionSettingsFile(file).load().enabledKinds)

            file.writeText("automatic=invalid\nenabledKinds=Rectangle,NoSuchShape,Cube\n")
            val loaded = store.load()
            assertTrue(loaded.automatic)
            assertEquals(setOf(ShapeKind.Rectangle, ShapeKind.Cube), loaded.enabledKinds)
        } finally {
            file.delete()
        }
    }
}
