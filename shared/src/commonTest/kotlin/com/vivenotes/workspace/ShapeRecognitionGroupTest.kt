package com.vivenotes.workspace

import com.vivenotes.model.ink.PAGE_BASIC
import com.vivenotes.model.ink.ShapeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapeRecognitionGroupTest {

    @Test
    fun fourFamiliesPartitionEveryBasicShapeKind() {
        assertEquals(4, ShapeRecognitionGroup.entries.size)
        assertEquals(ShapeKind.onPage(PAGE_BASIC).toSet(),
            ShapeRecognitionGroup.entries.flatMap { it.kinds }.toSet())
        ShapeKind.onPage(PAGE_BASIC).forEach { kind ->
            assertEquals(kind, ShapeRecognitionGroup.groupOf(kind)?.kinds?.first { it == kind })
        }
        assertNull(ShapeRecognitionGroup.groupOf(ShapeKind.Cube), "solids are never recognized")
    }

    @Test
    fun recognitionComparesOnlyMotherShapes() {
        assertEquals(setOf(ShapeKind.Line, ShapeKind.Curve, ShapeKind.L), ShapeRecognitionGroup.Paths.templates)
        assertEquals(setOf(ShapeKind.Rectangle), ShapeRecognitionGroup.Quadrilaterals.templates)
        assertEquals(setOf(ShapeKind.Ellipse), ShapeRecognitionGroup.Conics.templates)
        assertEquals(setOf(ShapeKind.Triangle, ShapeKind.Pentagon, ShapeKind.Hexagon),
            ShapeRecognitionGroup.Polygons.templates)
        assertFalse(ShapeKind.onPage(PAGE_BASIC).size ==
            ShapeRecognitionGroup.entries.sumOf { it.templates.size }, "templates must be fewer than all variants")
    }

    @Test
    fun aFamilyToggleEnablesAndDisablesOnlyItsVariants() {
        val default = ShapeRecognitionSettings.Default
        val paths = ShapeRecognitionGroup.Paths
        val withoutPaths = paths.setEnabled(default, false)

        assertTrue(withoutPaths.enabledKinds.none { it in paths.kinds })
        assertEquals(default.enabledKinds - paths.kinds, withoutPaths.enabledKinds)
        assertTrue(ShapeRecognitionGroup.Quadrilaterals.kinds.all { it in withoutPaths.enabledKinds })
        paths.kinds.forEach { kind ->
            assertFalse(withoutPaths.allows(kind))
            assertTrue(default.allows(kind))
        }

        val restored = paths.setEnabled(withoutPaths, true)
        assertEquals(default.enabledKinds, restored.enabledKinds)
        assertTrue(paths.kinds.all { restored.allows(it) })
    }

    @Test
    fun aPartlyEnabledFamilyIsNotOnUntilEveryVariantIsOn() {
        val partly = ShapeRecognitionSettings(enabledKinds = setOf(ShapeKind.Line))
        val paths = ShapeRecognitionGroup.Paths

        assertFalse(paths.enabledIn(partly))
        assertTrue(paths.partlyEnabledIn(partly))
        assertFalse(ShapeRecognitionGroup.Conics.partlyEnabledIn(partly))

        val all = paths.setEnabled(partly, true)
        assertTrue(paths.enabledIn(all))
        assertFalse(paths.partlyEnabledIn(all))
        assertTrue(paths.kinds.all { all.allows(it) })

        val none = paths.setEnabled(partly, false)
        assertTrue(none.enabledKinds.isEmpty())
        assertFalse(paths.partlyEnabledIn(none))
    }

    @Test
    fun aDisabledMainSwitchKeepsTheFamilyChoicesButAllowsNothing() {
        val settings = ShapeRecognitionSettings(automatic = false)
        val conics = ShapeRecognitionGroup.Conics.setEnabled(settings, true)

        assertTrue(ShapeRecognitionGroup.Conics.enabledIn(conics))
        assertFalse(conics.allows(ShapeKind.Ellipse))
    }
}
