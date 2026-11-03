package com.arg_studios.arg_weaponry.client.aiming;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The aiming input state machine: toggle mode, hold mode and the two kinds of refusal.
 *
 * <p>No game is needed, which is the point: the toggle/hold rule and the "a reload must not cancel my toggle" rule are
 * verified without a client.</p>
 */
class AimInputTest {

    @Test
    void toggleModeStartsOnTheFirstPressAndStopsOnTheNext() {
        AimInput input = new AimInput();

        assertTrue(input.update(true, true, AimBlockReason.NONE), "the first press starts aiming");
        assertTrue(input.update(true, true, AimBlockReason.NONE), "holding the button does not stop it");
        assertTrue(input.update(false, true, AimBlockReason.NONE), "releasing the button does not stop it either");
        assertFalse(input.update(true, true, AimBlockReason.NONE), "so the second press is what stops it");
        assertFalse(input.update(false, true, AimBlockReason.NONE), "and it stays stopped");
    }

    @Test
    void holdModeFollowsTheButtonAndKeepsNoLatch() {
        AimInput input = new AimInput();

        assertTrue(input.update(true, false, AimBlockReason.NONE));
        assertTrue(input.update(true, false, AimBlockReason.NONE), "a held button stays aimed");
        assertFalse(input.update(false, false, AimBlockReason.NONE), "releasing stops aiming");
        assertFalse(input.latched(), "hold mode never latches anything");
    }

    @Test
    void aTemporaryRefusalKeepsTheIntent() {
        AimInput input = new AimInput();
        assertTrue(input.update(true, true, AimBlockReason.NONE), "toggle on");

        assertTrue(input.update(false, true, AimBlockReason.RELOADING),
                "the intent is kept while a reload pauses aiming - the refusal is applied by the aiming controller");
        assertTrue(input.latched(), "and the toggle request is kept...");
        assertTrue(input.update(false, true, AimBlockReason.NONE), "...and aiming resumes when the reload ends");
    }

    @Test
    void aHardRefusalForgetsTheRequest() {
        AimInput input = new AimInput();
        assertTrue(input.update(true, true, AimBlockReason.NONE), "toggle on");

        assertFalse(input.update(false, true, AimBlockReason.DISABLED), "a weapon without aiming cannot be aimed");
        assertFalse(input.latched(), "and a latched request is dropped instead of leaking to the next weapon");
        assertFalse(input.update(false, true, AimBlockReason.DISABLED));
    }

    @Test
    void aPressWhileAnInspectionRunsCannotAim() {
        AimInput input = new AimInput();
        assertTrue(input.update(true, true, AimBlockReason.NONE), "toggle on");

        assertFalse(input.update(false, true, AimBlockReason.INSPECTING), "an inspection cancels the aim");
        assertFalse(input.latched(), "the intent is cleared, not suspended");
        assertFalse(input.update(true, true, AimBlockReason.INSPECTING),
                "a press during the inspection cannot re-arm it");
        assertFalse(input.update(false, true, AimBlockReason.NONE), "so aiming does not return afterwards");
    }

    @Test
    void resetForgetsBothTheLatchAndTheButton() {
        AimInput input = new AimInput();
        assertTrue(input.update(true, true, AimBlockReason.NONE));

        input.reset();

        assertFalse(input.latched());
        assertFalse(input.wasDown());
        assertFalse(input.update(false, true, AimBlockReason.NONE), "a fresh press is needed after a reset");
    }

    @Test
    void sprintingIsRefusedWhileAimIsHeldOrLatched() {
        assertTrue(AimInput.blocksSprint(true, false), "holding the aim button blocks a sprint start");
        assertTrue(AimInput.blocksSprint(false, true), "a latched toggle keeps blocking after the button is released");
        assertTrue(AimInput.blocksSprint(true, true));
    }

    @Test
    void sprintingIsAvailableAgainTheMomentAimingEnds() {
        assertFalse(AimInput.blocksSprint(false, false),
                "a released aim must not keep sprinting blocked while the transition blends out");
    }

    @Test
    void aRawAimRequestAlsoBlocksSprintingDuringATemporaryRefusal() {
        assertTrue(AimInput.blocksSprint(false, false, true),
                "a hold-mode aim during a reload still blocks sprinting");
        assertFalse(AimInput.blocksSprint(false, false, false),
                "but holding an unrelated weapon must not disable sprinting");
    }

    @Test
    void theToggleLatchBlocksSprintingWithoutAnyKeyBeingHeld() {
        AimInput input = new AimInput();

        assertTrue(input.update(true, true, AimBlockReason.NONE), "toggle aiming on");
        input.update(false, true, AimBlockReason.NONE);
        assertTrue(AimInput.blocksSprint(false, input.latched()), "the button is released, the aim is not");

        assertFalse(input.update(true, true, AimBlockReason.NONE), "the next press cancels the toggle");
        assertFalse(AimInput.blocksSprint(false, input.latched()), "so sprinting is available again");
    }
    @Test
    void aReloadBlocksSprintingUntilItEnds() {
        assertTrue(AimInput.blocksSprint(false, false, false, true),
                "a running reload must stop and refuse a sprint, even with no aim involved");
        assertFalse(AimInput.blocksSprint(false, false, false, false),
                "once the reload ends, sprinting is available again");
        assertTrue(AimInput.blocksSprint(true, false, false, true));
    }

    @Test
    void anInspectionCancelsAimingAndItDoesNotComeBackOnItsOwn() {
        AimInput input = new AimInput();

        assertTrue(input.update(true, false, AimBlockReason.NONE), "the player is aiming");
        assertFalse(input.update(true, false, AimBlockReason.INSPECTING),
                "an inspection cancels the aim on the tick it starts");
        assertTrue(input.suppressedUntilRelease());
        assertFalse(input.update(true, false, AimBlockReason.NONE),
                "a still-held button must not re-arm the aim while the inspection runs");
        assertFalse(input.update(false, false, AimBlockReason.NONE),
                "releasing the button only clears the suppression, it does not aim");
        assertTrue(input.update(true, false, AimBlockReason.NONE),
                "a fresh press aims again, so normal aiming outside the inspection is preserved");
    }

    @Test
    void anInspectionClearsALatchedToggleWithoutResumingIt() {
        AimInput input = new AimInput();

        assertTrue(input.update(true, true, AimBlockReason.NONE), "the toggle latches the aim");
        assertFalse(input.update(true, true, AimBlockReason.INSPECTING), "the inspection cancels it");
        assertFalse(input.latched(), "the latch is cleared, not suspended");
        assertFalse(input.update(false, true, AimBlockReason.NONE),
                "and it does not re-latch when the inspection ends");
        assertTrue(input.update(true, true, AimBlockReason.NONE), "a new press latches it again");
    }
}
