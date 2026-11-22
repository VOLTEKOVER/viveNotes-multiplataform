package com.vivenotes.ui.ribbon.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.ShapeRecognitionSettings
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RecognitionPaneTest {

    @Test
    fun recognitionDocksAPaneWithTheMainSwitchAndEveryShape() =
        runDesktopComposeUiTest(width = 1600, height = 900) {
            var observed = WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)
            var saved: ShapeRecognitionSettings? = null
            setContent {
                var state by remember { mutableStateOf(observed) }
                ViveNotesTheme(darkTheme = true) {
                    WorkspaceScreen(state = state, onStateChange = { state = it(state); observed = state },
                        onRecognitionSettingsChange = { saved = it })
                }
            }

            onNodeWithTag(RecognitionTags.Open).performClick().assertIsSelected()
            onNodeWithTag(ToolPaneTags.Pane).assertExists()
            onNodeWithTag(RecognitionTags.Automatic).assertIsOn()
            ShapeKind.entries.forEach { kind ->
                onNodeWithTag(RecognitionTags.kind(kind)).performScrollTo().assertIsOn()
            }
            onNodeWithTag(RecognitionTags.NoneSelected).assertDoesNotExist()

            onNodeWithTag(RecognitionTags.Automatic).performScrollTo().performClick().assertIsOff()
            runOnIdle {
                assertFalse(observed.recognitionSettings.automatic)
                assertEquals(false, saved?.automatic)
            }

            onNodeWithTag(RecognitionTags.kind(ShapeKind.Rectangle)).performScrollTo().performClick().assertIsOff()
            runOnIdle {
                assertFalse(observed.recognitionSettings.enabledKinds.contains(ShapeKind.Rectangle))
                assertFalse(saved!!.enabledKinds.contains(ShapeKind.Rectangle))
                assertTrue(saved!!.enabledKinds.contains(ShapeKind.Cube))
            }

            onNodeWithTag(RecognitionTags.Open).performClick()
            onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
        }

    @Test
    fun uncheckingEveryKindShowsWhyHeldInkStaysInk() =
        runDesktopComposeUiTest(width = 1600, height = 900) {
            var observed = WorkspaceState.demo().copy(activeTab = RibbonTab.Settings,
                recognitionSettings = ShapeRecognitionSettings(enabledKinds = setOf(ShapeKind.Line)))
            var saved: ShapeRecognitionSettings? = null
            setContent {
                var state by remember { mutableStateOf(observed) }
                ViveNotesTheme(darkTheme = true) {
                    WorkspaceScreen(state = state, onStateChange = { state = it(state); observed = state },
                        onRecognitionSettingsChange = { saved = it })
                }
            }

            onNodeWithTag(RecognitionTags.Open).performClick()
            onNodeWithTag(RecognitionTags.kind(ShapeKind.Line)).performClick().assertIsOff()
            onNodeWithTag(RecognitionTags.NoneSelected).assertExists()
            runOnIdle {
                assertTrue(observed.recognitionSettings.enabledKinds.isEmpty())
                assertTrue(saved!!.enabledKinds.isEmpty())
            }
        }
}
