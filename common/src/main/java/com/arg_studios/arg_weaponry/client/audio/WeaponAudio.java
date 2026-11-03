package com.arg_studios.arg_weaponry.client.audio;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.audio.SoundVariationPicker;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundKey;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's weapon audio engine: one place that turns a configured sound key into playback.
 *
 * <h2>What it does, and what it refuses to do</h2>
 * <p>It resolves the identifier the weapon declares for a key and plays it. It never substitutes: an unconfigured key
 * is silent (no vanilla sound, no other key, no fallback), an invalid identifier is silent and reported once, and an
 * identifier that the client's sound registry does not know is silent and reported once. A missing sound therefore can
 * never keep its action from working, which is why every call site may simply call this class after the server
 * accepted the action.</p>
 *
 * <h2>Variation</h2>
 * <p>A key may declare several samples (see {@link WeaponSoundSet}); every playback then asks
 * {@link SoundVariationPicker} which one to use, which deals the samples in a shuffled order so the same recording is
 * never heard twice in a row, and applies the key's pitch and volume spread to that one playback. A key that declares a
 * single sample behaves exactly as before except for the gunshot's default spread, and a key that declares no variation
 * plays at its declared pitch and volume. Selection is per weapon and key, so a shot and a reload never consume each
 * other's samples.</p>
 *
 * <h2>Two playback shapes</h2>
 * <ul>
 *   <li>{@link #playFirstPerson(WeaponDefinition, WeaponSoundKey)} - a non-positional {@link SoundSource#UI} sound, used
 *   for the player's own weapon actions that belong to the view model (reload, inspect, draw, holster, hit feedback).</li>
 *   <li>{@link #playWorld(WeaponDefinition, WeaponSoundKey)} - a positional {@link SoundSource#PLAYERS} instance at the
 *   local player, used for that player's own gunshot so it pans when looking around. It uses linear attenuation, so it
 *   is a real world sound and not a forced non-positional one.</li>
 * </ul>
 * <p>Other players' shots never come through this class: the server broadcasts them to everyone except the shooter (see
 * {@code WeaponSoundBroadcast}), which is what keeps the shooter from hearing the same shot twice.</p>
 *
 * <h2>Reporting</h2>
 * <p>Each unplayable identifier is reported once at {@code DEBUG} level and forgotten on the next resource reload, so a
 * pack without sound files produces one diagnostic instead of one per shot, and the log is not flooded at
 * {@code INFO}.</p>
 */
public final class WeaponAudio {

    /** Volume used when a definition declares none; the schema's default for every key. */
    public static final float DEFAULT_VOLUME = 1.0F;

    /** Pitch used when a definition declares none; the schema's default for every key. */
    public static final float DEFAULT_PITCH = 1.0F;

    /** Identifiers (or weapon key pairs) already reported as unplayable. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * The variant rotation of every weapon sound.
     *
     * <p>It is keyed by weapon and key, so the shot of one weapon and the shot of another - or the shot and the reload of
     * one weapon - never consume each other's samples, and each stream deals every sample before repeating one.</p>
     */
    private static final SoundVariationPicker VARIANTS = new SoundVariationPicker();

    /** The randomness of the sample choice and of the pitch and volume variation. */
    private static final RandomSource RANDOM = RandomSource.create();

    private WeaponAudio() {
    }

    /**
     * One resolved playback: which identifier to play, and at which volume and pitch.
     *
     * @param id     the playable identifier
     * @param volume the volume of this playback, already varied
     * @param pitch  the pitch of this playback, already varied
     */
    public record Playback(Identifier id, float volume, float pitch) {
    }

    /**
     * Plays the sound a weapon declares for one key as a first-person (non-positional) sound.
     *
     * @param definition the weapon whose sound to play, may be {@code null}
     * @param key        the action that just happened, may be {@code null}
     * @return {@code true} when a sound instance was started
     */
    public static boolean playFirstPerson(WeaponDefinition definition, WeaponSoundKey key) {
        return playFirstPersonTracked(definition, key) != null;
    }

    /**
     * Plays a first-person sound and returns the instance, so its owner can stop exactly this playback.
     *
     * <p>Milestone 10S needs the instance, not just "did something play": an inspection interrupted by a shot must stop
     * <em>its own</em> remaining audio without touching another hand's or another player's copy of the same identifier.
     * The caller keeps the returned instance in {@link ActionSoundHandles}.</p>
     *
     * @param definition the weapon whose sound to play, may be {@code null}
     * @param key        the action that just happened, may be {@code null}
     * @return the instance that was started, or {@code null} when nothing played
     */
    public static SoundInstance playFirstPersonTracked(WeaponDefinition definition, WeaponSoundKey key) {
        Playback playback = resolvePlayback(definition, key, RANDOM::nextInt);
        if (playback == null) {
            return null;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getSoundManager() == null) {
            return null;
        }
        SoundInstance instance = SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvent
                .createVariableRangeEvent(playback.id()), playback.pitch(), playback.volume());
        client.getSoundManager().play(instance);
        return instance;
    }

    /**
     * Plays the sound a weapon declares for one key positionally at the local player.
     *
     * @param definition the weapon whose sound to play, may be {@code null}
     * @param key        the action that just happened, may be {@code null}
     * @return {@code true} when a sound instance was started
     */
    public static boolean playWorld(WeaponDefinition definition, WeaponSoundKey key) {
        Playback playback = resolvePlayback(definition, key, RANDOM::nextInt);
        if (playback == null) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(playback.id(), SoundSource.PLAYERS,
                playback.volume(), playback.pitch(), RandomSource.create(), false, 0,
                SoundInstance.Attenuation.LINEAR,
                player.getX(), player.getEyeY(), player.getZ(), false));
        return true;
    }

    /**
     * Resolves one playback of a key: which variant, at which pitch and volume.
     *
     * <p>The order of decisions is deliberate. The variant is chosen from the key's shuffle bag first, so the sample
     * rotation is never disturbed by an unplayable variant; then the pitch and the volume are varied from two independent
     * uniform values, so a shot is not systematically the loudest and the shortest at the same time. A variant the client
     * cannot play (a missing {@code .ogg}, an identifier the sound registry does not know) is skipped in favour of the next
     * one, which is what keeps a pack that names a sound file it does not ship from muting a key that has other, usable
     * samples.</p>
     *
     * @param definition the weapon whose sound to play, may be {@code null}
     * @param key        the action that just happened, may be {@code null}
     * @param shuffle    the source of the variant rotation's random numbers, or {@code null} to always use the first sample
     * @return the playback to start, or {@code null} when nothing can be played
     */
    public static Playback resolvePlayback(WeaponDefinition definition, WeaponSoundKey key,
                                           SoundVariationPicker.IndexShuffler shuffle) {
        if (definition == null || key == null) {
            return null;
        }
        WeaponSoundSet set = definition.soundSet(key);
        if (set == null) {
            // Documented behaviour: no entry means silence for this action, never a substitute.
            return null;
        }
        int count = set.size();
        int chosen = VARIANTS.pick(variantStream(definition, key), count, shuffle);
        for (int attempt = 0; attempt < count; attempt++) {
            ResourceId configured = set.variant((chosen + attempt) % count);
            Identifier id = playable(definition, key, configured);
            if (id != null) {
                return new Playback(id, set.volumeAt(unit()), set.pitchAt(unit()));
            }
        }
        return null;
    }

    /**
     * Resolves the identifier of one sound key and checks that the client can actually play it.
     *
     * @param definition the weapon, may be {@code null}
     * @param key        the action, may be {@code null}
     * @return a playable identifier, or {@code null} when the key is unconfigured, invalid or unknown to the registry
     */
    public static Identifier resolve(WeaponDefinition definition, WeaponSoundKey key) {
        Playback playback = resolvePlayback(definition, key, RANDOM::nextInt);
        return playback == null ? null : playback.id();
    }

    /**
     * Validates one declared identifier of a key.
     *
     * @param definition the weapon, never {@code null}
     * @param key        the action, never {@code null}
     * @param configured the declared identifier
     * @return the identifier when the client can play it, otherwise {@code null}
     */
    private static Identifier playable(WeaponDefinition definition, WeaponSoundKey key, ResourceId configured) {
        Identifier id = Identifier.tryParse(configured.asString());
        if (id == null) {
            reportOnce("invalid:" + configured, "Weapon '{}' declares '{}' as its {} sound, which is not a valid "
                    + "resource identifier; the action stays silent", definition.name(), configured, key.jsonName());
            return null;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getSoundManager() == null || client.getSoundManager().getSoundEvent(id) == null) {
            reportOnce("unregistered:" + id, "The {} sound '{}' of weapon '{}' is not a registered sound event; no "
                    + "audio is played", key.jsonName(), id, definition.name());
            return null;
        }
        return id;
    }

    /**
     * @param definition the weapon
     * @param key        the action
     * @return the identity of the sample rotation this weapon uses for this key
     */
    private static String variantStream(WeaponDefinition definition, WeaponSoundKey key) {
        ResourceId identity = definition.authoritativeId() != null ? definition.authoritativeId()
                : definition.fileIdentity();
        return identity + "#" + key.jsonName();
    }

    /** @return a uniform value in {@code [0, 1)} for one variation. */
    private static float unit() {
        return RANDOM.nextFloat();
    }

    /** Forgets which identifiers were already reported, so a resource reload reports them again. */
    public static void reset() {
        REPORTED.clear();
        VARIANTS.reset();
        ActionSoundHandles.reset();
    }

    private static void reportOnce(String key, String message, Object... arguments) {
        if (REPORTED.add(key)) {
            Constants.LOG.debug("[ARG] " + message, arguments);
        }
    }
}
