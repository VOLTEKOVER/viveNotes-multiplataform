package com.vivenotes.ui.keyboard

import androidx.compose.ui.input.key.Key

/** No keyboard layout in the browser: the shortcut table's name, else the key's number. */
internal actual fun Key.penKeyLabel(): String = shortcutKeyLabel() ?: "Key $keyCode"
