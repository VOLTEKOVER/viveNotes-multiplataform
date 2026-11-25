package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.vivenotes.localization.Strings
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.ui.components.ContextMenu
import com.vivenotes.ui.components.ContextMenuItem
import com.vivenotes.ui.components.ContextMenuDivider
import com.vivenotes.ui.components.ContextSubmenu
import com.vivenotes.ui.icons.DrawSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.workspace.ShapeRecognitionGroup

/** Test tags for the variant menu shown after recognition and from the object's right-click menu. */
internal object ShapeStyleMenuTags {
    const val Change = "shape-style-change"
    fun variant(kind: ShapeKind) = "shape-style-${kind.name.lowercase()}"
    const val Popup = "shape-style-popup"
}

/** The variants offered for a shape from a recognized family; solids keep their drawn geometry. */
internal fun shapeStyleVariants(kind: ShapeKind): List<ShapeKind> =
    ShapeRecognitionGroup.groupOf(kind)?.kinds?.sortedBy { it.ordinal } ?: emptyList()

/**
 * The `Change style` row of the object's right-click menu. Every variant of the shape's family is a
 * command: picking one reseeds the shape in place, keeping its colour, line and fill.
 */
@Composable
internal fun ColumnScope.ShapeStyleMenuItems(
    kind: ShapeKind,
    enabled: Boolean,
    onPick: (ShapeKind) -> Unit,
) {
    val variants = shapeStyleVariants(kind)
    if (variants.isEmpty()) return
    ContextSubmenu(
        label = Strings.change_shape_style,
        icon = DrawSymbols.Shape,
        enabled = enabled,
        modifier = Modifier.testTag(ShapeStyleMenuTags.Change),
    ) {
        variants.forEach { variant ->
            ContextMenuItem(
                label = variant.label,
                icon = ObjectSymbols.LineStyle,
                enabled = enabled,
                onClick = { onPick(variant) },
                modifier = Modifier.testTag(ShapeStyleMenuTags.variant(variant)),
            )
        }
    }
}

/**
 * The small menu that follows a held-ink recognition: the shape is already on the page as a mother
 * shape, and this lets the user replace it with the exact variant they meant.
 */
@Composable
internal fun ShapeStylePopup(
    anchor: androidx.compose.ui.geometry.Offset?,
    kind: ShapeKind?,
    enabled: Boolean,
    onPick: (ShapeKind) -> Unit,
    onDismiss: () -> Unit,
) {
    if (kind == null) return
    ContextMenu(anchor = anchor, onDismiss = onDismiss, modifier = Modifier.testTag(ShapeStyleMenuTags.Popup)) {
        variantsMenu(kind, enabled, onPick)
    }
}

@Composable
private fun ColumnScope.variantsMenu(kind: ShapeKind, enabled: Boolean, onPick: (ShapeKind) -> Unit) {
    val variants = shapeStyleVariants(kind)
    if (variants.isEmpty()) return
    ContextMenuItem(
        label = Strings.choose_shape_style,
        icon = DrawSymbols.Shape,
        enabled = false,
        onClick = {},
    )
    ContextMenuDivider()
    variants.forEach { variant ->
        ContextMenuItem(
            label = variant.label,
            icon = DrawSymbols.Shape,
            enabled = enabled,
            onClick = { onPick(variant) },
            modifier = Modifier.testTag(ShapeStyleMenuTags.variant(variant)),
        )
    }
}
