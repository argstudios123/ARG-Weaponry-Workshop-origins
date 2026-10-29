package com.arg_studios.arg_weaponry.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The gate in front of every per-shot effect: one accepted shot id is acted on once, and a duplicate or replayed
 * acceptance produces nothing - no animation, no sound and no recoil impulse.
 */
class AcceptedShotIdsTest {

    private static final String MAIN_HAND = "main";

    private static final String OFF_HAND = "off";

    @Test
    void theSameShotIsOnlyEverAcceptedOnce() {
        AcceptedShotIds<String> ids = new AcceptedShotIds<>();

        assertTrue(ids.accept(MAIN_HAND, 7));
        assertFalse(ids.accept(MAIN_HAND, 7), "the second delivery of shot 7 must not produce another effect");
        assertFalse(ids.accept(MAIN_HAND, 7), "and neither must a third");
        assertEquals(7, ids.lastAccepted(MAIN_HAND));
    }

    @Test
    void anOlderIdIsIgnoredAndANewerOneIsAccepted() {
        AcceptedShotIds<String> ids = new AcceptedShotIds<>();
        ids.accept(MAIN_HAND, 7);

        assertFalse(ids.accept(MAIN_HAND, 6), "an out-of-order replay must be ignored");
        assertFalse(ids.accept(MAIN_HAND, 1));
        assertTrue(ids.accept(MAIN_HAND, 8), "the next real shot must still be accepted");
        assertEquals(8, ids.lastAccepted(MAIN_HAND));
    }

    @Test
    void idsBelowOneAreNeverAccepted() {
        AcceptedShotIds<String> ids = new AcceptedShotIds<>();

        assertFalse(ids.accept(MAIN_HAND, 0), "the server never assigns shot id 0");
        assertFalse(ids.accept(MAIN_HAND, -3));
        assertTrue(ids.accept(MAIN_HAND, 1));
    }

    @Test
    void eachHandIsTrackedSeparately() {
        AcceptedShotIds<String> ids = new AcceptedShotIds<>();

        assertTrue(ids.accept(MAIN_HAND, 3));
        assertTrue(ids.accept(OFF_HAND, 2), "the other hand has its own shot ids");

        assertTrue(ids.accept(MAIN_HAND, 4));
        assertFalse(ids.accept(OFF_HAND, 2), "the other hand's duplicate is still a duplicate");
        assertTrue(ids.accept(OFF_HAND, 5));
    }

    @Test
    void clearingForgetsEveryTrackedShot() {
        AcceptedShotIds<String> ids = new AcceptedShotIds<>();
        ids.accept(MAIN_HAND, 5);

        ids.clear();

        assertEquals(0, ids.lastAccepted(MAIN_HAND));
        assertTrue(ids.accept(MAIN_HAND, 5),
                "after leaving the world the server's counters start over and the client must accept them again");
    }
}
