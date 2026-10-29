package com.arg_studios.arg_weaponry.weapon.audio;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Server-side broadcast of the weapon sounds other players are meant to hear.
 *
 * <h2>Which sounds, and to whom</h2>
 * <p>Only the gunshot is broadcast. It is played at the shooter's eyes, with the shooter excluded, so every nearby player
 * hears it positionally and the shooter - whose own client plays the same shot locally, from the accepted-shot result -
 * never hears it twice. Everything that belongs to the view model (reload, inspect, draw, holster) and all hit feedback
 * stays local to the shooter, which is both cheaper and the conventional behaviour.</p>
 *
 * <h2>How a content-pack sound reaches another client</h2>
 * <p>The identifier is sent as a <em>direct</em> {@link SoundEvent} ({@link SoundEvent#createVariableRangeEvent}), not as
 * a registry holder. The client resolves it against its own sound registry, which contains the content packs mounted by
 * {@code PackRepositoryMixin}; a client that cannot resolve it plays nothing, exactly like a vanilla resource pack it
 * does not have. No new packet type is needed: vanilla's own sound packet carries the identifier.</p>
 */
public final class WeaponSoundBroadcast {

    private WeaponSoundBroadcast() {
    }

    /**
     * Plays the weapon's firing sound to every player except the shooter.
     *
     * @param shooter    the player who fired
     * @param definition the weapon definition of the fired weapon
     * @return {@code true} when a sound was broadcast
     */
    public static boolean shot(ServerPlayer shooter, WeaponDefinition definition) {
        if (shooter == null || definition == null) {
            return false;
        }
        WeaponSoundKey key = WeaponSounds.firingKey(definition.sounds(), false);
        ResourceId configured = WeaponSounds.sound(definition.sounds(), key);
        if (configured == null) {
            // Documented: no firing entry means the weapon is silent for everyone, never a substitute.
            return false;
        }
        Identifier location = Identifier.tryParse(configured.asString());
        if (location == null) {
            Constants.LOG.debug("[ARG] Weapon '{}' declares an invalid firing sound '{}'; nothing is broadcast",
                    definition.name(), configured);
            return false;
        }
        shooter.level().playSound(shooter, shooter.getX(), shooter.getEyeY(), shooter.getZ(),
                SoundEvent.createVariableRangeEvent(location), SoundSource.PLAYERS, 1.0F, 1.0F);
        return true;
    }
}
