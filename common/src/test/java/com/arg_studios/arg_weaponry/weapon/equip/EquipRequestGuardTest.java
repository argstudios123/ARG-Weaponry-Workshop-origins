package com.arg_studios.arg_weaponry.weapon.equip;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The anti-replay and rate-limit rule of the equip request payload.
 *
 * <p>The server has to accept a client's "a switch is starting" message without letting a replayed, reordered or flooding
 * payload start windows at will. The rule is pure, so its edge cases - the first request, a duplicate, an older one, one
 * inside the cooldown, one after it, and a clock that went backwards - are pinned here rather than discovered in a
 * multiplayer session.</p>
 */
class EquipRequestGuardTest {

    private static final int COOLDOWN = EquipRequestGuard.COOLDOWN_TICKS;

    @Test
    void theFirstRequestIsAlwaysAllowed() {
        assertTrue(EquipRequestGuard.accepts(null, 100L, 1));
    }

    @Test
    void aReplayedOrOlderSequenceIsRefused() {
        EquipRequestGuard guard = new EquipRequestGuard(100L, 5);

        assertFalse(EquipRequestGuard.accepts(guard, 1_000L, 5), "the same sequence is a replay");
        assertFalse(EquipRequestGuard.accepts(guard, 1_000L, 4), "an older sequence is out of order");
        assertTrue(EquipRequestGuard.accepts(guard, 1_000L, 6));
    }

    @Test
    void aBurstInsideTheCooldownIsRefused() {
        EquipRequestGuard guard = new EquipRequestGuard(100L, 5);

        assertFalse(EquipRequestGuard.accepts(guard, 100L, 6), "the same tick is too soon");
        assertFalse(EquipRequestGuard.accepts(guard, 100L + COOLDOWN - 1, 7), "one tick early is too soon");
        assertTrue(EquipRequestGuard.accepts(guard, 100L + COOLDOWN, 8), "exactly at the cooldown is fine");
        assertTrue(EquipRequestGuard.accepts(guard, 200L, 9));
    }

    @Test
    void aClockThatWentBackwardsDoesNotBlockThePlayer() {
        EquipRequestGuard guard = new EquipRequestGuard(50_000L, 5);

        assertTrue(EquipRequestGuard.accepts(guard, 10L, 6),
                "a new world has a different time base, so the old stamp cannot be trusted");
    }

    @Test
    void acceptingARequestProducesTheNextStamp() {
        EquipRequestGuard next = new EquipRequestGuard(100L, 5).accepted(120L, 6);

        assertTrue(next.tick() == 120L);
        assertTrue(next.sequence() == 6);
        assertFalse(EquipRequestGuard.accepts(next, 121L, 6), "the new stamp rejects its own replay");
    }
}
