package com.arg_studios.arg_weaponry.weapon.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The handoff state machine, driven without a game, a renderer or an inventory.
 *
 * <p>These tests model the two identities the rewrite is built around: the <em>selected</em> slot (the fake inventory) and the
 * <em>visual</em> weapon (the fake visuals). Every requirement of the subsystem is expressed as an assertion about those two
 * and about the calls between them - which is exactly what the previous implementation could not be tested for, because the
 * visual weapon was not a concept it had.</p>
 */
class WeaponHandoffControllerTest {

    private static final WeaponIdentity RIFLE_A = new WeaponIdentity("arg:rifle_a", 0);
    private static final WeaponIdentity RIFLE_B = new WeaponIdentity("arg:rifle_b", 1);
    private static final WeaponIdentity SHOTGUN_C = new WeaponIdentity("arg:shotgun_c", 2);
    private static final WeaponIdentity PISTOL_D = new WeaponIdentity("arg:pistol_d", 3);

    private FakeHandoffEnvironment env;
    private FakeHandoffVisuals visuals;
    private WeaponHandoffController controller;

    @BeforeEach
    void setUp() {
        env = new FakeHandoffEnvironment();
        visuals = new FakeHandoffVisuals();
        controller = new WeaponHandoffController(env, visuals);
        env.weapons.put(0, RIFLE_A.itemId());
        env.weapons.put(1, RIFLE_B.itemId());
        env.weapons.put(2, SHOTGUN_C.itemId());
        env.weapons.put(3, PISTOL_D.itemId());
        env.selectedSlot = 0;
        // The player is holding a weapon that is already being drawn.
        visuals.adopt(RIFLE_A);
        visuals.calls.clear();
        visuals.adoptions.clear();
    }

    @Test
    void aSwitchHolstersHandsOffAndDraws() {
        assertTrue(controller.requestSwitch(1));
        assertEquals(HandoffStage.HOLSTERING, controller.stage());
        assertEquals(List.of(1), env.switchStarted, "the server is told once that a switch started");
        assertEquals(List.of(), env.committedSlots, "the slot must not change while the outgoing weapon is still drawn");
        assertEquals(RIFLE_A, visuals.activeIdentity(), "the outgoing weapon stays the visual weapon");

        visuals.finishHolster();
        controller.tick();

        assertEquals(HandoffStage.DRAWING, controller.stage());
        assertEquals(List.of(1), env.committedSlots, "exactly one slot commit, at the boundary");
        assertEquals(RIFLE_B, visuals.activeIdentity(), "the incoming weapon became the visual weapon");

        int adopted = visuals.calls.indexOf("adopt:" + RIFLE_B.itemId() + "@" + RIFLE_B.slot());
        int drawn = visuals.calls.indexOf("draw");
        assertTrue(adopted >= 0, "the incoming weapon must be adopted at the boundary");
        assertTrue(drawn > adopted, "the draw must start on the newly adopted weapon, not before it");
        assertEquals(RIFLE_B, visuals.drawOwner());

        visuals.finishDraw();
        controller.tick();
        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(1, visuals.settleDrawCalls, "the draw is handed back to the weapon's idle playback");
    }

    @Test
    void theTransitionUsesTheAuthoredClipLengthAsItsDeadline() {
        env.now = 40.0;
        visuals.holsterClipSeconds = 0.74;
        assertTrue(controller.requestSwitch(1));
        HandoffSession session = controller.session();
        assertEquals(0.74, session.clipSeconds(), 1.0E-9);
        assertEquals(40.0 + HandoffTiming.deadline(0.74), session.deadline(), 1.0E-9);
    }

    // ---------------------------------------------------------------- rapid switching, retargeting, cancellation

    @Test
    void rapidSwitchingRetargetsWithoutRestartingTheHolster() {
        assertTrue(controller.requestSwitch(1));
        assertTrue(controller.requestSwitch(2));
        assertTrue(controller.requestSwitch(3));

        assertEquals(HandoffStage.HOLSTERING, controller.stage());
        assertEquals(1, visuals.holsterStarts, "the holster clip is not restarted by a new request");
        assertEquals(3, controller.session().targetSlot());
        assertEquals(List.of(1, 2, 3), env.switchStarted, "the server is told about every destination");
        assertEquals(RIFLE_A, visuals.activeIdentity(), "and the outgoing weapon is still the one drawn");

        visuals.finishHolster();
        controller.tick();

        assertEquals(PISTOL_D, visuals.activeIdentity(), "only the last requested destination is adopted");
        assertEquals(List.of(3), env.committedSlots, "and it is committed exactly once");
    }

    @Test
    void pressingTheCurrentSlotAgainCancelsTheHolster() {
        assertTrue(controller.requestSwitch(1));
        assertTrue(controller.requestSwitch(0));

        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(1, visuals.stopHolsterCalls, "the holster clip is stopped");
        assertEquals(1, env.switchCancelled, "the server is told the switch was abandoned");
        assertEquals(RIFLE_A, visuals.activeIdentity(), "the weapon stays the visual weapon");
        assertEquals(List.of(), env.committedSlots, "and nothing is committed");
    }

    @Test
    void switchingWhileDrawingHolstersTheWeaponThatIsBeingDrawn() {
        assertTrue(controller.requestSwitch(1));
        visuals.finishHolster();
        controller.tick();
        assertEquals(HandoffStage.DRAWING, controller.stage());

        assertTrue(controller.requestSwitch(2));
        assertEquals(HandoffStage.HOLSTERING, controller.stage());
        assertEquals(2, visuals.holsterStarts, "a fresh holster starts on the weapon that was being drawn");
        assertEquals(RIFLE_B, visuals.activeIdentity(), "and that weapon is still the visual one");
        assertEquals(RIFLE_B, controller.session().outgoing());

        visuals.finishHolster();
        controller.tick();
        assertEquals(SHOTGUN_C, visuals.activeIdentity());
        assertEquals(List.of(1, 2), env.committedSlots, "one commit per completed transition");
    }

    @Test
    void pressingTheCurrentSlotWhileDrawingChangesNothing() {
        assertTrue(controller.requestSwitch(1));
        visuals.finishHolster();
        controller.tick();

        assertFalse(controller.requestSwitch(1), "pressing the slot that is already being drawn needs no deferral");
        assertEquals(HandoffStage.DRAWING, controller.stage());
        assertEquals(RIFLE_B, visuals.activeIdentity());
    }

    // ---------------------------------------------------------------- missing clips and empty destinations

    @Test
    void aWeaponWithoutAHolsterClipHandsOffImmediately() {
        visuals.holsterClipSeconds = 0.0;
        assertTrue(controller.requestSwitch(1));

        assertEquals(RIFLE_B, visuals.activeIdentity(), "there is no clip to wait for, so the boundary is immediate");
        assertEquals(List.of(1), env.committedSlots);
        assertEquals(0, visuals.holsterStarts);
    }

    @Test
    void aWeaponWithoutADrawClipIsIdleAfterTheBoundary() {
        visuals.drawClipSeconds = 0.0;
        assertTrue(controller.requestSwitch(1));
        visuals.finishHolster();
        controller.tick();

        assertEquals(HandoffStage.IDLE, controller.stage(), "nothing is left running");
        assertEquals(RIFLE_B, visuals.activeIdentity());
        assertEquals(0, visuals.drawStarts);
        assertEquals(0, visuals.settleDrawCalls);
    }

    @Test
    void aDestinationWithoutAWeaponReleasesTheVisualWeapon() {
        // Slot 4 holds nothing, so the destination identity is NONE.
        assertTrue(controller.requestSwitch(4));
        visuals.finishHolster();
        controller.tick();

        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(WeaponIdentity.NONE, visuals.activeIdentity(), "rendering falls back to the live hand");
        assertEquals(WeaponIdentity.NONE, visuals.adoptions.get(visuals.adoptions.size() - 1),
                "the outgoing weapon was retired by adopting the empty hand");
    }

    // ---------------------------------------------------------------- recovery, deadlines, stale state

    @Test
    void aClipThatNeverFinishesIsCommittedAtItsDeadline() {
        visuals.holsterClipSeconds = 0.5;
        env.now = 10.0;
        assertTrue(controller.requestSwitch(1));

        env.now += 0.5;
        controller.tick();
        assertEquals(HandoffStage.HOLSTERING, controller.stage(), "still inside the clip");

        env.now += HandoffTiming.deadline(0.5) - 0.5 + 0.01;
        controller.tick();
        assertEquals(HandoffStage.DRAWING, controller.stage(), "the deadline commits the transition");
        assertEquals(List.of(1), env.committedSlots);
    }

    @Test
    void aDrawThatNeverFinishesIsSettledAtItsDeadline() {
        visuals.drawClipSeconds = 0.2;
        assertTrue(controller.requestSwitch(1));
        visuals.finishHolster();
        controller.tick();
        assertEquals(HandoffStage.DRAWING, controller.stage());

        env.now += HandoffTiming.deadline(0.2) + 0.01;
        controller.tick();
        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(1, visuals.settleDrawCalls, "the weapon returns to idle instead of staying mid-draw");
    }

    @Test
    void aCompletionIsOnlyActedOnOnce() {
        assertTrue(controller.requestSwitch(1));
        visuals.finishHolster();
        controller.tick();
        controller.tick();
        controller.tick();

        assertEquals(1, env.committedSlots.size(), "further ticks must not commit the slot again");
        assertEquals(1, visuals.adoptions.size(), "and must not adopt the incoming weapon again");
        assertEquals(HandoffStage.DRAWING, controller.stage());
    }

    @Test
    void aRequestDuringAHigherPriorityActionWaitsForIt() {
        env.actionBusy = true;
        assertTrue(controller.requestSwitch(1));
        assertEquals(HandoffStage.IDLE, controller.stage(), "the reload keeps playing");
        assertEquals(0, visuals.holsterStarts);

        controller.tick();
        assertEquals(HandoffStage.IDLE, controller.stage(), "still blocked");

        env.actionBusy = false;
        controller.tick();
        assertEquals(HandoffStage.HOLSTERING, controller.stage(), "and then the switch proceeds");
        assertEquals(1, visuals.holsterStarts);
    }

    @Test
    void aSelectionChangedOutsideTheInputPathIsRepairedWithoutASecondCommit() {
        // Something else switched the slot: the live identity is now B while A is still drawn.
        env.selectedSlot = 1;
        controller.tick();

        assertEquals(HandoffStage.HOLSTERING, controller.stage(), "the outgoing weapon still plays its holster");
        assertEquals(RIFLE_A, visuals.activeIdentity());
        assertFalse(controller.session().deferred(), "nothing is deferred: the slot already changed");

        visuals.finishHolster();
        controller.tick();

        assertEquals(RIFLE_B, visuals.activeIdentity());
        assertEquals(List.of(), env.committedSlots, "no second commit");
    }

    @Test
    void losingThePlayerResetsEverything() {
        assertTrue(controller.requestSwitch(1));
        env.canTransition = false;
        controller.tick();

        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(WeaponIdentity.NONE, visuals.activeIdentity(), "no visual weapon survives the reset");
        assertEquals(1, visuals.releases);
    }

    @Test
    void resettingDropsTheSessionAndTheVisualWeapon() {
        assertTrue(controller.requestSwitch(1));
        controller.reset();

        assertEquals(HandoffStage.IDLE, controller.stage());
        assertNull(controller.session());
        assertEquals(WeaponIdentity.NONE, visuals.activeIdentity());
        assertEquals(1, visuals.releases);

        // A later request starts from a clean state and, with no visual weapon, is left to vanilla.
        assertFalse(controller.requestSwitch(2));
    }

    @Test
    void aRequestWithNoVisualWeaponIsLeftToVanilla() {
        controller.reset();
        assertFalse(controller.requestSwitch(1), "nothing is adopted, so there is nothing to holster");
        assertEquals(List.of(), env.switchStarted);
    }

    @Test
    void switchingBetweenSlotsHoldingTheSameItemIsLeftToVanilla() {
        // Slot 1 now holds the same item as the selected slot 0: the two stacks look identical, so there is nothing to hide
        // or draw and the switch must not be deferred (Point Blank's matchesGunIdentity rule).
        env.weapons.put(1, RIFLE_A.itemId());
        assertFalse(controller.requestSwitch(1), "identical stacks switch instantly");
        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(List.of(), env.switchStarted);
        assertEquals(List.of(), env.committedSlots);
        assertEquals(RIFLE_A, visuals.activeIdentity(), "the visual weapon does not change");
    }
}
