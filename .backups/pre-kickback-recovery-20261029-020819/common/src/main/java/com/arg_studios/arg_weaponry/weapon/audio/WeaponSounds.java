package com.arg_studios.arg_weaponry.weapon.audio;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

import java.util.Map;

/**
 * The weapon-defined sound selection rules.
 *
 * <p>This is the single place that answers "which sound does this action of this weapon use", and it is deliberately
 * pure: no Minecraft types, no registry lookups, no playback. The client and the server both call it, which is what
 * keeps the two sides from disagreeing about which key belongs to an action.</p>
 *
 * <h2>Absent means silent</h2>
 * <p>Every lookup returns {@code null} when the weapon does not declare that key. Nothing is substituted: a weapon
 * without {@code reload_empty} reloads silently, a weapon without {@code headshot} plays no headshot sound, and a
 * weapon without a {@code sounds} object at all is silent everywhere. A {@code null}, blank or whitespace-only
 * identifier is treated exactly like a missing entry, because the parser already drops those.</p>
 */
public final class WeaponSounds {

    private WeaponSounds() {
    }

    /**
     * @param sounds the definition's raw {@code sounds} map, may be {@code null}
     * @param key    the action being performed, may be {@code null}
     * @return the configured identifier, or {@code null} when that action is unconfigured for this weapon
     */
    public static ResourceId sound(Map<String, ResourceId> sounds, WeaponSoundKey key) {
        if (sounds == null || sounds.isEmpty() || key == null) {
            return null;
        }
        return sounds.get(key.jsonName());
    }

    /**
     * @param sounds the definition's raw {@code sounds} map, may be {@code null}
     * @param key    the action being performed
     * @return whether the weapon declares a playable identifier for that action
     */
    public static boolean has(Map<String, ResourceId> sounds, WeaponSoundKey key) {
        return sound(sounds, key) != null;
    }

    /**
     * The key a shot of this weapon uses.
     *
     * <p>No suppressor or attachment system exists in this milestone, so the suppressed variant has exactly one
     * well-defined meaning: <strong>a weapon that declares {@code fire_suppressed} but not {@code fire} is a silenced
     * weapon</strong>, and every shot of it uses the suppressed sound. A weapon that declares both uses {@code fire},
     * because there is no suppressed state to select the other one with. This keeps the key usable today and leaves the
     * {@code suppressed} parameter as the single seam a future attachment system has to flip.</p>
     *
     * @param sounds     the definition's raw {@code sounds} map, may be {@code null}
     * @param suppressed whether the weapon is currently in its suppressed state; no system sets this yet
     * @return the key to play, or {@code null} when this weapon declares neither firing sound
     */
    public static WeaponSoundKey firingKey(Map<String, ResourceId> sounds, boolean suppressed) {
        boolean silencedByDeclaration = !has(sounds, WeaponSoundKey.FIRE)
                && has(sounds, WeaponSoundKey.FIRE_SUPPRESSED);
        if (suppressed || silencedByDeclaration) {
            return has(sounds, WeaponSoundKey.FIRE_SUPPRESSED) ? WeaponSoundKey.FIRE_SUPPRESSED : null;
        }
        return has(sounds, WeaponSoundKey.FIRE) ? WeaponSoundKey.FIRE : null;
    }

    /**
     * @param emptyMagazine whether the magazine was empty when the reload started
     * @return {@link WeaponSoundKey#RELOAD_EMPTY} or {@link WeaponSoundKey#RELOAD}
     */
    public static WeaponSoundKey reloadKey(boolean emptyMagazine) {
        return emptyMagazine ? WeaponSoundKey.RELOAD_EMPTY : WeaponSoundKey.RELOAD;
    }

    /**
     * @param emptyMagazine whether the magazine is empty while inspecting
     * @return {@link WeaponSoundKey#INSPECT_EMPTY} or {@link WeaponSoundKey#INSPECT}
     */
    public static WeaponSoundKey inspectKey(boolean emptyMagazine) {
        return emptyMagazine ? WeaponSoundKey.INSPECT_EMPTY : WeaponSoundKey.INSPECT;
    }

    /**
     * The key for drawing a weapon.
     *
     * <p>Order of precedence, documented because the schema has three variants: the first draw of a weapon in this
     * session uses {@code draw_first} when it is declared; otherwise an empty magazine uses {@code draw_empty}; in every
     * other case the ordinary {@code draw} is used. A weapon that declares none of them is silent when drawn.</p>
     *
     * @param sounds        the definition's raw {@code sounds} map, may be {@code null}
     * @param firstDraw     whether this is the first draw of this weapon since the client joined the world
     * @param emptyMagazine whether the magazine is empty
     * @return the key to play, or {@code null} when the weapon declares none of the three
     */
    public static WeaponSoundKey drawKey(Map<String, ResourceId> sounds, boolean firstDraw, boolean emptyMagazine) {
        if (firstDraw && has(sounds, WeaponSoundKey.DRAW_FIRST)) {
            return WeaponSoundKey.DRAW_FIRST;
        }
        if (emptyMagazine && has(sounds, WeaponSoundKey.DRAW_EMPTY)) {
            return WeaponSoundKey.DRAW_EMPTY;
        }
        return has(sounds, WeaponSoundKey.DRAW) ? WeaponSoundKey.DRAW : null;
    }

    /**
     * @param sounds        the definition's raw {@code sounds} map, may be {@code null}
     * @param emptyMagazine whether the magazine is empty
     * @return {@link WeaponSoundKey#HOLSTER_EMPTY} when the magazine is empty and that key is declared, otherwise
     *         {@link WeaponSoundKey#HOLSTER}, otherwise {@code null}
     */
    public static WeaponSoundKey holsterKey(Map<String, ResourceId> sounds, boolean emptyMagazine) {
        if (emptyMagazine && has(sounds, WeaponSoundKey.HOLSTER_EMPTY)) {
            return WeaponSoundKey.HOLSTER_EMPTY;
        }
        return has(sounds, WeaponSoundKey.HOLSTER) ? WeaponSoundKey.HOLSTER : null;
    }

    /**
     * The key for a hit, from the hit's own facts.
     *
     * <p>A kill plays {@code entity_wasted} when it is declared; a non-lethal headshot plays {@code headshot}; a
     * non-lethal body hit plays {@code entity_hit}. Every branch is independently optional, so a weapon that declares
     * only {@code headshot} stays silent on a body hit.</p>
     *
     * @param sounds   the definition's raw {@code sounds} map, may be {@code null}
     * @param headshot whether the hit landed in the entity's head region
     * @param killed   whether the hit killed the entity
     * @return the key to play, or {@code null} when the applicable key is not declared
     */
    public static WeaponSoundKey hitKey(Map<String, ResourceId> sounds, boolean headshot, boolean killed) {
        if (killed) {
            return has(sounds, WeaponSoundKey.ENTITY_WASTED) ? WeaponSoundKey.ENTITY_WASTED : null;
        }
        if (headshot) {
            return has(sounds, WeaponSoundKey.HEADSHOT) ? WeaponSoundKey.HEADSHOT : null;
        }
        return has(sounds, WeaponSoundKey.ENTITY_HIT) ? WeaponSoundKey.ENTITY_HIT : null;
    }

    /**
     * @param sounds the definition's raw {@code sounds} map, may be {@code null}
     * @return the number of declared keys, for diagnostics
     */
    public static int size(Map<String, ResourceId> sounds) {
        return sounds == null ? 0 : sounds.size();
    }
}
