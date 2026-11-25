package com.vivenotes.ui.ribbon.draw

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.ui.components.ContextMenu
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ShapeStyleMenuTest {

    @Test
    fun eachFamilyOffersOnlyItsOwnVariants() = runDesktopComposeUiTest(width = 900, height = 700) {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                ContextMenu(anchor = Offset(20f, 20f), onDismiss = {}) {
                    ShapeStyleMenuItems(ShapeKind.Rectangle, true, {})
                }
            }
        }

        onNodeWithTag(ShapeStyleMenuTags.Change).performClick()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.Rectangle)).assertIsDisplayed()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.RoundedRectangle)).assertIsDisplayed()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.Diamond)).assertIsDisplayed()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.Triangle)).assertDoesNotExist()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.Cube)).assertDoesNotExist()
    }

    @Test
    fun theRecognitionPopupOffersOnlyTheRecognizedFamily() = runDesktopComposeUiTest(width = 900, height = 700) {
        var chosen: ShapeKind? = null
        setContent {
            ViveNotesTheme(darkTheme = true) {
                ShapeStylePopup(Offset(40f, 40f), ShapeKind.Triangle, true, { chosen = it }, {})
            }
        }

        onNodeWithTag(ShapeStyleMenuTags.Popup).assertIsDisplayed()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.Triangle)).assertIsDisplayed()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.RightTriangle)).assertIsDisplayed()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.Rectangle)).assertDoesNotExist()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.RightTriangle)).performClick()
        assertEquals(ShapeKind.RightTriangle, chosen)
    }

    @Test
    fun choosingAVariantCallsBackWithThatKind() = runDesktopComposeUiTest(width = 900, height = 700) {
        var chosen: ShapeKind? = null
        setContent {
            ViveNotesTheme(darkTheme = true) {
                ContextMenu(anchor = Offset(20f, 20f), onDismiss = {}) {
                    ShapeStyleMenuItems(ShapeKind.Triangle, true) { chosen = it }
                }
            }
        }

        onNodeWithTag(ShapeStyleMenuTags.Change).performClick()
        onNodeWithTag(ShapeStyleMenuTags.variant(ShapeKind.RightTriangle)).performClick()
        assertEquals(ShapeKind.RightTriangle, chosen)
    }
}
