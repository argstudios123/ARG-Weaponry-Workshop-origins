package com.arg_studios.arg_weaponry.weapon.muzzle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * {@code duration_ms}: the flash's visibility lifetime, independent of the sprite frame rate.
 *
 * <p>The frame rate decides how fast the sheet advances; the duration decides when the flash stops being drawn. These
 * tests hold one constant while varying the other, which is the property that makes the two settings usable on their own:
 * a 200 fps sheet with an 80 ms duration steps every 5 ms, and the same 80 ms duration at 30 fps still ends at 80 ms.</p>
 *
 * <p>Every assertion is on observable output - "is a frame drawn, and which one" - never on an intermediate value, so the
 * tests keep their meaning if the internals change.</p>
 */
class MuzzleFlashDurationTest {

    private static final double EIGHTY_MILLIS = 0.080D;

    @Test
    void randomAnimationIsVisibleForTheConfiguredDurationAndNoLonger() {
        // 8 frames at 200 fps: the sheet's own frames would run out after 8 * 5 = 40 ms, which must NOT end the flash.
        WeaponDefinition.MuzzleFlash.Sprites sprites =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 200, WeaponDefinition.MuzzleFlash.SpriteType.RANDOM);

        assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.0D, sprites, 7, EIGHTY_MILLIS), "the flash starts visible");
        assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.039D, sprites, 7, EIGHTY_MILLIS),
                "it is still visible after the sheet's own frames are used up");
        assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.079D, sprites, 7, EIGHTY_MILLIS), "and up to the last instant");
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.080D, sprites, 7, EIGHTY_MILLIS), "at exactly the duration it ends");
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.5D, sprites, 7, EIGHTY_MILLIS), "and it never comes back");
    }

    @Test
    void theFrameRateDoesNotChangeTheLifetime() {
        for (int fps : new int[] {30, 60, 200, 1000}) {
            WeaponDefinition.MuzzleFlash.Sprites sprites =
                    new WeaponDefinition.MuzzleFlash.Sprites(1, 8, fps, WeaponDefinition.MuzzleFlash.SpriteType.RANDOM);
            assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.079D, sprites, 3, EIGHTY_MILLIS),
                    "visible just before the duration at " + fps + " fps");
            assertNull(MuzzleFlashSpriteSheet.frameAt(0.080D, sprites, 3, EIGHTY_MILLIS),
                    "and over at the same instant whatever the frame rate: " + fps + " fps");
        }
    }

    @Test
    void theDurationDoesNotChangeWhichSpriteIsSelected() {
        WeaponDefinition.MuzzleFlash.Sprites sprites =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 200, WeaponDefinition.MuzzleFlash.SpriteType.RANDOM);

        for (int step = 0; step < 8; step++) {
            double elapsed = step * 0.005D;
            int withShortDuration = MuzzleFlashSpriteSheet.frameAt(elapsed, sprites, 4242, EIGHTY_MILLIS).index();
            int withLongDuration = MuzzleFlashSpriteSheet.frameAt(elapsed, sprites, 4242, 5.0D).index();
            assertEquals(withShortDuration, withLongDuration,
                    "sprite selection depends on the frame rate and the seed, never on how long the flash lasts");
        }
    }

    @Test
    void loopKeepsWrappingUntilTheConfiguredDurationEnds() {
        // 3 frames at 200 fps: one pass lasts 15 ms, so an 80 ms flash must wrap several times.
        WeaponDefinition.MuzzleFlash.Sprites sprites =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 3, 200, WeaponDefinition.MuzzleFlash.SpriteType.LOOP);

        assertEquals(0, MuzzleFlashSpriteSheet.frameAt(0.0D, sprites, 0, EIGHTY_MILLIS).index());
        assertEquals(1, MuzzleFlashSpriteSheet.frameAt(0.005D, sprites, 0, EIGHTY_MILLIS).index());
        assertEquals(2, MuzzleFlashSpriteSheet.frameAt(0.010D, sprites, 0, EIGHTY_MILLIS).index());
        assertEquals(0, MuzzleFlashSpriteSheet.frameAt(0.015D, sprites, 0, EIGHTY_MILLIS).index(), "it wrapped");
        assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.070D, sprites, 0, EIGHTY_MILLIS), "still looping near the end");
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.080D, sprites, 0, EIGHTY_MILLIS), "the duration still ends it");
    }

    @Test
    void staticKeepsItsSpriteUntilTheConfiguredDurationEnds() {
        WeaponDefinition.MuzzleFlash.Sprites sprites =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 200, WeaponDefinition.MuzzleFlash.SpriteType.STATIC);

        assertEquals(0, MuzzleFlashSpriteSheet.frameAt(0.0D, sprites, 0, EIGHTY_MILLIS).index());
        assertEquals(0, MuzzleFlashSpriteSheet.frameAt(0.079D, sprites, 0, EIGHTY_MILLIS).index());
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.080D, sprites, 0, EIGHTY_MILLIS));
    }

    @Test
    void playOnceHoldsItsFinalFrameUntilTheConfiguredDurationEnds() {
        // 8 frames at 60 fps: the sequence lasts ~133 ms, so a 500 ms flash must freeze on the last frame.
        WeaponDefinition.MuzzleFlash.Sprites sprites =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 60, WeaponDefinition.MuzzleFlash.SpriteType.PLAY_ONCE);

        assertEquals(0, MuzzleFlashSpriteSheet.frameAt(0.0D, sprites, 0, 0.5D).index());
        assertEquals(7, MuzzleFlashSpriteSheet.frameAt(7 / 60.0D, sprites, 0, 0.5D).index());
        assertEquals(7, MuzzleFlashSpriteSheet.frameAt(0.2D, sprites, 0, 0.5D).index(),
                "past its own sequence it holds the final frame instead of disappearing");
        assertEquals(7, MuzzleFlashSpriteSheet.frameAt(0.499D, sprites, 0, 0.5D).index());
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.5D, sprites, 0, 0.5D), "the duration ends it");
    }

    @Test
    void withoutADurationEveryTypeKeepsItsHistoricalBehaviour() {
        WeaponDefinition.MuzzleFlash.Sprites random =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 60, WeaponDefinition.MuzzleFlash.SpriteType.RANDOM);
        WeaponDefinition.MuzzleFlash.Sprites playOnce =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 60, WeaponDefinition.MuzzleFlash.SpriteType.PLAY_ONCE);
        WeaponDefinition.MuzzleFlash.Sprites loop =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 60, WeaponDefinition.MuzzleFlash.SpriteType.LOOP);
        WeaponDefinition.MuzzleFlash.Sprites fixed =
                new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 60, WeaponDefinition.MuzzleFlash.SpriteType.STATIC);

        // Random and play-once still end with the sheet's own frames (8 * 16 ms = 128 ms), exactly as before.
        assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.127D, random, 5), "8 frames at 60 fps last 127 ms");
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.128D, random, 5));
        assertNull(MuzzleFlashSpriteSheet.frameAt(8 / 60.0D, playOnce, 5));
        // Loop and static never ended on their own; the client's cleanup bound is what eventually drops them.
        assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.9D, loop, 5));
        assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.9D, fixed, 5));
    }

    @Test
    void aDerivedStripSheetAlsoHonoursTheDuration() {
        // No declared grid: the sheet has no type, so it behaves like play_once, and the duration still ends it.
        assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.0D, 8, EIGHTY_MILLIS));
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.080D, 8, EIGHTY_MILLIS));
        // Eight derived frames last 8 * 40 ms = 320 ms, so a 500 ms duration holds the last frame.
        assertEquals(7, MuzzleFlashSpriteSheet.frameAt(0.4D, 8, 0.5D).index());
        assertNull(MuzzleFlashSpriteSheet.frameAt(0.5D, 8, 0.5D));
    }
}
