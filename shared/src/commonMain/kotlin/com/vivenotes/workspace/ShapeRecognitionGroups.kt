package com.vivenotes.workspace

import com.vivenotes.localization.Strings
import com.vivenotes.model.ink.ShapeKind

/**
 * The four families offered by Settings → Shape recognition. Every basic [ShapeKind] belongs to
 * exactly one family: recognition compares only [templates], the mother shapes, while [kinds] are
 * the variants the style menu can turn a recognized shape into.
 *
 * 3D solids are deliberately absent: they are drawn from the shape toolkit, never recognized.
 *
 * [ShapeRecognitionSettings.enabledKinds] stays the persisted source of truth, so older documents
 * and the recognition engine keep speaking concrete `ShapeKind`s.
 */
enum class ShapeRecognitionGroup(
    private val labelText: () -> String,
    val kinds: Set<ShapeKind>,
    val templates: Set<ShapeKind>,
) {
    Paths({ Strings.recognition_paths }, setOf(
        ShapeKind.Line, ShapeKind.Arrow, ShapeKind.Curve, ShapeKind.L,
    ), setOf(ShapeKind.Line, ShapeKind.Curve, ShapeKind.L)),
    Quadrilaterals({ Strings.recognition_quadrilaterals }, setOf(
        ShapeKind.Rectangle, ShapeKind.RoundedRectangle, ShapeKind.Diamond,
    ), setOf(ShapeKind.Rectangle)),
    Conics({ Strings.recognition_conics }, setOf(ShapeKind.Ellipse), setOf(ShapeKind.Ellipse)),
    Polygons({ Strings.recognition_polygons }, setOf(
        ShapeKind.Triangle, ShapeKind.RightTriangle, ShapeKind.Pentagon, ShapeKind.Hexagon,
    ), setOf(ShapeKind.Triangle, ShapeKind.Pentagon, ShapeKind.Hexagon)),
    ;

    val label: String get() = labelText()

    /** A family counts as on only when every variant it contains is enabled. */
    fun enabledIn(settings: ShapeRecognitionSettings): Boolean = kinds.all { it in settings.enabledKinds }

    /** Some, but not all, of the family's variants are enabled. */
    fun partlyEnabledIn(settings: ShapeRecognitionSettings): Boolean =
        kinds.any { it in settings.enabledKinds } && !enabledIn(settings)

    /** Enables or disables every variant in this family, leaving the other families untouched. */
    fun setEnabled(settings: ShapeRecognitionSettings, enabled: Boolean): ShapeRecognitionSettings = settings.copy(
        enabledKinds = if (enabled) settings.enabledKinds + kinds else settings.enabledKinds - kinds,
    )

    companion object {
        init {
            val seen = hashSetOf<ShapeKind>()
            entries.forEach { group ->
                require(group.kinds.isNotEmpty() && group.kinds.none { !seen.add(it) }) {
                    "Shape recognition families must partition the basic ShapeKinds without overlap"
                }
                require(group.templates.isNotEmpty() && group.templates.all { it in group.kinds }) {
                    "A family's recognition templates must be variants of that family"
                }
            }
            require(seen == ShapeKind.onPage(com.vivenotes.model.ink.PAGE_BASIC).toSet()) {
                "Every basic ShapeKind must belong to a shape recognition family"
            }
        }

        fun groupOf(kind: ShapeKind): ShapeRecognitionGroup? = entries.firstOrNull { kind in it.kinds }
    }
}
