package com.arg_studios.arg_weaponry.weapon.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The shuffle bag behind the firing-sound variation.
 *
 * <p>The contract is what makes gunfire stop sounding repetitive: with {@code n} samples, {@code n} consecutive shots use
 * every sample exactly once, and the last sample of one cycle never starts the next one. Both properties are asserted over
 * many cycles and several sample counts, with a fixed seed so a failure is reproducible.</p>
 */
class SoundVariationPickerTest {

    @Test
    void everyCycleUsesEverySampleExactlyOnce() {
        SoundVariationPicker picker = new SoundVariationPicker();
        Random random = new Random(1234L);
        int count = 4;
        int[] used = new int[count];

        for (int shot = 0; shot < count * 10; shot++) {
            int index = picker.pick("weapon#fire", count, random::nextInt);
            assertTrue(index >= 0 && index < count, "an index inside the sample list");
            used[index]++;
            if ((shot + 1) % count == 0) {
                for (int sample = 0; sample < count; sample++) {
                    assertEquals(1, used[sample],
                            "every sample must be used exactly once per cycle of " + count + " shots");
                }
                used = new int[count];
            }
        }
    }

    @Test
    void aSampleIsNeverRepeatedBackToBackAcrossACycleBoundary() {
        SoundVariationPicker picker = new SoundVariationPicker();
        Random random = new Random(9876L);
        int previous = -1;

        for (int shot = 0; shot < 500; shot++) {
            int index = picker.pick("weapon#fire", 3, random::nextInt);
            assertNotEquals(previous, index, "shot " + shot + " repeats the previous sample");
            previous = index;
        }
    }

    @Test
    void aSingleSampleIsAlwaysIndexZero() {
        SoundVariationPicker picker = new SoundVariationPicker();

        for (int shot = 0; shot < 20; shot++) {
            assertEquals(0, picker.pick("weapon#fire", 1, new Random(shot)::nextInt));
        }
    }

    @Test
    void streamsAreIndependent() {
        SoundVariationPicker picker = new SoundVariationPicker();
        Random random = new Random(42L);

        int fire = picker.pick("a#fire", 5, random::nextInt);
        picker.pick("b#fire", 5, random::nextInt);
        picker.pick("b#fire", 5, random::nextInt);

        assertEquals(fire, picker.lastIndex("a#fire"),
                "another stream must not advance this one's dealt order");
        assertNotEquals(-1, picker.lastIndex("b#fire"));
    }

    @Test
    void aChangedSampleCountRestartsTheBagInsteadOfFailing() {
        SoundVariationPicker picker = new SoundVariationPicker();
        Random random = new Random(7L);

        picker.pick("weapon#fire", 3, random::nextInt);
        int index = picker.pick("weapon#fire", 2, random::nextInt);

        assertTrue(index >= 0 && index < 2);
    }

    @Test
    void resetForgetsEveryStream() {
        SoundVariationPicker picker = new SoundVariationPicker();
        picker.pick("weapon#fire", 3, new Random(1L)::nextInt);

        picker.reset();

        assertEquals(-1, picker.lastIndex("weapon#fire"));
    }

    @Test
    void aNullShufflerKeepsThePrimarySample() {
        SoundVariationPicker picker = new SoundVariationPicker();

        assertEquals(0, picker.pick("weapon#fire", 4, null));
    }

    @Test
    void anEmptyListIsTolerated() {
        SoundVariationPicker picker = new SoundVariationPicker();

        assertEquals(0, picker.pick("weapon#fire", 0, new Random(1L)::nextInt));
    }
}
