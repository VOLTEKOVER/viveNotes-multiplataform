package com.vivenotes.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Outline
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CanvasSelectionTypstTest {

    @Test
    fun aSelectedTypstPictureGetsAnEditButtonInTheQuickToolkit() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo()
            val pageId = base.selectedPageId
            val image = Outline.Image(
                id = "typst-image",
                x = 300f,
                y = 350f,
                width = 200f,
                height = 120f,
                attachmentId = "typst-svg",
            )
            val state = base.copy(
                notebooks = base.notebooks.map { notebook ->
                    notebook.copy(sections = notebook.sections.map { section ->
                        section.copy(pages = section.pages.map { page ->
                            if (page.id == pageId) page.copy(document = page.document.copy(
                                outlines = page.document.outlines + image)) else page
                        })
                    })
                },
                selectedObjectIds = setOf(image.id),
                selectedInk = null,
                selectedTextOutlineIds = emptySet(),
            )
            var edited: Outline.Image? = null

            setContent {
                ViveNotesTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize()) {
                        CanvasObjectToolkit(
                            state = state,
                            groupBounds = null,
                            measuredTextHeights = emptyMap(),
                            shapeInk = Color.Black,
                            heldTable = null,
                            onColorObjects = { _, _ -> },
                            onTableEdit = {},
                            onReleaseTable = {},
                            onEquationChange = {},
                            onShapeLineType = {},
                            onShapeWidth = {},
                            onShapeFill = {},
                            onImageBackground = {},
                            onCopyObjects = {},
                            onToggleObjectLock = {},
                            onDeleteObjects = {},
                            onOpenTypstImage = { edited = it },
                            pageToView = { it },
                        )
                    }
                }
            }

            onNodeWithTag(WorkspaceTestTags.ObjectToolkit).assertIsDisplayed()
            onNodeWithTag(WorkspaceTestTags.ObjectTypstEdit).assertIsDisplayed().performClick()
            assertEquals(image.id, edited?.id)
        }
}
