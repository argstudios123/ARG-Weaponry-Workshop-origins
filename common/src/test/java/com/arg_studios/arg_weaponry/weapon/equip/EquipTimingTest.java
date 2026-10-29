package com.arg_studios.arg_weaponry.weapon.equip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The seconds-to-ticks rule of the draw and holster windows: {@code max(1, ceil(seconds * 20))}.
 *
 * <p>The values are the ones the shipped weapon declares, so a change to the rule shows up as a failing number here
 * rather than as a window that ends a tick early in game.</p>
 */
class EquipTimingTest {

    @Test
    void authoredSecondsBecomeWholeTicksRoundedUp() {
        assertEquals(24, EquipTiming.secondsToTicks(1.2), "the draw_first clip is 1.2 s");
        assertEquals(18, EquipTiming.secondsToTicks(0.87), "0.87 s rounds up to 18 ticks");
        assertEquals(18, EquipTiming.secondsToTicks(0.89), "0.89 s rounds up to 18 ticks");
        assertEquals(16, EquipTiming.secondsToTicks(0.8), "the holster clip is 0.8 s");
        assertEquals(14, EquipTiming.secondsToTicks(0.7), "the empty holster clip is 0.7 s");
        assertEquals(10, EquipTiming.secondsToTicks(0.4833), "a firing clip only ever needs one window");
        assertEquals(11, EquipTiming.secondsToTicks(0.5333));
        assertEquals(20, EquipTiming.secondsToTicks(1.0));
        assertEquals(40, EquipTiming.secondsToTicks(2.0));
    }

    @Test
    void theShortestWindowIsOneTick() {
        assertEquals(1, EquipTiming.secondsToTicks(0.049), "anything below one tick still lasts a tick");
        assertEquals(1, EquipTiming.secondsToTicks(0.0));
        assertEquals(1, EquipTiming.secondsToTicks(-3.0), "a negative duration is a broken entry, not a rewind");
        assertEquals(1, EquipTiming.secondsToTicks(Double.NaN));
        assertEquals(1, EquipTiming.secondsToTicks(Double.POSITIVE_INFINITY));
    }

    @Test
    void anAbsurdDurationIsCappedInsteadOfOverflowing() {
        assertEquals(EquipTiming.MAX_DURATION_TICKS, EquipTiming.secondsToTicks(1.0E9));
        assertEquals(EquipTiming.MAX_DURATION_TICKS, EquipTiming.secondsToTicks(EquipTiming.MAX_DURATION_SECONDS));
        assertTrue(EquipTiming.MAX_DURATION_TICKS > 0);
    }

    @Test
    void theSafetyDeadlineIsAlwaysLaterThanTheWindow() {
        assertEquals(24 + EquipTiming.SAFETY_MARGIN_TICKS, EquipTiming.deadlineTicks(24));
        assertEquals(EquipTiming.MIN_DURATION_TICKS + EquipTiming.SAFETY_MARGIN_TICKS,
                EquipTiming.deadlineTicks(0), "a zero window still gets a positive deadline");
        assertEquals(EquipTiming.MIN_DURATION_TICKS + EquipTiming.SAFETY_MARGIN_TICKS,
                EquipTiming.deadlineTicks(-7));
        assertTrue(EquipTiming.deadlineTicks(EquipTiming.MAX_DURATION_TICKS) > EquipTiming.MAX_DURATION_TICKS);
    }

    @Test
    void ticksConvertBackToSecondsForClipRetiming() {
        assertEquals(1.2, EquipTiming.ticksToSeconds(24), 1.0E-12);
        assertEquals(0.0, EquipTiming.ticksToSeconds(0));
        assertEquals(0.0, EquipTiming.ticksToSeconds(-5), "a negative tick count is treated as no duration");
    }
}
