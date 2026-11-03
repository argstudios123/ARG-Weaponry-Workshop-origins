package com.arg_studios.arg_weaponry.weapon.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

/**
 * The playable description of one sound key: its samples and the pitch and volume spread a playback may use.
 *
 * <p>The interesting parts are the defaults (a gunshot is varied, every other action is not) and the clamping (a pack can
 * ask for a lot, but never for a pitch the sound engine cannot play or for a negative volume).</p>
 */
class WeaponSoundSetTest {

    private static final ResourceId FIRE = new ResourceId("arg_pack", "guns/fire_1");
    private static final ResourceId FIRE_2 = new ResourceId("arg_pack", "guns/fire_2");

    @Test
    void aGunshotGetsTheDefaultSpreadAndEveryOtherKeyDoesNot() {
        WeaponSoundSet fire = WeaponSoundSet.single(WeaponSoundKey.FIRE, FIRE);
        WeaponSoundSet reload = WeaponSoundSet.single(WeaponSoundKey.RELOAD, FIRE);

        assertEquals(WeaponSoundSet.DEFAULT_FIRE_PITCH_VARIATION, fire.pitchVariation());
        assertEquals(WeaponSoundSet.DEFAULT_FIRE_VOLUME_VARIATION, fire.volumeVariation());
        assertTrue(fire.varies());

        assertEquals(0.0F, reload.pitchVariation());
        assertEquals(0.0F, reload.volumeVariation());
        assertFalse(reload.varies(), "a one-sample reload is not varied at all");
    }

    @Test
    void theVariationIsCentredOnTheBaseValue() {
        WeaponSoundSet set = new WeaponSoundSet(List.of(FIRE), 1.0F, 1.0F, 0.05F, 0.10F);

        assertEquals(1.0F, set.pitchAt(0.5F), 1.0E-6F, "the neutral value is the declared pitch");
        assertEquals(1.0F - 0.05F, set.pitchAt(0.0F), 1.0E-6F);
        assertEquals(1.0F + 0.05F, set.pitchAt(1.0F), 1.0E-6F);
        assertEquals(1.0F - 0.10F, set.volumeAt(0.0F), 1.0E-6F);
        assertEquals(1.0F + 0.10F, set.volumeAt(1.0F), 1.0E-6F);
    }

    @Test
    void theVariationStaysInsideTheRangeTheSoundEnginePlays() {
        WeaponSoundSet loud = new WeaponSoundSet(List.of(FIRE), 1.0F, 1.98F, 0.5F, 0.5F);

        assertEquals(WeaponSoundSet.MAX_PITCH, loud.pitchAt(1.0F));
        assertTrue(loud.pitchAt(0.0F) >= WeaponSoundSet.MIN_PITCH);
        assertTrue(loud.volumeAt(0.0F) >= 0.0F);
        assertTrue(loud.volumeAt(1.0F) <= WeaponSoundSet.MAX_VOLUME);
    }

    @Test
    void aDeclaredTuningOutsideItsRangeIsClamped() {
        WeaponSoundSet set = new WeaponSoundSet(List.of(FIRE), -2.0F, 40.0F, 4.0F, -1.0F);

        assertEquals(0.0F, set.volume());
        assertEquals(WeaponSoundSet.MAX_PITCH, set.pitch());
        assertEquals(WeaponSoundSet.MAX_VARIATION, set.pitchVariation());
        assertEquals(0.0F, set.volumeVariation());
    }

    @Test
    void aNonFiniteTuningFallsBackToTheNeutralValue() {
        WeaponSoundSet set = new WeaponSoundSet(List.of(FIRE), Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN);

        assertEquals(1.0F, set.volume());
        assertEquals(1.0F, set.pitch());
        assertEquals(0.0F, set.pitchVariation());
        assertEquals(0.0F, set.volumeVariation());
    }

    @Test
    void theVariantsAreKeptInOrderAndTheFirstOneIsThePrimary() {
        WeaponSoundSet set = new WeaponSoundSet(List.of(FIRE, FIRE_2), 1.0F, 1.0F, 0.0F, 0.0F);

        assertEquals(2, set.size());
        assertEquals(FIRE, set.primary());
        assertEquals(FIRE, set.variant(0));
        assertEquals(FIRE_2, set.variant(1));
        assertEquals(FIRE, set.variant(5), "an index outside the list falls back to the primary sample");
        assertEquals(FIRE, set.variant(-1));
    }

    @Test
    void aSetNeedsAtLeastOneSample() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeaponSoundSet(List.of(), 1.0F, 1.0F, 0.0F, 0.0F));
    }

    @Test
    void aMissingTuningValueIsTheNeutralOne() {
        WeaponSoundSet set = new WeaponSoundSet(List.of(FIRE), 1.0F, 1.0F, 0.0F, 0.0F);

        assertEquals(1.0F, set.pitchAt(0.0F));
        assertEquals(1.0F, set.pitchAt(1.0F));
        assertEquals(1.0F, set.volumeAt(0.37F));
    }
}
