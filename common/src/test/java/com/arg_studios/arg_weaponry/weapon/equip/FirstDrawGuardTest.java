package com.arg_studios.arg_weaponry.weapon.equip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The client-side first-draw guard, and the pure rule that combines it with the persistent flag.
 *
 * <p>The guard exists for one race: the server sets the flag when the draw <em>starts</em>, and the client reads it at the
 * same moment, so a very fast holster/draw cycle could replay {@code draw_first} before the synchronised component
 * arrives. These tests pin both halves of the answer - the in-session memory and the rule that consumes it.</p>
 */
class FirstDrawGuardTest {

    private static final String WEAPON = "arg_pack:m100_dmr";

    private static final String OTHER_WEAPON = "arg_pack:other";

    @AfterEach
    void clearGuard() {
        FirstDrawGuard.reset();
    }

    @Test
    void aDrawIsRememberedPerSlotAndItem() {
        assertFalse(FirstDrawGuard.hasPlayed(3, WEAPON));

        FirstDrawGuard.markPlayed(3, WEAPON);

        assertTrue(FirstDrawGuard.hasPlayed(3, WEAPON));
        assertFalse(FirstDrawGuard.hasPlayed(4, WEAPON), "the same weapon in another slot is another stack");
        assertFalse(FirstDrawGuard.hasPlayed(3, OTHER_WEAPON), "another item in that slot is another stack");
        assertEquals(1, FirstDrawGuard.size());
    }

    @Test
    void forgettingASlotKeepsOtherSlots() {
        FirstDrawGuard.markPlayed(3, WEAPON);
        FirstDrawGuard.markPlayed(4, WEAPON);

        FirstDrawGuard.forgetSlot(3);

        assertFalse(FirstDrawGuard.hasPlayed(3, WEAPON));
        assertTrue(FirstDrawGuard.hasPlayed(4, WEAPON));
    }

    @Test
    void theMemoryIsBounded() {
        for (int index = 0; index < FirstDrawGuard.MAX_ENTRIES + 20; index++) {
            FirstDrawGuard.markPlayed(index, "arg_pack:weapon_" + index);
        }

        assertEquals(FirstDrawGuard.MAX_ENTRIES, FirstDrawGuard.size(),
                "a long session must not grow this set without bound");
        assertTrue(FirstDrawGuard.hasPlayed(FirstDrawGuard.MAX_ENTRIES + 19, "arg_pack:weapon_"
                        + (FirstDrawGuard.MAX_ENTRIES + 19)), "the newest entry survives");
        assertFalse(FirstDrawGuard.hasPlayed(0, "arg_pack:weapon_0"), "the oldest entry was evicted");
    }

    @Test
    void resetForgetsEverything() {
        FirstDrawGuard.markPlayed(3, WEAPON);

        FirstDrawGuard.reset();

        assertEquals(0, FirstDrawGuard.size());
        assertFalse(FirstDrawGuard.hasPlayed(3, WEAPON));
    }

    @Test
    void theFirstDrawRuleNeedsBothTheStoredFlagAndTheGuardToBeClear() {
        assertTrue(FirstDrawFlag.needsFirstDraw(false, false), "a fresh stack draws first");
        assertFalse(FirstDrawFlag.needsFirstDraw(true, false), "the persistent flag already says it was drawn");
        assertFalse(FirstDrawFlag.needsFirstDraw(false, true),
                "this client already played it, so the unsynchronised flag must not replay it");
        assertFalse(FirstDrawFlag.needsFirstDraw(true, true));
    }

    @Test
    void theGuardSuppressesTheReplayForTheSameStackOnly() {
        FirstDrawGuard.markPlayed(3, WEAPON);

        assertFalse(FirstDrawFlag.needsFirstDraw(false, FirstDrawGuard.hasPlayed(3, WEAPON)));
        assertTrue(FirstDrawFlag.needsFirstDraw(false, FirstDrawGuard.hasPlayed(4, WEAPON)),
                "a different slot still gets its own first draw");
    }
}
