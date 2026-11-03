package com.arg_studios.arg_weaponry.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The muzzle-flash lifecycle: which flashes are running, for how long, and what a new shot does to the running ones.
 *
 * <p>The reference implementation keeps a <em>list</em> of effects per gun state, appends one per shot and removes the
 * expired ones on the client tick. These tests pin the two consequences that are visible in game - overlapping shots stay
 * independent instead of restarting one animation, and a flash that has outlived its bound is dropped - without needing a
 * client, because {@link MuzzleFlashState} has no Minecraft types.</p>
 */
class MuzzleFlashStateTest {

    private static final String HAND = "main_hand";
    private static final String OTHER_HAND = "off_hand";
    private static final String WEAPON = "weapon";

    private MuzzleFlashState<String, String> state;

    @BeforeEach
    void setUp() {
        state = new MuzzleFlashState<>();
    }

    /** Records a flash with the default lifetime, the case every pre-existing configuration hits. */
    private void add(String bone, long startNanos, int seed) {
        state.add(HAND, WEAPON, bone, startNanos, seed, MuzzleFlashState.DEFAULT_LIFETIME_SECONDS);
    }

    @Test
    void twoOverlappingShotsAreBothActive() {
        long first = 1_000_000_000L;
        long second = first + 50_000_000L;

        add("muzzle", first, 11);
        add("muzzle2", second, 22);

        List<MuzzleFlashState.Flash<String>> active = state.active(HAND, WEAPON, second + 50_000_000L);

        assertEquals(2, active.size(), "a second shot must not replace the first one's flash");
        assertEquals("muzzle", active.get(0).bone(), "oldest first");
        assertEquals("muzzle2", active.get(1).bone());
        assertEquals(11, active.get(0).seed());
        assertEquals(22, active.get(1).seed(), "each shot keeps the seed it drew");
        assertNotEquals(active.get(0).startNanos(), active.get(1).startNanos(),
                "each flash keeps its own start, which is what makes two animations independent");
    }

    @Test
    void anExpiredFlashIsDropped() {
        long start = 5_000_000_000L;
        add("muzzle", start, 1);

        assertTrue(state.active(HAND, WEAPON, start + 100_000_000L).size() == 1, "the flash is still running");
        assertEquals(List.of(), state.active(HAND, WEAPON,
                        start + (long) (MuzzleFlashState.DEFAULT_LIFETIME_SECONDS * 1.0E9D) + 1L),
                "a flash older than the cleanup bound is not reported");
        assertEquals(0, state.size(HAND), "and its record is gone, so nothing leaks");
    }

    @Test
    void aNewShotAlsoPurgesTheExpiredOnes() {
        long start = 2_000_000_000L;
        add("muzzle", start, 1);
        long muchLater = start + (long) (MuzzleFlashState.DEFAULT_LIFETIME_SECONDS * 1.0E9D) + 1L;

        add("muzzle", muchLater, 2);

        assertEquals(1, state.size(HAND), "the expired record is dropped when the next shot arrives");
        assertEquals(2, state.active(HAND, WEAPON, muchLater).getFirst().seed());
    }

    @Test
    void aDifferentWeaponInTheSameHandShowsNothing() {
        long start = 3_000_000_000L;
        add("muzzle", start, 1);

        assertEquals(List.of(), state.active(HAND, "another_weapon", start),
                "a swapped weapon must not inherit the previous flash");
        assertEquals(1, state.active(HAND, WEAPON, start).size());
    }

    @Test
    void eachHandKeepsItsOwnFlashes() {
        long start = 4_000_000_000L;
        add("muzzle", start, 1);
        state.add(OTHER_HAND, WEAPON, "muzzle_off", start, 2, MuzzleFlashState.DEFAULT_LIFETIME_SECONDS);

        assertEquals("muzzle", state.active(HAND, WEAPON, start).getFirst().bone());
        assertEquals("muzzle_off", state.active(OTHER_HAND, WEAPON, start).getFirst().bone());
    }

    @Test
    void aClockThatWentBackwardsReportsNothing() {
        long start = 6_000_000_000L;
        add("muzzle", start, 1);

        assertEquals(List.of(), state.active(HAND, WEAPON, start - 1_000_000L),
                "an instant before the shot cannot show a flash");
    }

    @Test
    void theOldestFlashIsDroppedWhenTheBoundIsReached() {
        long start = 7_000_000_000L;
        for (int shot = 0; shot < MuzzleFlashState.MAX_PER_KEY + 1; shot++) {
            add("muzzle", start + shot * 1_000_000L, shot);
        }

        List<MuzzleFlashState.Flash<String>> active =
                state.active(HAND, WEAPON, start + MuzzleFlashState.MAX_PER_KEY * 1_000_000L);
        assertEquals(MuzzleFlashState.MAX_PER_KEY, active.size(), "the list is bounded");
        assertEquals(1, active.getFirst().seed(), "the oldest flash was dropped first");
    }

    @Test
    void eachFlashExpiresAtItsOwnLifetime() {
        long start = 8_000_000_000L;
        // The same hand, two shots, two different configured durations: 80 ms and 500 ms.
        state.add(HAND, WEAPON, "short", start, 1, 0.080D);
        state.add(HAND, WEAPON, "long", start, 2, 0.500D);

        assertEquals(2, state.active(HAND, WEAPON, start + 50_000_000L).size(), "both are visible at 50 ms");

        List<MuzzleFlashState.Flash<String>> atHundred = state.active(HAND, WEAPON, start + 100_000_000L);
        assertEquals(1, atHundred.size(), "the short flash is gone at 100 ms");
        assertEquals("long", atHundred.getFirst().bone());

        assertEquals(List.of(), state.active(HAND, WEAPON, start + 600_000_000L),
                "and the long one is gone after its own 500 ms");
    }

    @Test
    void anUnusableLifetimeFallsBackToTheDefault() {
        long start = 9_000_000_000L;
        state.add(HAND, WEAPON, "zero", start, 1, 0.0D);
        state.add(HAND, WEAPON, "negative", start, 2, -2.0D);
        state.add(HAND, WEAPON, "nan", start, 3, Double.NaN);

        assertEquals(3, state.active(HAND, WEAPON, start + 500_000_000L).size(),
                "an unusable lifetime must not make the flash vanish immediately");
        assertEquals(List.of(), state.active(HAND, WEAPON,
                        start + (long) (MuzzleFlashState.DEFAULT_LIFETIME_SECONDS * 1.0E9D) + 1L),
                "they fall back to the default lifetime");
    }

    @Test
    void clearingForgetsEveryFlash() {
        add("muzzle", 1L, 1);
        state.clear();

        assertEquals(0, state.size(HAND));
        assertEquals(List.of(), state.active(HAND, WEAPON, 1L));
    }
}
