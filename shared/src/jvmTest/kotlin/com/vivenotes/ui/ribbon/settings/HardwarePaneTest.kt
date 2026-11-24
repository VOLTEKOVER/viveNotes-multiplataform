package com.vivenotes.ui.ribbon.settings

import com.vivenotes.workspace.InputSettings
import com.vivenotes.workspace.InkTool
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import com.vivenotes.data.TextClipboard
import com.vivenotes.data.LocalTextClipboard
import com.vivenotes.localization.Strings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.vivenotes.model.Mark
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.keyboard.penKeyLabel
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.LocalPenHaptics
import com.vivenotes.workspace.PenAction
import com.vivenotes.workspace.PenButtonSettings
import com.vivenotes.workspace.PenHapticFeel
import com.vivenotes.workspace.PenHaptics
import com.vivenotes.workspace.PenKeyBinding
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutKey
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings → Hardware: the keyboard shortcuts it lists and rebinds, and that the workspace then
 * dispatches by those bindings, in the text box as well as on the canvas. The Stylus page adds
 * the pen's own buttons and keys, captured the same way and dispatched ahead of the keyboard.
 */
@OptIn(ExperimentalTestApi::class)
class HardwarePaneTest {

    private var reported: KeyBindings? = null

    @Test
    fun hardwareDocksAPaneListingEveryShortcut() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(HardwareTags.Open).assertIsSelected()
        onNodeWithTag(ToolPaneTags.Pane).assertExists()
        onNodeWithTag(HardwareTags.kind(HardwareKind.Keyboard)).assertIsSelected()
        onNodeWithTag(HardwareTags.kind(HardwareKind.Stylus)).assertIsNotSelected().assertIsEnabled()
        ShortcutAction.entries.forEach { action ->
            onNodeWithTag(HardwareTags.shortcut(action)).performScrollTo()
                .assertTextContains(action.label)
                .assertTextContains(KeyBindings.Default.primary(action)!!.label)
            onNodeWithTag(HardwareTags.reset(action)).assertDoesNotExist()
        }
        onNodeWithTag(HardwareTags.ResetAll).performScrollTo().assertIsNotEnabled()

        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
    }

    /**
     * Android's Stylus page: *Let a finger draw*, off until chosen. The choice reaches the host at
     * once, and with it on a finger writes with the pen instead of moving the page.
     */
    @Test
    fun stylusLetsAFingerDrawOnceChosen() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo().copy(activeTab = RibbonTab.Settings).toggleInkTool(InkTool.Pen)
        var saved: InputSettings? = null
        setContent {
            var state by remember { mutableStateOf(observed) }
            var input by remember { mutableStateOf(InputSettings()) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); observed = state },
                    inputSettings = input, onInputSettingsChange = { input = it; saved = it })
            }
        }
        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(HardwareTags.kind(HardwareKind.Stylus)).performClick().assertIsSelected()
        onNodeWithTag(HardwareTags.Shortcuts).assertDoesNotExist()
        onNodeWithTag(HardwareTags.FingerDraws).assertIsOff()

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
            down(Offset(300f, 500f)); repeat(6) { moveBy(Offset(15f, 0f), delayMillis = 20) }; up()
        }
        runOnIdle { assertTrue(observed.pendingInkEdits.isEmpty()) }

        onNodeWithTag(HardwareTags.FingerDraws).performClick().assertIsOn()
        runOnIdle { assertEquals(InputSettings(drawWithFinger = true), saved) }
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
            down(Offset(300f, 500f)); repeat(6) { moveBy(Offset(15f, 0f), delayMillis = 20) }; up()
        }
        runOnIdle { assertEquals(1, observed.pendingInkEdits.size) }

        onNodeWithTag(HardwareTags.kind(HardwareKind.Keyboard)).performClick()
        onNodeWithTag(HardwareTags.Shortcuts).assertExists()
    }

    /**
     * The pen's rows below *Let a finger draw*: two barrel buttons and three slots for the keys a
     * pen's driver sends, empty until a key is captured. Picking a barrel's action saves it at
     * once, as the finger-drawing choice does.
     */
    @Test
    fun thePenListsItsTwoBarrelsAndThreeKeySlots() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var saved: InputSettings? = null
        setWorkspace(onInputChange = { saved = it })
        openHardware()
        onNodeWithTag(HardwareTags.kind(HardwareKind.Stylus)).performClick().assertIsSelected()

        onNodeWithTag(HardwareTags.PenBarrel).performScrollTo().assertTextContains(Strings.pen_button)
            .assertTextContains(PenAction.Lasso.label)
        onNodeWithTag(HardwareTags.PenBarrel2).assertTextContains(Strings.pen_second_button)
            .assertTextContains(PenAction.None.label)
        repeat(PenButtonSettings.KEY_SLOTS) { slot ->
            onNodeWithTag(HardwareTags.penKey(slot)).performScrollTo().assertTextContains(Strings.not_set)
            // An empty slot has no action to pick for.
            onNodeWithTag(HardwareTags.penKeyAction(slot)).assertTextContains(Strings.not_set)
                .assertIsNotEnabled()
        }

        onNodeWithTag(HardwareTags.PenBarrel).performScrollTo().performClick()
        onNodeWithTag(HardwareTags.penAction(PenAction.Eraser)).performClick()
        runOnIdle { assertEquals(InputSettings(penButtons = PenButtonSettings(barrel = PenAction.Eraser)), saved) }
        onNodeWithTag(HardwareTags.PenBarrel).assertTextContains(PenAction.Eraser.label)
    }

    /**
     * Capturing a hardware key takes its slot at once, the action beside it picks what the key
     * does, and the bound key then arms its pen from anywhere in the workspace, as a shortcut's
     * chord does. PrintScreen is a key no keyboard shortcut can take, as a pen's keys often are.
     */
    @Test
    fun aCapturedHardwareKeyArmsItsPen() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo()
        var saved: InputSettings? = null
        setWorkspace(onInputChange = { saved = it }) { observed = it }
        openHardware()
        onNodeWithTag(HardwareTags.kind(HardwareKind.Stylus)).performClick()

        onNodeWithTag(HardwareTags.penKey(0)).performScrollTo().performClick()
        onNodeWithTag(ShortcutDialogTags.PenKeyCapture).assertExists()
        press(listOf(Key.PrintScreen))

        onNodeWithTag(ShortcutDialogTags.PenKeyCapture).assertDoesNotExist()
        val printScreen = PenKeyBinding(Key.PrintScreen.keyCode, Key.PrintScreen.penKeyLabel(), PenAction.Undo)
        runOnIdle { assertEquals(InputSettings(penButtons = PenButtonSettings().withKey(0, printScreen)), saved) }
        onNodeWithTag(HardwareTags.penKey(0)).assertTextContains(Key.PrintScreen.penKeyLabel())
        onNodeWithTag(HardwareTags.penKeyAction(0)).assertTextContains(PenAction.Undo.label).assertIsEnabled()

        onNodeWithTag(HardwareTags.penKeyAction(0)).performClick()
        onNodeWithTag(HardwareTags.penAction(PenAction.Pen2)).performClick()
        runOnIdle { assertEquals(PenAction.Pen2, saved?.penButtons?.key(0)?.action) }

        press(listOf(Key.PrintScreen))
        runOnIdle {
            assertEquals(RibbonTab.Draw, observed.activeTab)
            assertEquals(InkTool.Pen, observed.inkTool)
            assertEquals(1, observed.activePenIndex)
        }
    }

    /** A key another slot already has waits for Replace: the key moves, and the slot it left is empty. */
    @Test
    fun aKeyAnotherSlotHasIsOnlyTakenOnceReplaceIsChosen() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var saved: InputSettings? = null
        val printScreen = PenKeyBinding(Key.PrintScreen.keyCode, Key.PrintScreen.penKeyLabel(), PenAction.Pen1)
        val withKey = InputSettings(penButtons = PenButtonSettings().withKey(0, printScreen))
        setWorkspace(input = withKey, onInputChange = { saved = it })
        openHardware()
        onNodeWithTag(HardwareTags.kind(HardwareKind.Stylus)).performClick()

        onNodeWithTag(HardwareTags.penKey(1)).performScrollTo().performClick()
        press(listOf(Key.PrintScreen))
        onNodeWithTag(ShortcutDialogTags.PenKeyCaptured, useUnmergedTree = true).onChild()
            .assertTextContains(Key.PrintScreen.penKeyLabel())
        onNodeWithTag(ShortcutDialogTags.PenKeyMessage, useUnmergedTree = true)
            .assertTextContains(Strings.pen_hardware_key_value(1), substring = true)
        runOnIdle { assertNull(saved, "a key in use was taken without asking") }

        onNodeWithTag(ShortcutDialogTags.PenKeyReplace).performClick()
        onNodeWithTag(ShortcutDialogTags.PenKeyCapture).assertDoesNotExist()
        // The key moved with its action, and the slot it came from is empty.
        runOnIdle { assertEquals(PenButtonSettings().withKey(1, printScreen), saved?.penButtons) }
        onNodeWithTag(HardwareTags.penKey(0)).performScrollTo().assertTextContains(Strings.not_set)
        onNodeWithTag(HardwareTags.penKey(1)).assertTextContains(Key.PrintScreen.penKeyLabel())
        onNodeWithTag(HardwareTags.penKeyAction(1)).assertTextContains(PenAction.Pen1.label)
    }

    /** Esc leaves a slot as it was; Backspace clears its key, as it disables a shortcut. */
    @Test
    fun escCancelsAndBackspaceClearsAPenKey() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var saved: InputSettings? = null
        val printScreen = PenKeyBinding(Key.PrintScreen.keyCode, Key.PrintScreen.penKeyLabel(), PenAction.Eraser)
        val withKey = InputSettings(penButtons = PenButtonSettings().withKey(2, printScreen))
        setWorkspace(input = withKey, onInputChange = { saved = it })
        openHardware()
        onNodeWithTag(HardwareTags.kind(HardwareKind.Stylus)).performClick()

        onNodeWithTag(HardwareTags.penKey(2)).performScrollTo().performClick()
        press(listOf(Key.Escape))
        onNodeWithTag(ShortcutDialogTags.PenKeyCapture).assertDoesNotExist()
        runOnIdle { assertNull(saved) }

        onNodeWithTag(HardwareTags.penKey(2)).performClick()
        press(listOf(Key.Backspace))
        onNodeWithTag(ShortcutDialogTags.PenKeyCapture).assertDoesNotExist()
        runOnIdle { assertEquals(InputSettings(), saved) }
        onNodeWithTag(HardwareTags.penKey(2)).assertTextContains(Strings.not_set)
        onNodeWithTag(HardwareTags.penKeyAction(2)).assertTextContains(Strings.not_set).assertIsNotEnabled()
    }

    /** A mouse is not a pen: its right button keeps the page's menus and never arms a barrel. */
    @Test
    fun theMouseSRightButtonIsNotThePenSBarrel() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { rightClick(Offset(700f, 500f)) }
        runOnIdle { assertNull(observed.inkTool) }
    }

    /** The pen's motor is on until its owner turns it off, and the choice is saved at once. */
    @Test
    fun thePenSMotorIsOnUntilTurnedOff() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var saved: InputSettings? = null
        setWorkspace(onInputChange = { saved = it })
        openHardware()
        onNodeWithTag(HardwareTags.kind(HardwareKind.Stylus)).performClick()

        onNodeWithTag(HardwareTags.PenHaptics).performScrollTo().assertIsOn()
        onNodeWithTag(HardwareTags.PenHaptics).performClick().assertIsOff()
        runOnIdle { assertEquals(InputSettings(penHaptics = false), saved) }

        onNodeWithTag(HardwareTags.PenHaptics).performClick().assertIsOn()
        runOnIdle { assertEquals(InputSettings(penHaptics = true), saved) }
    }

    /**
     * A pen key that runs a command clicks the pen's own motor, once per press that changes
     * something: pressing the same pen again while it is already in hand is not a click.
     */
    @Test
    fun aPenCommandClicksThePenSMotor() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo()
        val printScreen = PenKeyBinding(Key.PrintScreen.keyCode, Key.PrintScreen.penKeyLabel(), PenAction.Pen2)
        val haptics = FakePenHaptics()
        setWorkspace(
            input = InputSettings(penButtons = PenButtonSettings().withKey(0, printScreen)),
            penHaptics = haptics,
        ) { observed = it }

        press(listOf(Key.PrintScreen))
        runOnIdle {
            assertEquals(1, observed.activePenIndex)
            assertEquals(1, haptics.clicks)
        }

        press(listOf(Key.PrintScreen))
        runOnIdle { assertEquals(1, haptics.clicks) }
    }

    /** One pane at a time, as on Android: Paper Size and Hardware replace each other. */
    @Test
    fun hardwareAndPaperSizeShareTheDock() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.View)).performClick()
        onNodeWithTag(com.vivenotes.ui.ribbon.view.ViewRibbonTags.PaperSize).performClick()
        onNodeWithTag(HardwareTags.Shortcuts).assertDoesNotExist()
        onNodeWithTag(ToolPaneTags.field("Size")).assertExists()
    }

    @Test
    fun pressingNewKeysChangesTheShortcutAndResetRestoresIt() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).performClick()
        onNodeWithTag(ShortcutDialogTags.Capture).assertExists()
        press(listOf(Key.CtrlLeft, Key.ShiftLeft, Key.U))

        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        val ctrlShiftU = KeyChord(ShortcutKey.U, ctrl = true, shift = true)
        runOnIdle { assertEquals(listOf(ctrlShiftU), reported!!.chords(ShortcutAction.Undo)) }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).assertTextContains("Ctrl+Shift+U")
        onNodeWithTag(HardwareTags.ResetAll).assertIsEnabled()

        onNodeWithTag(HardwareTags.reset(ShortcutAction.Undo)).performClick()
        runOnIdle { assertEquals(KeyBindings.Default, reported) }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).assertTextContains("Ctrl+Z")
        onNodeWithTag(HardwareTags.reset(ShortcutAction.Undo)).assertDoesNotExist()
    }

    @Test
    fun aChordInUseIsOnlyTakenOnceReplaceIsChosen() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).performClick()
        press(listOf(Key.CtrlLeft, Key.R))

        onNodeWithTag(ShortcutDialogTags.Captured, useUnmergedTree = true).onChild().assertTextContains("Ctrl+R")
        onNodeWithTag(ShortcutDialogTags.Message, useUnmergedTree = true).assertTextContains("Redo", substring = true)
        runOnIdle { assertNull(reported, "a chord in use was taken without asking") }
        onNodeWithTag(ShortcutDialogTags.Replace).performClick()

        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).assertTextContains("Ctrl+R")
        // Redo keeps Android's Ctrl+Shift+Z, and shows it.
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Redo)).assertTextContains("Ctrl+Shift+Z")
        onNodeWithTag(HardwareTags.reset(ShortcutAction.Redo)).assertExists()
    }

    @Test
    fun enterAlsoConfirmsReplacing() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Bold)).performScrollTo().performClick()
        press(listOf(Key.CtrlLeft, Key.I))
        press(listOf(Key.Enter))
        runOnIdle {
            assertEquals(KeyChord(ShortcutKey.I, ctrl = true), reported!!.primary(ShortcutAction.Bold))
            assertNull(reported!!.primary(ShortcutAction.Italic))
        }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Italic)).assertTextContains("Disabled")
    }

    @Test
    fun escCancelsAndBackspaceDisables() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.NewPage)).performClick()
        press(listOf(Key.Escape))
        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        runOnIdle { assertNull(reported) }

        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.NewPage)).performClick()
        press(listOf(Key.Backspace))
        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        runOnIdle { assertNull(reported!!.primary(ShortcutAction.NewPage)) }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.NewPage)).assertTextContains("Disabled")
    }

    @Test
    fun aKeyThatTypesIsRefusedWithoutAModifier() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.NewPage)).performClick()
        press(listOf(Key.ShiftLeft, Key.K))
        onNodeWithTag(ShortcutDialogTags.Capture).assertExists()
        onNodeWithTag(ShortcutDialogTags.Message, useUnmergedTree = true).assertTextContains("Add Ctrl, Alt or Super", substring = true)
        onNodeWithTag(ShortcutDialogTags.Replace).assertDoesNotExist()
        // A function key needs nothing else.
        press(listOf(Key.F6))
        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        runOnIdle { assertEquals(KeyChord(ShortcutKey.F6), reported!!.primary(ShortcutAction.NewPage)) }
    }

    /** The Text box tool may have a letter alone, which works on the canvas at once. */
    @Test
    fun theTextBoxToolTakesALetterAlone() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.TextTool)).performScrollTo()
            .assertTextContains("Text box tool").assertTextContains("I").performClick()
        press(listOf(Key.T))
        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        runOnIdle { assertEquals(KeyChord(ShortcutKey.T), reported!!.primary(ShortcutAction.TextTool)) }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.TextTool)).assertTextContains("T")

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(700f, 500f)) }
        press(listOf(Key.I))
        runOnIdle { assertFalse(observed.textToolArmed, "I still armed the tool after it moved to T") }
        press(listOf(Key.T))
        runOnIdle { assertTrue(observed.textToolArmed) }
    }

    @Test
    fun resetAllAsksAndThenRestoresEveryDefault() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val custom = KeyBindings.Default.rebind(ShortcutAction.Undo, KeyChord(ShortcutKey.U, ctrl = true))
            .rebind(ShortcutAction.Bold, null)
        setWorkspace(bindings = custom)
        openHardware()
        onNodeWithTag(HardwareTags.ResetAll).performScrollTo().performClick()
        onNodeWithTag(ShortcutDialogTags.CancelResetAll).performClick()
        onNodeWithTag(ShortcutDialogTags.ResetAll).assertDoesNotExist()
        runOnIdle { assertNull(reported) }

        onNodeWithTag(HardwareTags.ResetAll).performClick()
        onNodeWithTag(ShortcutDialogTags.ConfirmResetAll).performClick()
        runOnIdle { assertEquals(KeyBindings.Default, reported) }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Bold)).performScrollTo().assertTextContains("Ctrl+B")
        onNodeWithTag(HardwareTags.ResetAll).assertIsNotEnabled()
    }

    @Test
    fun aRebindingTakesEffectInTheWorkspaceAtOnce() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.ZoomIn)).performScrollTo().performClick()
        press(listOf(Key.CtrlLeft, Key.K))

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(700f, 500f)) }
        press(listOf(Key.CtrlLeft, Key.Equals))
        assertZoom(100)
        press(listOf(Key.CtrlLeft, Key.K))
        assertZoom(125)
    }

    @Test
    fun textBoxShortcutsFollowTheirBindings() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val clipboard = FakeClipboard()
        val ctrlShiftC = KeyChord(ShortcutKey.C, ctrl = true, shift = true)
        var observed = WorkspaceState.demo()
        setWorkspace(initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document),
            bindings = KeyBindings.Default.rebind(ShortcutAction.Copy, ctrlShiftC),
            clipboard = clipboard) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))
        val selected = runOnIdle { observed.selectedText }
        assertTrue(selected.isNotEmpty())

        // Copy moved off Ctrl+C, and the text field's own copy must not take the key back.
        press(listOf(Key.CtrlLeft, Key.C))
        runOnIdle { assertNull(clipboard.value) }
        press(listOf(Key.CtrlLeft, Key.ShiftLeft, Key.C))
        runOnIdle { assertEquals(selected, clipboard.value?.text) }

        press(listOf(Key.CtrlLeft, Key.B))
        runOnIdle { assertTrue(Mark.Bold in observed.richText!!.select(TextSelection(0, 6)).activeMarks) }

        // The right-click menu names the keys in force.
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { rightClick(Offset(15f, 10f)) }
        waitForIdle()
        assertTrue(onAllNodes(hasText("Ctrl+Shift+C") and hasAnyAncestor(isPopup())).fetchSemanticsNodes().isNotEmpty())
    }

    private fun ComposeUiTest.assertZoom(percent: Int) {
        onNodeWithTag(WorkspaceTestTags.ZoomIndicator)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Canvas zoom $percent percent"))
    }

    private fun ComposeUiTest.openHardware() {
        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(HardwareTags.Shortcuts).assertExists()
    }

    /** Holds [keys] down in order and lets them go in reverse, as fingers do. */
    private fun ComposeUiTest.press(keys: List<Key>) {
        onRoot().performKeyInput {
            keys.forEach { keyDown(it) }
            keys.reversed().forEach { keyUp(it) }
        }
    }

    private fun ComposeUiTest.setWorkspace(
        initial: WorkspaceState = WorkspaceState.demo().copy(activeTab = RibbonTab.Settings),
        bindings: KeyBindings = KeyBindings.Default,
        input: InputSettings = InputSettings(),
        clipboard: TextClipboard? = null,
        penHaptics: PenHaptics? = null,
        onInputChange: (InputSettings) -> Unit = {},
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            var inputState by remember { mutableStateOf(input) }
            val content = @androidx.compose.runtime.Composable {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) },
                    inputSettings = inputState, onInputSettingsChange = { inputState = it; onInputChange(it) },
                    keyBindings = bindings, onKeyBindingsChange = { reported = it })
            }
            ViveNotesTheme(darkTheme = true) {
                CompositionLocalProvider(LocalPenHaptics provides penHaptics) {
                    if (clipboard == null) content()
                    else CompositionLocalProvider(LocalTextClipboard provides clipboard) { content() }
                }
            }
        }
    }

    private class FakePenHaptics : PenHaptics {
        var clicks = 0
        override fun inkStart(pointerId: Long, feel: PenHapticFeel) = Unit
        override fun inkEnd() = Unit
        override fun commandClick() { clicks++ }
    }

    private class FakeClipboard : TextClipboard {
        var value: AnnotatedString? = null
        override fun getText(): AnnotatedString? = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }
}
