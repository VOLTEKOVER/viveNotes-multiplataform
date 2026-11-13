package com.vivenotes.ui.keyboard

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import java.awt.event.KeyEvent

/** The desktop keyboard's own name for the key: "F19", "Page Up", the label printed on it. */
internal actual fun Key.penKeyLabel(): String = KeyEvent.getKeyText(nativeKeyCode)
