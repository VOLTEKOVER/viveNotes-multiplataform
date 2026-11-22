package com.vivenotes.workspace

import com.vivenotes.model.ink.ShapeKind

/**
 * App-wide choices for turning held ink into an editable shape.
 *
 * [automatic] is the main switch: with it off the hold gesture never looks for a shape, whatever the
 * pen's own `holdForShape` says. [enabledKinds] is the second gate: a disabled kind cannot win or
 * hide an enabled runner-up in `ShapeFit`. An empty set recognizes nothing, which is a valid choice.
 */
data class ShapeRecognitionSettings(
    val automatic: Boolean = true,
    val enabledKinds: Set<ShapeKind> = ShapeKind.entries.toSet(),
) {
    /** Keeps enum order and drops names from a newer app that this build does not know. */
    fun normalized(): ShapeRecognitionSettings = copy(
        enabledKinds = ShapeKind.entries.filter { it in enabledKinds }.toSet(),
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
