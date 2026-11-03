package com.arg_studios.arg_weaponry.weapon.muzzle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The {@code random} sprite animation of a muzzle flash, checked against the reference implementation's algorithm.
 *
 * <p>PB gives every effect instance its own provider, draws <em>one</em> seed in that provider's constructor and then, for
 * every query, reseeds from {@code seed + (int) (elapsedMillis / (long) (1000.0 / fps))} and takes
 * {@code nextInt(frameCount)}. These tests pin the observable consequences of that: one sequence per shot, stable within
 * a frame interval, repeatable, independent between shots, and finished when the sheet's frames are used up.</p>
 *
 * <p>The expected indices are computed here from the reference formula rather than from ARG's helper, so a change to the
 * helper that silently alters the algorithm fails the test instead of agreeing with itself.</p>
 */
class MuzzleFlashRandomSpriteTest {

    private static final WeaponDefinition.MuzzleFlash.Sprites RANDOM_8 =
            new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 60, WeaponDefinition.MuzzleFlash.SpriteType.RANDOM);

    @Test
    void everyEffectDrawsItsOwnSequence() {
        List<Integer> first = sequence(1_234_567, RANDOM_8);
        List<Integer> second = sequence(7_654_321, RANDOM_8);

        assertEquals(8, first.size());
        assertEquals(8, second.size());
        assertNotEquals(first, second,
                "two shots must not show the same random sequence: each effect carries its own seed");
    }

    @Test
    void theSameInstantAlwaysResolvesToTheSameSprite() {
        for (int step = 0; step < 8; step++) {
            double elapsed = step * 0.016D;
            int expected = referenceIndex(42_424, elapsed, 60, 8);
            for (int call = 0; call < 5; call++) {
                // Interleaved with another effect's queries: a generator that was not reseeded would drift.
                MuzzleFlashSpriteSheet.frameAt(elapsed, RANDOM_8, 999);
                assertEquals(expected, MuzzleFlashSpriteSheet.frameAt(elapsed, RANDOM_8, 42_424).index(),
                        "a repeated render call inside one frame interval must not change the sprite");
            }
        }
    }

    @Test
    void effectsDoNotDisturbEachOther() {
        List<Integer> alone = sequence(555, RANDOM_8);

        List<Integer> interleaved = new ArrayList<>();
        for (int step = 0; step < 8; step++) {
            MuzzleFlashSpriteSheet.frameAt(step * 0.016D, RANDOM_8, 777_777);
            interleaved.add(MuzzleFlashSpriteSheet.frameAt(step * 0.016D, RANDOM_8, 555).index());
        }

        assertEquals(alone, interleaved,
                "a second effect rendering in between must not advance the first effect's sequence");
    }

    @Test
    void theSpriteFollowsTheReferenceFrameGrid() {
        // PB truncates the frame duration to whole milliseconds: 1000.0 / 60 = 16 ms, not 16.67.
        assertEquals(referenceIndex(3_000, 0.0D, 60, 8), indexAt(0.0D));
        assertEquals(referenceIndex(3_000, 0.015D, 60, 8), indexAt(0.015D),
                "15 ms is still inside the first frame interval");
        assertEquals(referenceIndex(3_000, 0.016D, 60, 8), indexAt(0.016D), "16 ms starts the second interval");
        assertEquals(referenceIndex(3_000, 0.031D, 60, 8), indexAt(0.031D));
        assertEquals(referenceIndex(3_000, 0.032D, 60, 8), indexAt(0.032D));
    }

    @Test
    void repeatedSpritesAreAllowed() {
        // Two frames over eight intervals: a draw that never repeated would be a permutation, not a random draw.
        WeaponDefinition.MuzzleFlash.Sprites twoFrames =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 2, 60, WeaponDefinition.MuzzleFlash.SpriteType.RANDOM);
        boolean sawRepeat = false;
        for (int seed = 0; seed < 64 && !sawRepeat; seed++) {
            List<Integer> drawn = sequence(seed, twoFrames);
            assertEquals(2, drawn.size(), "a two-frame sheet has two intervals");
            sawRepeat = drawn.get(0).equals(drawn.get(1));
        }
        assertTrue(sawRepeat, "a random selection must be allowed to show the same sprite twice");
    }

    @Test
    void theAnimationEndsWhenTheSheetsFramesAreUsedUp() {
        // Eight frames at 60 fps: 8 * 16 ms = 128 ms.
        assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.127D, RANDOM_8, 11), "the last interval still draws");
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.128D, RANDOM_8, 11), "the flash is over after its own frames");
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.5D, RANDOM_8, 11), "and it never comes back");
    }

    @Test
    void aCustomFrameRateChangesTheInterval() {
        WeaponDefinition.MuzzleFlash.Sprites slow =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 10, WeaponDefinition.MuzzleFlash.SpriteType.RANDOM);
        // 10 fps -> a 100 ms frame duration: 0.05 s is still the first interval, 0.1 s is the second.
        assertEquals(referenceIndex(9, 0.05D, 10, 8), MuzzleFlashSpriteSheet.frameAt(0.05D, slow, 9).index());
        assertEquals(referenceIndex(9, 0.10D, 10, 8), MuzzleFlashSpriteSheet.frameAt(0.10D, slow, 9).index());
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.8D, slow, 9), "8 frames at 10 fps last 800 ms");
    }

    @Test
    void theOtherAnimationTypesAreUnchanged() {
        WeaponDefinition.MuzzleFlash.Sprites playOnce =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 60, WeaponDefinition.MuzzleFlash.SpriteType.PLAY_ONCE);
        for (int step = 0; step < 8; step++) {
            // PLAY_ONCE keeps its double-precision frame duration: one frame per 1/60 s.
            assertEquals(step, MuzzleFlashSpriteSheet.frameAt(step / 60.0D, playOnce, 5).index());
        }
        assertNull(MuzzleFlashSpriteSheet.frameAt(8 / 60.0D, playOnce, 5), "play_once stops after its last frame");

        WeaponDefinition.MuzzleFlash.Sprites loop =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 60, WeaponDefinition.MuzzleFlash.SpriteType.LOOP);
        assertEquals(0, MuzzleFlashSpriteSheet.frameAt(0.0D, loop, 5).index());
        assertEquals(3, MuzzleFlashSpriteSheet.frameAt(3 / 60.0D, loop, 5).index());
        assertEquals(0, MuzzleFlashSpriteSheet.frameAt(8 / 60.0D, loop, 5).index(), "loop wraps onto the first frame");

        WeaponDefinition.MuzzleFlash.Sprites fixed =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 60, WeaponDefinition.MuzzleFlash.SpriteType.STATIC);
        assertEquals(0, MuzzleFlashSpriteSheet.frameAt(0.0D, fixed, 5).index());
        assertEquals(0, MuzzleFlashSpriteSheet.frameAt(0.5D, fixed, 5).index(), "static never advances");
    }

    // ---------------------------------------------------------------- helpers

    /** The sprite indices a fresh effect with that seed shows over one full animation. */
    private static List<Integer> sequence(int seed, WeaponDefinition.MuzzleFlash.Sprites sprites) {
        long frameMillis = (long) (1000.0D / sprites.fps());
        List<Integer> drawn = new ArrayList<>();
        for (int step = 0; step < sprites.frameCount(); step++) {
            MuzzleFlashSpriteSheet.Frame frame =
                    MuzzleFlashSpriteSheet.frameAt(step * frameMillis / 1000.0D, sprites, seed);
            assertNotNull(frame, "step " + step + " must still be inside the animation");
            drawn.add(frame.index());
        }
        return drawn;
    }

    private static int indexAt(double elapsedSeconds) {
        MuzzleFlashSpriteSheet.Frame frame = MuzzleFlashSpriteSheet.frameAt(elapsedSeconds, RANDOM_8, 3_000);
        assertNotNull(frame);
        return frame.index();
    }

    /**
     * The reference implementation's selection, written out independently.
     *
     * @param seed           the effect's seed
     * @param elapsedSeconds how long the effect has been running
     * @param fps            the sheet's frame rate
     * @param total          the sheet's frame count
     * @return the sprite index it would draw
     */
    private static int referenceIndex(int seed, double elapsedSeconds, int fps, int total) {
        long frameMillis = (long) (1000.0D / fps);
        double elapsedMillis = elapsedSeconds * 1000.0D;
        int step = (int) (elapsedMillis / frameMillis);
        return new Random((long) (seed + step)).nextInt(total);
    }
}
