package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundKey;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSounds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Server-side broadcast of the draw and holster sounds other players are meant to hear.
 *
 * <p>Same shape as {@code WeaponSoundBroadcast}, which does this for the gunshot: the identifier the weapon declares for the
 * key is played at the player's eyes with the player excluded, so everyone nearby hears it positionally and the player
 * themselves - whose own client plays the view-model sound locally - never hears it twice.</p>
 *
 * <p>Nothing is substituted: an undeclared key is silent for everyone, and an identifier the clients cannot resolve is
 * simply not played there (a content pack is a resource pack; a client that lacks it hears nothing, exactly like a
 * vanilla resource pack it does not have).</p>
 */
public final class EquipSoundBroadcast {

    private EquipSoundBroadcast() {
    }

    /**
     * @param player        the player who drew the weapon
     * @param definition    the weapon being drawn, may be {@code null}
     * @param firstDraw     whether this is the stack's first draw, which selects {@code sounds.draw_first}
     * @param emptyMagazine whether the magazine is empty, which selects {@code sounds.draw_empty}
     * @return whether a sound was broadcast
     */
    public static boolean draw(ServerPlayer player, WeaponDefinition definition, boolean firstDraw,
                               boolean emptyMagazine) {
        if (definition == null) {
            return false;
        }
        return play(player, definition, WeaponSounds.drawKey(definition.sounds(), firstDraw, emptyMagazine));
    }

    /**
     * @param player        the player who holstered the weapon
     * @param definition    the weapon being holstered, may be {@code null}
     * @param emptyMagazine whether the magazine is empty, which selects {@code sounds.holster_empty}
     * @return whether a sound was broadcast
     */
    public static boolean holster(ServerPlayer player, WeaponDefinition definition, boolean emptyMagazine) {
        if (definition == null) {
            return false;
        }
        return play(player, definition, WeaponSounds.holsterKey(definition.sounds(), emptyMagazine));
    }

    /**
     * Plays one configured key to everyone except the given player.
     *
     * @param player     the player to exclude, may be {@code null}
     * @param definition the weapon, may be {@code null}
     * @param key        the key to play, may be {@code null}
     * @return whether a sound was broadcast
     */
    private static boolean play(ServerPlayer player, WeaponDefinition definition, WeaponSoundKey key) {
        if (player == null || definition == null || key == null) {
            return false;
        }
        ResourceId configured = WeaponSounds.sound(definition.sounds(), key);
        if (configured == null) {
            return false;
        }
        Identifier location = Identifier.tryParse(configured.asString());
        if (location == null) {
            Constants.LOG.debug("[ARG] Weapon '{}' declares an invalid '{}' sound '{}'; nothing is broadcast",
                    definition.name(), key.jsonName(), configured);
            return false;
        }
        player.level().playSound(player, player.getX(), player.getEyeY(), player.getZ(),
                SoundEvent.createVariableRangeEvent(location), SoundSource.PLAYERS, 1.0F, 1.0F);
        return true;
    }
}
