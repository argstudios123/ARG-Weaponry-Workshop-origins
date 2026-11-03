package com.arg_studios.arg_weaponry.weapon.audio;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

import java.util.ArrayList;
import java.util.List;

/**
 * The playable description of one sound key of one weapon: one or more samples plus the small amount of variation the
 * engine is allowed to add to them.
 *
 * <h2>Why a list and not one identifier</h2>
 * <p>A single recording played on every shot is the main reason gunfire sounds synthetic. Professional shooters ship
 * several takes of the same weapon and rotate between them, adding a few percent of pitch and volume change on top, so two
 * consecutive shots essentially never sound identical. This type is that idea as a value: the {@code sounds} entry of a
 * weapon may stay a single identifier (unchanged behaviour), become an array of identifiers, or become an object that also
 * tunes the variation.</p>
 *
 * <h2>Pure by design</h2>
 * <p>No Minecraft types, no registry lookups and no clock: the class only answers "given a uniform random value in
 * {@code [0, 1)}, which pitch and which volume should this playback use". That is what lets the tuning be unit tested and
 * lets the client and the server apply the same arithmetic.</p>
 *
 * @param variants        the samples of this key, in the order they were declared; never empty
 * @param volume          the base volume, {@code 1.0} when the pack declares none
 * @param pitch           the base pitch, {@code 1.0} when the pack declares none
 * @param pitchVariation  the relative pitch spread applied per playback, {@code 0} for none
 * @param volumeVariation the relative volume spread applied per playback, {@code 0} for none
 */
public record WeaponSoundSet(List<ResourceId> variants, float volume, float pitch, float pitchVariation,
                             float volumeVariation) {

    /** The quietest pitch the sound engine plays; also Minecraft's own lower bound. */
    public static final float MIN_PITCH = 0.5F;

    /** The highest pitch the sound engine plays; also Minecraft's own upper bound. */
    public static final float MAX_PITCH = 2.0F;

    /** The loudest volume a single playback may use. */
    public static final float MAX_VOLUME = 2.0F;

    /** The largest relative variation a pack may declare; beyond it the effect stops sounding like a variation. */
    public static final float MAX_VARIATION = 0.5F;

    /**
     * The default relative pitch spread of a gunshot.
     *
     * <p>About three percent: enough that the ear cannot match two shots, far below the point where a rifle starts to
     * sound like a different weapon.</p>
     */
    public static final float DEFAULT_FIRE_PITCH_VARIATION = 0.035F;

    /** The default relative volume spread of a gunshot, slightly wider than the pitch spread. */
    public static final float DEFAULT_FIRE_VOLUME_VARIATION = 0.06F;

    public WeaponSoundSet {
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("A sound set needs at least one variant");
        }
        List<ResourceId> copy = new ArrayList<>(variants.size());
        for (ResourceId variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("A sound set may not contain a null variant");
            }
            copy.add(variant);
        }
        variants = List.copyOf(copy);
        volume = clamp(volume, 0.0F, MAX_VOLUME, 1.0F);
        pitch = clamp(pitch, MIN_PITCH, MAX_PITCH, 1.0F);
        pitchVariation = clamp(pitchVariation, 0.0F, MAX_VARIATION, 0.0F);
        volumeVariation = clamp(volumeVariation, 0.0F, MAX_VARIATION, 0.0F);
    }

    /**
     * Builds the set of a single sample with the defaults of its key.
     *
     * <p>The defaults are what makes the improvement reach a pack without any change: a weapon that declares one
     * {@code fire} sample still gets the subtle pitch and volume spread of gunfire, while every other key stays exactly as
     * it was declared so reloads and draw sounds are not altered retroactively.</p>
     *
     * @param key  the key the sample belongs to, may be {@code null}
     * @param only the single sample
     * @return the sound set
     */
    public static WeaponSoundSet single(WeaponSoundKey key, ResourceId only) {
        return new WeaponSoundSet(List.of(only), 1.0F, 1.0F, defaultPitchVariation(key),
                defaultVolumeVariation(key));
    }

    /**
     * @param key the key, may be {@code null}
     * @return the relative pitch spread a key uses when the pack declares none
     */
    public static float defaultPitchVariation(WeaponSoundKey key) {
        return isFiring(key) ? DEFAULT_FIRE_PITCH_VARIATION : 0.0F;
    }

    /**
     * @param key the key, may be {@code null}
     * @return the relative volume spread a key uses when the pack declares none
     */
    public static float defaultVolumeVariation(WeaponSoundKey key) {
        return isFiring(key) ? DEFAULT_FIRE_VOLUME_VARIATION : 0.0F;
    }

    /**
     * @param key a sound key, may be {@code null}
     * @return whether the key is a gunshot, i.e. the one action whose repetition is audible
     */
    public static boolean isFiring(WeaponSoundKey key) {
        return key == WeaponSoundKey.FIRE || key == WeaponSoundKey.FIRE_SUPPRESSED;
    }

    /** @return the number of samples this key can choose from. */
    public int size() {
        return variants.size();
    }

    /** @return the first declared sample, which is the one a single-sample definition names. */
    public ResourceId primary() {
        return variants.get(0);
    }

    /**
     * @param index a sample index
     * @return the sample at that index, or the primary one when the index is outside the list
     */
    public ResourceId variant(int index) {
        if (index < 0 || index >= variants.size()) {
            return primary();
        }
        return variants.get(index);
    }

    /** @return whether this key can vary its sample, its pitch or its volume. */
    public boolean varies() {
        return variants.size() > 1 || pitchVariation > 0.0F || volumeVariation > 0.0F;
    }

    /**
     * @param unit a uniform random value in {@code [0, 1)}, clamped when outside
     * @return the pitch to play this sample at, inside {@link #MIN_PITCH} and {@link #MAX_PITCH}
     */
    public float pitchAt(float unit) {
        return applyVariation(pitch, pitchVariation, unit, MIN_PITCH, MAX_PITCH);
    }

    /**
     * @param unit a uniform random value in {@code [0, 1)}, clamped when outside
     * @return the volume to play this sample at, inside {@code 0} and {@link #MAX_VOLUME}
     */
    public float volumeAt(float unit) {
        return applyVariation(volume, volumeVariation, unit, 0.0F, MAX_VOLUME);
    }

    /**
     * Applies one relative variation around the base value.
     *
     * @param base      the base value
     * @param variation the relative spread, {@code 0} for none
     * @param unit      the random value in {@code [0, 1)}
     * @param minimum   the lowest allowed result
     * @param maximum   the highest allowed result
     * @return the varied value
     */
    private static float applyVariation(float base, float variation, float unit, float minimum, float maximum) {
        if (!(variation > 0.0F)) {
            return clamp(base, minimum, maximum, minimum);
        }
        float centred = clamp(unit, 0.0F, 1.0F, 0.5F) * 2.0F - 1.0F;
        return clamp(base * (1.0F + variation * centred), minimum, maximum, base);
    }

    /**
     * @param value    the value to clamp
     * @param minimum  the lowest allowed value
     * @param maximum  the highest allowed value
     * @param fallback the value used when {@code value} is not finite
     * @return the clamped value
     */
    private static float clamp(float value, float minimum, float maximum, float fallback) {
        if (!Float.isFinite(value)) {
            return Math.max(minimum, Math.min(maximum, fallback));
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
