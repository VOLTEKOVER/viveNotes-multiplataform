package com.vivenotes.ui.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.ui.canvas.INK_LAYER_TAG
import com.vivenotes.ui.ribbon.draw.ShapeStyleMenuTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals

/** A held stroke becomes a mother shape, and the menu that follows lets it become a precise variant. */
@OptIn(ExperimentalTestApi::class)
class RecognitionStyleMenuTest {

    @Test
    fun thePostRecognitionMenuReseedsTheRecognizedShape() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw).selectInkTool(InkTool.Pen, 1)
        val state = mutableStateOf(initial)
        val existing = state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>()
            .map { it.id }.toSet()
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        waitForIdle()

        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(220f, 260f)); press()
            moveTo(Offset(360f, 260f), delayMillis = 10); moveTo(Offset(500f, 260f), delayMillis = 10)
            moveTo(Offset(500f, 340f), delayMillis = 10); moveTo(Offset(500f, 420f), delayMillis = 10)
            moveTo(Offset(360f, 420f), delayMillis = 10); moveTo(Offset(220f, 420f), delayMillis = 10)
            moveTo(Offset(220f, 340f), delayMillis = 10); moveTo(Offset(220f, 260f), delayMillis = 10)
        }
        mainClock.advanceTimeBy(1100)
        waitUntil(timeoutMillis = 5000) {
            state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>()
                .any { it.id !in existing }
        }
        var recognizedId: String? = null
        runOnIdle { recognizedId = state.value.selectedObjectIds.single() }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput { release() }
        waitForIdle()

        onNodeWithTag(ShapeStyleMenuTags.Popup).assertIsDisplayed()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.RoundedRectangle)).assertIsDisplayed().performClick()
        waitForIdle()

        val result = state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>()
            .single { it.id == recognizedId }
        assertEquals(ShapeKind.RoundedRectangle, result.kind, "the menu picks the variant of the recognized mother shape")
    }
}
