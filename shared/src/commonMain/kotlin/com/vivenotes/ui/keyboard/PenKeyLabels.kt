package com.vivenotes.ui.keyboard

import androidx.compose.ui.input.key.Key

/**
 * A hardware key's own name for the Hardware pane: what the platform calls the key the pen's
 * driver spells, so a bound slot shows "F19" rather than a number. The desktop keyboard names
 * every key; the web falls back to the shortcut table's name, then the key's number.
 */
internal expect fun Key.penKeyLabel(): String
