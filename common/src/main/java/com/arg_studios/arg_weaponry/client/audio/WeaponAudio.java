package com.arg_studios.arg_weaponry.client.audio;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundKey;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSounds;
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

    /** Volume used for every weapon sound; the schema has no per-weapon volume yet. */
    public static final float DEFAULT_VOLUME = 1.0F;

    /** Pitch used for every weapon sound; the schema has no per-weapon pitch yet. */
    public static final float DEFAULT_PITCH = 1.0F;

    /** Identifiers (or weapon key pairs) already reported as unplayable. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private WeaponAudio() {
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
        Identifier id = resolve(definition, key);
        if (id == null) {
            return null;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getSoundManager() == null) {
            return null;
        }
        SoundInstance instance = SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvent
                .createVariableRangeEvent(id), DEFAULT_PITCH, DEFAULT_VOLUME);
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
        Identifier id = resolve(definition, key);
        if (id == null) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(id, SoundSource.PLAYERS,
                DEFAULT_VOLUME, DEFAULT_PITCH, RandomSource.create(), false, 0, SoundInstance.Attenuation.LINEAR,
                player.getX(), player.getEyeY(), player.getZ(), false));
        return true;
    }

    /**
     * Resolves the identifier of one sound key and checks that the client can actually play it.
     *
     * @param definition the weapon, may be {@code null}
     * @param key        the action, may be {@code null}
     * @return a playable identifier, or {@code null} when the key is unconfigured, invalid or unknown to the registry
     */
    public static Identifier resolve(WeaponDefinition definition, WeaponSoundKey key) {
        if (definition == null || key == null) {
            return null;
        }
        ResourceId configured = WeaponSounds.sound(definition.sounds(), key);
        if (configured == null) {
            // Documented behaviour: no entry means silence for this action, never a substitute.
            return null;
        }
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

    /** Forgets which identifiers were already reported, so a resource reload reports them again. */
    public static void reset() {
        REPORTED.clear();
        ActionSoundHandles.reset();
    }

    private static void reportOnce(String key, String message, Object... arguments) {
        if (REPORTED.add(key)) {
            Constants.LOG.debug("[ARG] " + message, arguments);
        }
    }
}
