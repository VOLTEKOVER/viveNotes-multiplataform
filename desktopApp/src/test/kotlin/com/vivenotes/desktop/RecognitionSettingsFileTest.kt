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
    fun missingFileRecognizesEveryBasicKindAndTheChosenSubsetSurvivesRestart() {
        val directory = Files.createTempDirectory("recognition-settings").toFile()
        try {
            val file = File(directory, "config/recognition.properties")
            val store = RecognitionSettingsFile(file)
            assertEquals(ShapeRecognitionSettings.Default, store.load())

            val chosen = ShapeRecognitionSettings(automatic = false,
                enabledKinds = setOf(ShapeKind.Arrow, ShapeKind.Curve))
            store.save(chosen)
            val reopened = RecognitionSettingsFile(file).load()
            assertEquals(chosen, reopened)
            assertFalse(reopened.automatic)
            assertTrue(reopened.enabledKinds.contains(ShapeKind.Arrow))
            assertTrue(reopened.enabledKinds.contains(ShapeKind.Curve))
            assertFalse(reopened.enabledKinds.contains(ShapeKind.Rectangle))
            val armed = reopened.copy(automatic = true)
            assertTrue(armed.allows(ShapeKind.Arrow))
            assertFalse(armed.allows(ShapeKind.Rectangle))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aFileWithoutAKindListKeepsTheBasicDefaults() {
        val file = Files.createTempFile("recognition-settings-legacy", ".properties").toFile()
        try {
            file.writeText("automatic=true\n")
            val loaded = RecognitionSettingsFile(file).load()
            assertEquals(ShapeRecognitionSettings.Default.enabledKinds, loaded.enabledKinds)
            assertTrue(ShapeKind.onPage(com.vivenotes.model.ink.PAGE_SOLID).none { it in loaded.enabledKinds })
        } finally {
            file.delete()
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
            assertEquals(setOf(ShapeKind.Rectangle), loaded.enabledKinds,
                "unknown names and solids are dropped from saved preferences")
        } finally {
            file.delete()
        }
    }
}
