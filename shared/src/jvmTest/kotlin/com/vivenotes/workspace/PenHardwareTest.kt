package com.vivenotes.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pen's buttons and keys, as far as their rules take them: what a bound action arms, which
 * barrel press runs, and the settings model that survives its file. No test framework can
 * synthesize a pen, so the part that handles its events lives in HardwarePaneTest and
 * ShortcutKeysTest; these hold the decisions the handlers make.
 */
class PenHardwareTest {

    @Test
    fun togglingSwapsBetweenThePenInHandAndTheEraser() {
        assertEquals(InkTool.Eraser to 1, inkToolFrom(PenAction.TogglePenEraser, InkTool.Pen, 1))
        assertEquals(InkTool.Pen to 2, inkToolFrom(PenAction.TogglePenEraser, InkTool.Eraser, 2))
        // Anything else in hand reaches back for the pen last in hand, to carry on in it.
        assertEquals(InkTool.Pen to 1, inkToolFrom(PenAction.TogglePenEraser, InkTool.Highlighter, 1))
        assertEquals(InkTool.Pen to 1, inkToolFrom(PenAction.TogglePenEraser, null, 1))
    }

    @Test
    fun cyclingWalksTheThreePensAndWraps() {
        assertEquals(InkTool.Pen to 1, inkToolFrom(PenAction.CyclePens, InkTool.Pen, 0))
        assertEquals(InkTool.Pen to 2, inkToolFrom(PenAction.CyclePens, InkTool.Pen, 1))
        assertEquals(InkTool.Pen to 0, inkToolFrom(PenAction.CyclePens, InkTool.Pen, 2))
        // Not holding a pen: the first one.
        assertEquals(InkTool.Pen to 0, inkToolFrom(PenAction.CyclePens, InkTool.Eraser, 2))
    }

    @Test
    fun pensAndToolsAreReachedForDirectly() {
        assertEquals(InkTool.Pen to 0, inkToolFrom(PenAction.Pen1, InkTool.Eraser, 2))
        assertEquals(InkTool.Pen to 1, inkToolFrom(PenAction.Pen2, null, 0))
        assertEquals(InkTool.Pen to 2, inkToolFrom(PenAction.Pen3, InkTool.Pen, 0))
        // The highlighter and the eraser keep the pen in hand, to go back to.
        assertEquals(InkTool.Highlighter to 1, inkToolFrom(PenAction.Highlighter, InkTool.Pen, 1))
        assertEquals(InkTool.Eraser to 2, inkToolFrom(PenAction.Eraser, InkTool.Pen, 2))
    }

    @Test
    fun someActionsArmNothing() {
        for (action in listOf(PenAction.None, PenAction.Undo, PenAction.Redo, PenAction.Lasso)) {
            assertNull(inkToolFrom(action, InkTool.Pen, 1), "$action armed a tool")
        }
    }

    @Test
    fun theBarrelRunsOnThePressThatGoesDown() {
        val buttons = PenButtonSettings(barrel = PenAction.Eraser, barrel2 = PenAction.Lasso)
        assertEquals(PenAction.Eraser, penBarrelAction(false, false, true, false, buttons))
        assertEquals(PenAction.Lasso, penBarrelAction(false, false, false, true, buttons))
        // Both going down at once counts as the lower barrel's press.
        assertEquals(PenAction.Eraser, penBarrelAction(false, false, true, true, buttons))
        // A button already held, or let go, ran when it went down and not again.
        assertNull(penBarrelAction(true, false, true, false, buttons))
        assertNull(penBarrelAction(true, true, true, true, buttons))
        assertNull(penBarrelAction(true, false, false, false, buttons))
        assertNull(penBarrelAction(false, false, false, false, buttons))
    }

    @Test
    fun bindingsSurviveTheirEncoding() {
        val binding = PenKeyBinding(1337, "Print Screen", PenAction.Pen2)
        assertEquals(binding, PenKeyBinding.decode(binding.encode()))
        // A label may itself contain bars, as some keyboards name keys.
        val barred = PenKeyBinding(42, "Left |Bar| Right", PenAction.Undo)
        assertEquals(barred, PenKeyBinding.decode(barred.encode()))
        // Junk is not a binding.
        assertNull(PenKeyBinding.decode(""))
        assertNull(PenKeyBinding.decode("no bars"))
        assertNull(PenKeyBinding.decode("|no code|Pen1"))
        assertNull(PenKeyBinding.decode("abc|label|Pen1"))
        assertNull(PenKeyBinding.decode("1|label|NoSuchAction"))
    }

    @Test
    fun actionsAreDecodedByName() {
        assertEquals(PenAction.Undo, PenAction.decode("Undo"))
        assertNull(PenAction.decode("NoSuchAction"))
    }

    @Test
    fun aKeyLivesInOneSlotOnly() {
        val base = PenButtonSettings(keys = listOf(PenKeyBinding(10, "A", PenAction.Pen1), null, null))
        val moved = base.withKey(1, PenKeyBinding(10, "A", PenAction.Pen2))
        assertNull(moved.key(0), "the key stayed bound in the slot it came from")
        assertEquals(PenKeyBinding(10, "A", PenAction.Pen2), moved.key(1))
        // Clearing one slot leaves the rest of the settings alone.
        assertEquals(PenButtonSettings(), moved.withKey(1, null))
    }

    @Test
    fun aKeyIsFoundInWhicheverSlotHoldsIt() {
        val buttons = PenButtonSettings(
            keys = listOf(null, PenKeyBinding(10, "A", PenAction.Pen1), PenKeyBinding(20, "B", PenAction.Undo)))
        assertEquals(PenKeyBinding(10, "A", PenAction.Pen1), buttons.bindingFor(10))
        assertNull(buttons.bindingFor(30))
        // Compose's "unknown key" is no key at all.
        assertNull(buttons.bindingFor(0))
        assertEquals(2, buttons.keyConflict(1, 20))
        // Its own slot is not a conflict, and neither is a key no slot has.
        assertNull(buttons.keyConflict(2, 20))
        assertNull(buttons.keyConflict(0, 30))
    }

    @Test
    fun anActionFollowsItsKey() {
        val buttons = PenButtonSettings(keys = listOf(PenKeyBinding(10, "A", PenAction.Pen1), null, null))
        assertEquals(PenKeyBinding(10, "A", PenAction.Eraser), buttons.withKeyAction(0, PenAction.Eraser).key(0))
        // An empty slot has nothing to set.
        assertEquals(buttons, buttons.withKeyAction(1, PenAction.Eraser))
    }

    @Test
    fun armingTakesTheDrawTabAndTheTool() {
        val armed = WorkspaceState.demo().armPenAction(PenAction.Pen2)
        assertEquals(RibbonTab.Draw, armed.activeTab)
        assertEquals(InkTool.Pen, armed.inkTool)
        assertEquals(1, armed.activePenIndex)
    }

    @Test
    fun togglingCarriesOnInThePenLastInHand() {
        val pen = WorkspaceState.demo().armPenAction(PenAction.Pen2)
        val erased = pen.armPenAction(PenAction.TogglePenEraser)
        assertEquals(InkTool.Eraser, erased.inkTool)
        assertEquals(1, erased.activePenIndex)
        val back = erased.armPenAction(PenAction.TogglePenEraser)
        assertEquals(InkTool.Pen, back.inkTool)
        assertEquals(1, back.activePenIndex)
    }

    @Test
    fun theLassoArmsAndThenDisarms() {
        val armed = WorkspaceState.demo().armPenAction(PenAction.Lasso)
        assertTrue(armed.objectLassoArmed)
        assertEquals(RibbonTab.Draw, armed.activeTab)
        assertFalse(armed.armPenAction(PenAction.Lasso).objectLassoArmed)
    }

    @Test
    fun pressesThatArmNothingChangeNothing() {
        val state = WorkspaceState.demo().selectInkTool(InkTool.Pen, 1)
        assertEquals(state, state.armPenAction(PenAction.None))
        assertEquals(state, state.armPenAction(PenAction.Undo))
        assertEquals(state, state.armPenAction(PenAction.Redo))
    }

    @Test
    fun pressesThatCameTooEarlyChangeNothing() {
        val noPage = WorkspaceState(notebooks = emptyList(), selectedNotebookId = "",
            selectedSectionId = "", selectedPageId = "")
        assertEquals(noPage, noPage.armPenAction(PenAction.Pen2))
        val transferring = WorkspaceState.demo().copy(notebookTransfer = NotebookTransferState(running = true))
        assertEquals(transferring, transferring.armPenAction(PenAction.Pen2))
        val busyPane = WorkspaceState.demo().copy(filePane = FilePaneState(busy = true))
        assertEquals(busyPane, busyPane.armPenAction(PenAction.Pen2))
    }
}
