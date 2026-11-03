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
        assertEquals(RIFLE_A, visuals.visualIdentity(), "the outgoing weapon stays the visual weapon");

        visuals.finishHolster();
        controller.tick();

        assertEquals(HandoffStage.DRAWING, controller.stage());
        assertEquals(List.of(1), env.committedSlots, "exactly one slot commit, at the boundary");
        assertEquals(RIFLE_B, visuals.visualIdentity(), "the incoming weapon became the visual weapon");

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
        assertEquals(RIFLE_A, visuals.visualIdentity(), "and the outgoing weapon is still the one drawn");

        visuals.finishHolster();
        controller.tick();

        assertEquals(PISTOL_D, visuals.visualIdentity(), "only the last requested destination is adopted");
        assertEquals(List.of(3), env.committedSlots, "and it is committed exactly once");
    }

    @Test
    void pressingTheCurrentSlotAgainCancelsTheHolster() {
        assertTrue(controller.requestSwitch(1));
        assertTrue(controller.requestSwitch(0));

        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(1, visuals.stopHolsterCalls, "the holster clip is stopped");
        assertEquals(1, env.switchCancelled, "the server is told the switch was abandoned");
        assertEquals(RIFLE_A, visuals.visualIdentity(), "the weapon stays the visual weapon");
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
        assertEquals(RIFLE_B, visuals.visualIdentity(), "and that weapon is still the visual one");
        assertEquals(RIFLE_B, controller.session().outgoing());

        visuals.finishHolster();
        controller.tick();
        assertEquals(SHOTGUN_C, visuals.visualIdentity());
        assertEquals(List.of(1, 2), env.committedSlots, "one commit per completed transition");
    }

    @Test
    void pressingTheCurrentSlotWhileDrawingChangesNothing() {
        assertTrue(controller.requestSwitch(1));
        visuals.finishHolster();
        controller.tick();

        assertFalse(controller.requestSwitch(1), "pressing the slot that is already being drawn needs no deferral");
        assertEquals(HandoffStage.DRAWING, controller.stage());
        assertEquals(RIFLE_B, visuals.visualIdentity());
    }

    // ---------------------------------------------------------------- missing clips and empty destinations

    @Test
    void aWeaponWithoutAHolsterClipHandsOffImmediately() {
        visuals.holsterClipSeconds = 0.0;
        assertTrue(controller.requestSwitch(1));

        assertEquals(RIFLE_B, visuals.visualIdentity(), "there is no clip to wait for, so the boundary is immediate");
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
        assertEquals(RIFLE_B, visuals.visualIdentity());
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
        assertEquals(WeaponIdentity.NONE, visuals.visualIdentity(), "rendering falls back to the live hand");
        assertEquals(WeaponIdentity.NONE, visuals.adoptions.get(visuals.adoptions.size() - 1),
                "the outgoing weapon was retired by adopting the empty hand");
    }

    // ---------------------------------------------------------------- drawing a weapon with nothing to holster

    /**
     * The case the handoff-only implementation lost: the selection moves onto a weapon while nothing is drawn, so there is
     * no holster half and the weapon must still be raised with its draw clip instead of appearing in its idle pose.
     */
    @Test
    void aWeaponThatBecomesHeldWithNothingDrawnPlaysItsDraw() {
        controller.reset();
        controller.tick();
        assertEquals(HandoffStage.IDLE, controller.stage(), "the first look at the hand is not a draw");

        // The selection moves to another weapon by a path the input interception did not see; vanilla applied it already.
        env.selectedSlot = 1;
        controller.tick();

        assertEquals(HandoffStage.DRAWING, controller.stage(), "the weapon that became held is drawn");
        assertEquals(RIFLE_B, visuals.visualIdentity(), "and it is the adopted visual weapon");
        assertEquals(1, visuals.drawStarts, "its draw clip plays");
        assertEquals(RIFLE_B, visuals.drawOwner(), "on the weapon that was just adopted");
        assertEquals(0, visuals.holsterStarts, "there is nothing to holster");
        assertEquals(List.of(), env.committedSlots, "the slot was already applied, so nothing is committed again");
        assertEquals(List.of(), env.switchStarted, "and the server is not told about a delayed switch");

        visuals.finishDraw();
        controller.tick();

        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(1, visuals.settleDrawCalls, "the draw hands the weapon back to its idle playback");
    }

    @Test
    void aDrawWithoutAHolsterBlocksActionsForTheLengthOfItsClip() {
        controller.reset();
        controller.tick();
        env.selectedSlot = 1;
        controller.tick();
        assertTrue(controller.isTransitioning(), "the draw owns the hand while it plays");

        visuals.finishDraw();
        controller.tick();
        assertFalse(controller.isTransitioning(), "and releases it when it ends");
    }

    @Test
    void theFirstTickAfterAResetOnlyObservesTheSelection() {
        controller.reset();
        controller.tick();

        assertEquals(HandoffStage.IDLE, controller.stage(), "a weapon already in the hand is not a draw");
        assertEquals(0, visuals.drawStarts);
        assertEquals(0, visuals.adoptions.size(), "and nothing is adopted or re-adopted by the observation");
    }

    @Test
    void aWeaponWithoutADrawClipThatBecomesHeldIsAdoptedWithoutASession() {
        visuals.drawClipSeconds = 0.0;
        controller.reset();
        controller.tick();
        env.selectedSlot = 1;
        controller.tick();

        assertEquals(RIFLE_B, visuals.visualIdentity(), "the weapon is drawn in its idle playback");
        assertEquals(HandoffStage.IDLE, controller.stage(), "with no clip, nothing is left running");
        assertEquals(0, visuals.drawStarts);
    }

    @Test
    void aSwitchOntoANonWeaponSlotDoesNotStartADraw() {
        controller.reset();
        controller.tick();
        // Slot 4 holds nothing at all.
        env.selectedSlot = 4;
        controller.tick();

        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(WeaponIdentity.NONE, visuals.visualIdentity());
        assertEquals(0, visuals.drawStarts);
    }

    /**
     * A request that has nothing to holster is left to vanilla - the switch itself must stay instant - and the draw of the
     * weapon it selects is started by the tick that follows, so it begins on the same client tick as the input.
     */
    @Test
    void aSwitchRequestWithNothingDrawnIsAppliedByVanillaAndDrawnByTheTick() {
        controller.reset();
        controller.tick();

        assertFalse(controller.requestSwitch(1), "there is nothing to hide, so vanilla applies the switch");
        assertEquals(List.of(), env.switchStarted);

        // Vanilla applies the slot during the same client tick, and the controller's tick runs after it.
        env.selectedSlot = 1;
        controller.tick();

        assertEquals(HandoffStage.DRAWING, controller.stage());
        assertEquals(RIFLE_B, visuals.visualIdentity());
        assertEquals(1, visuals.drawStarts);
    }

    /**
     * A scroll applies the slot change between two ticks, so the very next rendered frame can reach the visual registry
     * before the controller's tick does. The weapon is then already the drawn one and must still be raised - on the
     * animator that is on screen, so the draw cross-fades in instead of the weapon simply sitting in its idle clip.
     */
    @Test
    void aWeaponAFrameAdoptedBeforeTheTickIsStillRaised() {
        controller.reset();
        controller.tick();

        // The frame after the switch adopted the incoming weapon before the tick could see the change.
        visuals.adopt(RIFLE_B);
        env.selectedSlot = 1;
        controller.tick();

        assertEquals(HandoffStage.DRAWING, controller.stage(), "the weapon that became held is raised");
        assertEquals(1, visuals.drawStarts);
        assertEquals(RIFLE_B, visuals.drawOwner(), "the clip starts on the weapon that is already drawn");
        assertEquals(1, visuals.adoptions.size(), "and that weapon is not adopted a second time");
    }

    @Test
    void aHolsterWhoseWeaponIsGoneCommitsInsteadOfWaitingForTheDeadline() {
        assertTrue(controller.requestSwitch(1));
        assertEquals(HandoffStage.HOLSTERING, controller.stage());

        // The renderer released the visual weapon - the stack left the hand - so its holster clip can never report the end
        // of the transition that would normally commit the switch.
        visuals.release();
        controller.tick();

        assertEquals(List.of(1), env.committedSlots, "the waiting switch is applied at once");
        assertEquals(HandoffStage.DRAWING, controller.stage(), "and the weapon that is now held draws");
    }

    @Test
    void aDrawWhoseWeaponIsGoneEndsTheTransitionAtOnce() {
        controller.reset();
        controller.tick();
        env.selectedSlot = 1;
        controller.tick();
        assertEquals(HandoffStage.DRAWING, controller.stage());

        visuals.release();
        controller.tick();

        assertEquals(HandoffStage.IDLE, controller.stage(),
                "no clip can finish for a weapon that is not drawn, so the hand must be released instead of waiting for "
                        + "the safety deadline");
        assertFalse(controller.isTransitioning());
    }

    @Test
    void theIncomingDrawIsArmedUntilTheFrameThatDrawsIt() {
        assertTrue(controller.requestSwitch(1));
        visuals.finishHolster();
        controller.tick();

        assertEquals(HandoffStage.DRAWING, controller.stage());
        assertTrue(visuals.drawArmed, "the incoming draw is armed, paused at its first frame");
        assertFalse(visuals.drawReleased, "and nothing has drawn it yet");

        // A frame that draws another weapon must not release it: the notification is generation-guarded like every other
        // boundary.
        controller.notifyWeaponRendered(RIFLE_A);
        assertFalse(visuals.drawReleased, "a stale frame releases nothing");

        controller.notifyWeaponRendered(RIFLE_B);
        assertTrue(visuals.drawReleased, "the frame that really draws the weapon starts the raise on that frame");
        assertEquals(HandoffStage.DRAWING, controller.stage(), "only the playback started, the window is unchanged");

        visuals.finishDraw();
        controller.tick();
        assertEquals(HandoffStage.IDLE, controller.stage());
    }

    @Test
    void aDrawNoFrameEverDrawsIsReleasedByTheTick() {
        assertTrue(controller.requestSwitch(1));
        visuals.finishHolster();
        controller.tick();
        assertTrue(visuals.drawArmed);

        // Third person, an open screen or a hidden hand: no first-person frame draws the weapon at all, so the pause must
        // not outlive a couple of ticks.
        controller.tick();
        controller.tick();
        assertFalse(visuals.drawReleased, "two ticks are still inside the safety window");

        controller.tick();
        assertTrue(visuals.drawReleased, "the raise is released anyway, so the hand can never stay frozen");
        assertEquals(HandoffStage.DRAWING, controller.stage(), "and the session still ends on its own deadline");
    }

    @Test
    void theFrameBetweenTwoTicksObservesTheSelectionAndArmsTheDraw() {
        controller.reset();
        controller.tick();

        // A scroll key applies the slot change between two ticks; the frame that follows it must already draw the weapon.
        env.selectedSlot = 2;
        controller.observeBeforeRender();

        assertEquals(HandoffStage.DRAWING, controller.stage(), "the frame after the switch raises the weapon");
        assertEquals(SHOTGUN_C, visuals.visualIdentity());
        assertTrue(visuals.drawArmed, "and the clip waits for that frame instead of running unseen");
    }

    @Test
    void theIdleTickAfterACompletedDrawDoesNotRaiseTheWeaponAgain() {
        assertTrue(controller.requestSwitch(1));
        visuals.finishHolster();
        controller.tick();
        visuals.finishDraw();
        controller.tick();

        controller.tick();
        controller.tick();

        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(1, visuals.drawStarts, "a settled weapon is not drawn again");
        assertEquals(RIFLE_B, visuals.visualIdentity());
    }


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
        assertEquals(RIFLE_A, visuals.visualIdentity());
        assertFalse(controller.session().deferred(), "nothing is deferred: the slot already changed");

        visuals.finishHolster();
        controller.tick();

        assertEquals(RIFLE_B, visuals.visualIdentity());
        assertEquals(List.of(), env.committedSlots, "no second commit");
    }

    @Test
    void losingThePlayerResetsEverything() {
        assertTrue(controller.requestSwitch(1));
        env.canTransition = false;
        controller.tick();

        assertEquals(HandoffStage.IDLE, controller.stage());
        assertEquals(WeaponIdentity.NONE, visuals.visualIdentity(), "no visual weapon survives the reset");
        assertEquals(1, visuals.releases);
    }

    @Test
    void resettingDropsTheSessionAndTheVisualWeapon() {
        assertTrue(controller.requestSwitch(1));
        controller.reset();

        assertEquals(HandoffStage.IDLE, controller.stage());
        assertNull(controller.session());
        assertEquals(WeaponIdentity.NONE, visuals.visualIdentity());
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
        assertEquals(RIFLE_A, visuals.visualIdentity(), "the visual weapon does not change");
    }
}
