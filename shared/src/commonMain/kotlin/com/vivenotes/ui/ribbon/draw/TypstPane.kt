package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vivenotes.data.PictureLibrary
import com.vivenotes.localization.Strings
import com.vivenotes.typst.DefaultTypstColors
import com.vivenotes.typst.TypstColorBinding
import com.vivenotes.typst.TypstDiagramSource
import com.vivenotes.typst.TypstDiagramSourceCodec
import com.vivenotes.typst.TypstRenderer
import com.vivenotes.typst.TypstRenderResult
import com.vivenotes.ui.icons.ObjectSymbols
import kotlinx.coroutines.delay

internal object TypstPaneTags {
    const val Pane = "draw-typst-pane"
    const val PreviewToggle = "draw-typst-mode-preview"
    const val EditToggle = "draw-typst-mode-edit"
    const val Source = "draw-typst-source"
    const val Preview = "draw-typst-preview"
    const val Error = "draw-typst-error"
    const val Insert = "draw-typst-insert"
    const val Replace = "draw-typst-replace"
    fun color(index: Int) = "draw-typst-color-$index"
}

@Composable
internal fun TypstPane(
    source: String,
    onSourceChange: (String) -> Unit,
    colors: List<TypstColorBinding>,
    onColorsChange: (List<TypstColorBinding>) -> Unit,
    renderer: TypstRenderer,
    pictures: PictureLibrary?,
    enabled: Boolean,
    onPlace: (String, TypstDiagramSource) -> Unit,
    onClose: () -> Unit,
    /** True when the panel was opened from a placed diagram's quick toolkit. */
    editing: Boolean = false,
    /** Exact mode to open in; a diagram opened for editing starts with its colours showing. */
    startInEditMode: Boolean = editing,
) {
    var editMode by remember(startInEditMode) { mutableStateOf(startInEditMode) }
    var result by remember { mutableStateOf<TypstRenderResult?>(null) }
    var compiling by remember { mutableStateOf(false) }
    var painter by remember { mutableStateOf<Painter?>(null) }
    val diagram = TypstDiagramSource(source, colors)

    LaunchedEffect(renderer, diagram) {
        val request = diagram
        delay(300)
        compiling = true
        val rendered = runCatching { renderer.render(request) }
            .getOrElse { TypstRenderResult.Failure(it.message ?: Strings.typst_render_failed) }
        if (request == diagram) {
            result = rendered
            compiling = false
        }
    }
    val svg = (result as? TypstRenderResult.Success)?.svg
    LaunchedEffect(svg, pictures) {
        painter = null
        if (svg != null && pictures != null) {
            painter = runCatching { pictures.vector(svg.toByteArray()) }.getOrNull()
        }
    }

    MathPaneFrame(Strings.typst_diagram, onClose, TypstPaneTags.Pane) {
        TypstModeToggle(editMode) { editMode = it }
        MathSection(Strings.typst_source) {
            OutlinedTextField(
                value = source,
                onValueChange = onSourceChange,
                enabled = enabled,
                minLines = 5,
                maxLines = 10,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize),
                placeholder = { Text(Strings.typst_source_hint) },
                modifier = Modifier.fillMaxWidth().testTag(TypstPaneTags.Source),
            )
            if (editMode) {
                Spacer(Modifier.height(10.dp))
                Text(Strings.typst_edit_hint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                colors.filter { it.used }.forEach { binding ->
                    val colorIndex = colors.indexOf(binding)
                    TypstColorRow(binding, colorIndex, onColorChange = { argb ->
                        onColorsChange(colors.mapIndexed { i, color ->
                            if (i == colorIndex) color.copy(argb = argb) else color
                        })
                    })
                }
            }
        }
        MathSection(Strings.preview) {
            Box(Modifier.fillMaxWidth().height(180.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                .testTag(TypstPaneTags.Preview), contentAlignment = Alignment.Center) {
                when {
                    compiling -> Text(Strings.typst_rendering,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    painter != null -> Image(painter!!, contentDescription = Strings.typst_diagram,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
                    result is TypstRenderResult.Failure -> Text(
                        (result as TypstRenderResult.Failure).message.ifBlank { Strings.typst_render_failed },
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp).testTag(TypstPaneTags.Error))
                    else -> Text(Strings.typst_preview_hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        MathSection(Strings.actions) {
            MathPanelButton(
                onClick = { if (svg != null) onPlace(svg, diagram) },
                enabled = enabled && svg != null && !compiling,
                modifier = Modifier.fillMaxWidth()
                    .testTag(if (editing) TypstPaneTags.Replace else TypstPaneTags.Insert),
            ) {
                if (editing) {
                    Icon(ObjectSymbols.EditSquare, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (editing) Strings.typst_replace else Strings.typst_insert)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
private fun TypstModeToggle(editMode: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleButton(!editMode, { onChange(false) },
            shapes = ToggleButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight),
            colors = ToggleButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.tertiary,
                checkedContentColor = MaterialTheme.colorScheme.onTertiary,
            ),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.ExtraSmallContainerHeight),
            modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.ExtraSmallContainerHeight)
                .testTag(TypstPaneTags.PreviewToggle)) {
            Text(Strings.preview)
        }
        ToggleButton(editMode, { onChange(true) },
            shapes = ToggleButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight),
            colors = ToggleButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.tertiary,
                checkedContentColor = MaterialTheme.colorScheme.onTertiary,
            ),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.ExtraSmallContainerHeight),
            modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.ExtraSmallContainerHeight)
                .testTag(TypstPaneTags.EditToggle)) {
            Text(Strings.edit)
        }
    }
}

@Composable
private fun TypstColorRow(binding: TypstColorBinding, index: Int, onColorChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var draft by remember(binding.token) { mutableStateOf(TypstDiagramSourceCodec.hex(binding.argb)) }
    var invalid by remember(binding.token) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(Modifier.size(28.dp).background(Color(binding.argb), RoundedCornerShape(6.dp))
                .testTag(TypstPaneTags.color(index)).semantics {
                    contentDescription = Strings.typst_color_slot(index + 1)
                }) {
                Box(Modifier.matchParentSize().clickable { expanded = true }
                    .semantics { contentDescription = Strings.typst_color_slot(index + 1) })
            }
            DropdownMenu(expanded, { expanded = false }) {
                DefaultTypstColors.forEach { color ->
                    DropdownMenuItem(
                        text = { Text(TypstDiagramSourceCodec.hex(color)) },
                        leadingIcon = {
                            Box(Modifier.size(18.dp).background(Color(color), RoundedCornerShape(4.dp)))
                        },
                        onClick = {
                            draft = TypstDiagramSourceCodec.hex(color)
                            invalid = false
                            onColorChange(color)
                            expanded = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(binding.token, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { value ->
                draft = value
                val parsed = parseHexColor(value)
                invalid = parsed == null
                if (parsed != null) onColorChange(parsed)
            },
            isError = invalid,
            singleLine = true,
            label = { Text(Strings.typst_color_value) },
            modifier = Modifier.width(112.dp),
        )
    }
}

private fun parseHexColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length !in setOf(6, 8)) return null
    val argb = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) (0xFF000000 or argb).toInt() else argb.toInt()
}
