package com.arg_studios.arg_weaponry.weapon.equip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The equip state machine: what a phase tracks, how a window advances, when it is finished, when it has timed out, and
 * which observed slot/stack changes are expected completions rather than hard interrupts.
 *
 * <p>All of it is pure, so the interrupt rules that are hardest to reproduce in game (a forced switch, a drop, the
 * client's own delayed {@code setSelectedSlot}) are pinned here.</p>
 */
class EquipPhaseTest {

    private static final int HOLSTER_TICKS = EquipTiming.secondsToTicks(0.8);

    private static final int DRAW_TICKS = EquipTiming.secondsToTicks(1.2);

    private static final String WEAPON = "arg_pack:m100_dmr";

    private static final String OTHER_WEAPON = "arg_pack:other";

    // ---------------------------------------------------------------- idle

    @Test
    void idleIsNotBusyAndBlocksNothing() {
        EquipPhase idle = EquipPhase.IDLE;

        assertEquals(EquipState.IDLE, idle.state());
        assertFalse(idle.isBusy());
        assertFalse(idle.isWindowElapsed());
        assertFalse(idle.isTimedOut());
        assertTrue(idle.classify(3, WEAPON) == EquipPhase.Observation.MATCH,
                "nothing is intercepted while idle, so nothing can be interrupted");
        assertEquals(0.0, idle.progress());
    }

    // ---------------------------------------------------------------- starting

    @Test
    void startingADrawTracksTheSlotItemAndWindow() {
        EquipPhase draw = EquipPhase.drawing(3, WEAPON, "anim.model.draw", DRAW_TICKS);

        assertEquals(EquipState.DRAWING, draw.state());
        assertTrue(draw.isBusy());
        assertEquals(3, draw.slot());
        assertEquals(WEAPON, draw.itemId());
        assertEquals("anim.model.draw", draw.clip());
        assertEquals(DRAW_TICKS, draw.durationTicks());
        assertEquals(0, draw.elapsedTicks());
        assertFalse(draw.hasPendingSlot());
    }

    @Test
    void aWindowWithoutAClipStaysIdle() {
        assertSame(EquipPhase.IDLE, EquipPhase.drawing(3, WEAPON, null, DRAW_TICKS));
        assertSame(EquipPhase.IDLE, EquipPhase.drawing(3, WEAPON, "  ", DRAW_TICKS));
        assertSame(EquipPhase.IDLE, EquipPhase.drawing(3, WEAPON, "clip", 0),
                "a weapon without a usable window never blocks anything");
        assertSame(EquipPhase.IDLE, EquipPhase.holstering(3, WEAPON, null, HOLSTER_TICKS, 4));
    }

    // ---------------------------------------------------------------- ticking

    @Test
    void advanceCountsTicksAndReachesTheEndOfTheWindow() {
        EquipPhase draw = EquipPhase.drawing(3, WEAPON, "clip", 3);

        EquipPhase first = draw.advance();
        assertEquals(1, first.elapsedTicks());
        assertFalse(first.isWindowElapsed());

        EquipPhase second = first.advance();
        assertFalse(second.isWindowElapsed());
        EquipPhase third = second.advance();
        assertTrue(third.isWindowElapsed(), "three ticks complete a three tick window");
    }

    @Test
    void theClockStopsAtTheSafetyDeadline() {
        EquipPhase phase = EquipPhase.drawing(3, WEAPON, "clip", 2);

        for (int tick = 0; tick < EquipTiming.deadlineTicks(2) + 10; tick++) {
            phase = phase.advance();
        }

        assertEquals(EquipTiming.deadlineTicks(2), phase.elapsedTicks(),
                "a phase that is never completed still cannot grow without bound");
        assertTrue(phase.isTimedOut());
        assertEquals(1.0, phase.progress(), 1.0E-9, "and its progress stays at one");
    }

    @Test
    void advancingAnIdlePhaseChangesNothing() {
        assertSame(EquipPhase.IDLE, EquipPhase.IDLE.advance());
    }

    @Test
    void progressRunsFromZeroToOne() {
        EquipPhase draw = EquipPhase.drawing(3, WEAPON, "clip", 4);
        assertEquals(0.0, draw.progress());
        assertEquals(0.25, draw.advance().progress(), 1.0E-9);
        assertEquals(0.5, draw.advance().advance().progress(), 1.0E-9);
        EquipPhase full = draw.advance().advance().advance().advance();
        assertEquals(1.0, full.progress(), 1.0E-9);
    }

    @Test
    void theSafetyTimeoutIsOnlyReachedAfterTheMargin() {
        EquipPhase phase = EquipPhase.holstering(3, WEAPON, "clip", HOLSTER_TICKS, 4);

        for (int tick = 0; tick < HOLSTER_TICKS; tick++) {
            phase = phase.advance();
        }
        assertTrue(phase.isWindowElapsed(), "the window itself is complete");
        assertFalse(phase.isTimedOut(), "but the safety net has not fired yet");

        for (int tick = 0; tick < EquipTiming.SAFETY_MARGIN_TICKS; tick++) {
            phase = phase.advance();
        }
        assertTrue(phase.isTimedOut(), "after the margin the manager must force the outcome");
    }

    // ---------------------------------------------------------------- pending slot

    @Test
    void aHolsterKeepsTheTrackedSlotAndRemembersThePendingOne() {
        EquipPhase holster = EquipPhase.holstering(3, WEAPON, "anim.model.holster", HOLSTER_TICKS, 4);

        assertEquals(EquipState.HOLSTERING, holster.state());
        assertEquals(3, holster.slot(), "the old weapon is still the held one for the whole clip");
        assertEquals(4, holster.pendingSlot());
        assertTrue(holster.hasPendingSlot());
    }

    @Test
    void thePendingSlotCanBeRetargetedAndOnlyTheLatestOneCounts() {
        EquipPhase holster = EquipPhase.holstering(3, WEAPON, "clip", HOLSTER_TICKS, 4);

        EquipPhase retargeted = holster.withPendingSlot(5).withPendingSlot(6);

        assertEquals(6, retargeted.pendingSlot(), "scroll spam keeps only the latest target");
        assertEquals(3, retargeted.slot(), "the tracked weapon never changes mid-holster");
        assertEquals(holster.elapsedTicks(), retargeted.elapsedTicks(), "re-targeting does not restart the clip");
    }

    @Test
    void aPendingSlotCanBeGivenUp() {
        EquipPhase holster = EquipPhase.holstering(3, WEAPON, "clip", HOLSTER_TICKS, 4);

        EquipPhase cancelled = holster.withPendingSlot(EquipPhase.NO_SLOT);

        assertFalse(cancelled.hasPendingSlot());
        assertEquals(EquipPhase.NO_SLOT, cancelled.pendingSlot());
    }

    // ---------------------------------------------------------------- interrupt rules

    @Test
    void theTrackedStackStillHeldIsAMatch() {
        assertEquals(EquipPhase.Observation.MATCH,
                EquipPhase.drawing(3, WEAPON, "clip", DRAW_TICKS).classify(3, WEAPON));
        assertEquals(EquipPhase.Observation.MATCH,
                EquipPhase.holstering(3, WEAPON, "clip", HOLSTER_TICKS, 5).classify(3, WEAPON),
                "the holster keeps running while the old weapon is still held");
    }

    @Test
    void thePendingSlotArrivingIsAnExpectedCompletionNotAnInterrupt() {
        EquipPhase holster = EquipPhase.holstering(3, WEAPON, "clip", HOLSTER_TICKS, 5);

        assertEquals(EquipPhase.Observation.EXPECTED_COMPLETION, holster.classify(5, OTHER_WEAPON),
                "the client's own setSelectedSlot and the server's observation of it both land here");
        assertEquals(EquipPhase.Observation.EXPECTED_COMPLETION, holster.classify(5, null),
                "even when the new slot is empty, the switch itself completed normally");
    }

    @Test
    void anyOtherChangeIsAHardInterrupt() {
        EquipPhase holster = EquipPhase.holstering(3, WEAPON, "clip", HOLSTER_TICKS, 5);

        assertEquals(EquipPhase.Observation.HARD_INTERRUPT, holster.classify(4, WEAPON),
                "a slot nobody asked for is a forced change");
        assertEquals(EquipPhase.Observation.HARD_INTERRUPT, holster.classify(3, OTHER_WEAPON),
                "the held stack was replaced");
        assertEquals(EquipPhase.Observation.HARD_INTERRUPT, holster.classify(3, null),
                "the weapon was dropped or moved away");
        assertEquals(EquipPhase.Observation.HARD_INTERRUPT, holster.classify(EquipPhase.NO_SLOT, null));

        EquipPhase draw = EquipPhase.drawing(3, WEAPON, "clip", DRAW_TICKS);
        assertEquals(EquipPhase.Observation.HARD_INTERRUPT, draw.classify(4, WEAPON),
                "a draw has no pending slot, so any slot change interrupts it");
    }

    @Test
    void clearingForgetsEverything() {
        assertSame(EquipPhase.IDLE,
                EquipPhase.holstering(3, WEAPON, "clip", HOLSTER_TICKS, 5).cleared());
    }

    @Test
    void anItemIdChangeIsDetectedCaseSensitively() {
        EquipPhase draw = EquipPhase.drawing(3, WEAPON, "clip", DRAW_TICKS);

        assertEquals(EquipPhase.Observation.HARD_INTERRUPT, draw.classify(3, WEAPON.toUpperCase(java.util.Locale.ROOT)),
                "identifiers are exact, so a different id is a different item");
    }
}
