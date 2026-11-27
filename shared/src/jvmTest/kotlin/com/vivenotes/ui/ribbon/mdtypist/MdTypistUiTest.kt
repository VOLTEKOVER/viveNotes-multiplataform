package com.vivenotes.ui.ribbon.mdtypist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ai.LocalAiProtocol
import com.vivenotes.ai.LocalAiSettings
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The MD-Typist destination is a full page, not another docked pane. */
@OptIn(ExperimentalTestApi::class)
class MdTypistUiTest {

    @Test
    fun tabShowsSideBySideHandwritingAndSourcePanes() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var state by mutableStateOf(WorkspaceState.demo())
            setWorkspace { state }

            onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.MdTypist))
                .performClick().assertIsSelected()

            onNodeWithTag(MdTypistPageTags.Page).assertIsDisplayed()
            // The preview is the real page canvas, not an ink-only mock: it carries the whole sheet.
            onNodeWithTag(WorkspaceTestTags.CanvasExtent).assertIsDisplayed()
            val handwriting = onNodeWithTag(MdTypistPageTags.HandwritingPane).assertIsDisplayed()
            val source = onNodeWithTag(MdTypistPageTags.SourcePane).assertIsDisplayed()
            val handwritingBounds = handwriting.getUnclippedBoundsInRoot()
            val sourceBounds = source.getUnclippedBoundsInRoot()
            assertTrue(handwritingBounds.right <= sourceBounds.left,
                "handwriting must sit to the left of the source: $handwritingBounds / $sourceBounds")
            onNodeWithTag(MdTypistRibbonTags.format(MdTypistFormat.Markdown)).assertIsSelected()
        }

    @Test
    fun narrowWidthStacksHandwritingAboveSource() =
        runDesktopComposeUiTest(width = 600, height = 900) {
            var state by mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.MdTypist))
            setWorkspace { state }

            val handwriting = onNodeWithTag(MdTypistPageTags.HandwritingPane).assertIsDisplayed()
            val source = onNodeWithTag(MdTypistPageTags.SourcePane).assertIsDisplayed()
            assertTrue(handwriting.getUnclippedBoundsInRoot().bottom <=
                source.getUnclippedBoundsInRoot().top,
                "narrow layout must keep handwriting above the source")
        }

    @Test
    fun formatToggleKeepsSeparateDraftsAndClearEmptiesTheCurrentOne() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var state by mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.MdTypist))
            setWorkspace { state }

            onNodeWithTag(MdTypistPageTags.SourceEditor).performTextReplacement("# Review")
            onNodeWithTag(MdTypistRibbonTags.format(MdTypistFormat.Typst))
                .performClick().assertIsSelected()
            assertSourceIsEmpty()
            onNodeWithTag(MdTypistPageTags.SourceEditor)
                .performTextReplacement("#set text(size: 12pt)")

            onNodeWithTag(MdTypistRibbonTags.format(MdTypistFormat.Markdown))
                .performClick().assertIsSelected()
            onNodeWithTag(MdTypistPageTags.SourceEditor).assertTextContains("# Review")

            onNodeWithTag(MdTypistRibbonTags.format(MdTypistFormat.Typst)).performClick()
            onNodeWithTag(MdTypistPageTags.SourceEditor).assertTextContains("#set text(size: 12pt)")
            onNodeWithTag(MdTypistRibbonTags.Clear).performClick()
            assertSourceIsEmpty()
        }

    @Test
    fun convertWritesTheModelsSourceAndUsesTheEditedConnection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var state by mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.MdTypist))
            val calls = AtomicInteger()
            var persisted: LocalAiSettings? = null
            val converter = object : MdTypistConverter {
                override suspend fun convert(
                    state: WorkspaceState,
                    format: MdTypistFormat,
                    settings: LocalAiSettings,
                ): MdTypistConversionResult {
                    calls.incrementAndGet()
                    assertEquals("http://192.168.1.20:11434", settings.endpoint)
                    assertEquals(LocalAiProtocol.OpenAiCompatible, settings.protocol)
                    return MdTypistConversionResult.Success("# Converted note")
                }
            }
            setContent {
                var workspace by remember { mutableStateOf(state) }
                ViveNotesTheme {
                    WorkspaceScreen(workspace, onStateChange = { workspace = it(workspace) },
                        mdTypistConverter = converter,
                        onMdTypistAiSettingsChange = { persisted = it })
                }
            }

            onNodeWithTag(MdTypistRibbonTags.ApiModel).performClick()
            onNodeWithTag(MdTypistPageTags.ApiDialog).assertIsDisplayed()
            onNodeWithTag(MdTypistPageTags.AiEndpoint)
                .performTextReplacement("http://192.168.1.20:11434")
            onNodeWithTag(MdTypistPageTags.protocol(LocalAiProtocol.OpenAiCompatible)).performClick()
            onNodeWithTag(MdTypistPageTags.ApiApply).performClick()
            onNodeWithTag(MdTypistRibbonTags.Convert).performClick()

            waitUntil(timeoutMillis = 5_000) { calls.get() == 1 }
            onNodeWithTag(MdTypistPageTags.SourceEditor).assertTextContains("# Converted note")
            onNodeWithTag(MdTypistPageTags.Status).assertIsDisplayed()
            assertEquals(LocalAiProtocol.OpenAiCompatible, persisted?.protocol)
        }

    @Test
    fun apiModelDialogCancelKeepsTheSavedConnection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var state by mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.MdTypist))
            var saved: LocalAiSettings? = null
            setContent {
                var workspace by remember { mutableStateOf(state) }
                ViveNotesTheme {
                    WorkspaceScreen(workspace, onStateChange = { workspace = it(workspace) },
                        onMdTypistAiSettingsChange = { saved = it })
                }
            }

            onNodeWithTag(MdTypistRibbonTags.ApiModel).performClick()
            onNodeWithTag(MdTypistPageTags.AiEndpoint)
                .performTextReplacement("http://discard.me:11434")
            onNodeWithTag(MdTypistPageTags.ApiCancel).performClick()

            onAllNodesWithTag(MdTypistPageTags.ApiDialog).assertCountEquals(0)
            assertEquals(null, saved)
        }

    @Test
    fun exportWritesTheEditedSourceAndItsFigures() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var state by mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.MdTypist))
            val calls = AtomicInteger()
            var exported: MdTypistExportRequest? = null
            val exporter = object : MdTypistExporter {
                override suspend fun export(request: MdTypistExportRequest): Result<String> {
                    calls.incrementAndGet()
                    exported = request
                    return Result.success("/tmp/note.md")
                }
            }
            val figure = MdTypistFigure("figure-1.png", byteArrayOf(1, 2, 3))
            val converter = object : MdTypistConverter {
                override suspend fun convert(
                    state: WorkspaceState,
                    format: MdTypistFormat,
                    settings: LocalAiSettings,
                ) = MdTypistConversionResult.Success("# note", listOf(figure))
            }
            setContent {
                var workspace by remember { mutableStateOf(state) }
                ViveNotesTheme {
                    WorkspaceScreen(workspace, onStateChange = { workspace = it(workspace) },
                        mdTypistConverter = converter, mdTypistExporter = exporter)
                }
            }

            onNodeWithTag(MdTypistRibbonTags.Convert).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onNodeWithTag(MdTypistPageTags.SourceEditor).fetchSemanticsNode()
                    .config[SemanticsProperties.EditableText].text == "# note"
            }
            onNodeWithTag(MdTypistRibbonTags.Export).performClick()

            waitUntil(timeoutMillis = 5_000) { calls.get() == 1 }
            assertEquals("# note", exported?.source)
            assertEquals(MdTypistFormat.Markdown, exported?.format)
            assertEquals(listOf(figure), exported?.figures)
            onNodeWithTag(MdTypistPageTags.Status).assertIsDisplayed()
        }

    private fun androidx.compose.ui.test.ComposeUiTest.assertSourceIsEmpty() {
        val editable = onNodeWithTag(MdTypistPageTags.SourceEditor)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals("", editable.text)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.setWorkspace(state: () -> WorkspaceState) {
        setContent {
            var workspace by remember { mutableStateOf(state()) }
            ViveNotesTheme {
                WorkspaceScreen(workspace, onStateChange = { workspace = it(workspace) })
            }
        }
    }
}
