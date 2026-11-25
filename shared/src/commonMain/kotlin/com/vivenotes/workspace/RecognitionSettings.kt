package com.vivenotes.workspace

import com.vivenotes.model.ink.PAGE_BASIC
import com.vivenotes.model.ink.ShapeKind

/**
 * App-wide choices for turning held ink into an editable shape.
 *
 * [automatic] is the main switch: with it off the hold gesture never looks for a shape, whatever the
 * pen's own `holdForShape` says. [enabledKinds] is the second gate: recognition compares only mother
 * shapes from enabled families. 3D solids are never recognized. An empty set recognizes nothing.
 */
data class ShapeRecognitionSettings(
    val automatic: Boolean = true,
    val enabledKinds: Set<ShapeKind> = ShapeKind.onPage(PAGE_BASIC).toSet(),
) {
    /** Keeps enum order and drops names from a newer app that this build does not know. */
    fun normalized(): ShapeRecognitionSettings = copy(
        enabledKinds = ShapeKind.onPage(PAGE_BASIC).filter { it in enabledKinds }.toSet(),
    )

    fun allows(kind: ShapeKind): Boolean = automatic && kind in enabledKinds

    /** Adds or removes one kind without disturbing the others. */
    fun withKind(kind: ShapeKind, enabled: Boolean): ShapeRecognitionSettings = copy(
        enabledKinds = if (enabled) enabledKinds + kind else enabledKinds - kind,
    )

    companion object {
        val Default: ShapeRecognitionSettings = ShapeRecognitionSettings()
    }
}
