package com.arg_studios.arg_weaponry.weapon.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * The sound keys a weapon definition may declare inside its {@code sounds} object.
 *
 * <p>Every key is independently optional: a definition may declare all of them, one of them, or none at all. A key that
 * is absent (or written as {@code null}, or as an empty string) means <em>silence</em> for exactly that one action - it
 * is never replaced by another key, by a vanilla sound or by a previously declared sound of another weapon.</p>
 *
 * <h2>Why an enum and not free-form keys</h2>
 * <p>The gameplay code asks for a sound by its <em>meaning</em> ({@link #FIRE}, {@link #RELOAD_EMPTY}, ...), so a typo in
 * a content pack cannot silently create a key nothing ever plays. The parser reports an unknown key as a warning and
 * ignores it; the known spellings here are exactly the ones the schema documents.</p>
 */
public enum WeaponSoundKey {

    /** A normal shot. */
    FIRE("fire"),

    /** A shot of a weapon that only declares the silenced variant; see {@link WeaponSounds#firingKey(java.util.Map, boolean)}. */
    FIRE_SUPPRESSED("fire_suppressed"),

    /** A reload with ammunition in the magazine. */
    RELOAD("reload"),

    /** A reload that started on an empty magazine. */
    RELOAD_EMPTY("reload_empty"),

    /** Inspecting a weapon that has ammunition. */
    INSPECT("inspect"),

    /** Inspecting a weapon whose magazine is empty. */
    INSPECT_EMPTY("inspect_empty"),

    /** Drawing a weapon that has already been drawn in this session. */
    DRAW("draw"),

    /** Drawing a weapon for the first time since the client joined the world. */
    DRAW_FIRST("draw_first"),

    /** Drawing a weapon whose magazine is empty. */
    DRAW_EMPTY("draw_empty"),

    /** Putting a weapon away with rounds left. */
    HOLSTER("holster"),

    /** Putting an empty weapon away. */
    HOLSTER_EMPTY("holster_empty"),

    /** The shot hit an entity's head region. */
    HEADSHOT("headshot"),

    /** The shot hit an entity without killing it. */
    ENTITY_HIT("entity_hit"),

    /** The shot killed the entity it hit. */
    ENTITY_WASTED("entity_wasted");

    private final String jsonName;

    WeaponSoundKey(String jsonName) {
        this.jsonName = jsonName;
    }

    /** @return the key exactly as it is written in the {@code sounds} object. */
    public String jsonName() {
        return jsonName;
    }

    /**
     * @param name a key from a {@code sounds} object, may be {@code null}
     * @return the matching key, or {@code null} when the schema does not define it
     */
    public static WeaponSoundKey byJsonName(String name) {
        if (name == null) {
            return null;
        }
        for (WeaponSoundKey key : values()) {
            if (key.jsonName.equals(name)) {
                return key;
            }
        }
        return null;
    }

    /** @return every declared key name, in declaration order, for diagnostics. */
    public static List<String> jsonNames() {
        List<String> names = new ArrayList<>(values().length);
        for (WeaponSoundKey key : values()) {
            names.add(key.jsonName);
        }
        return List.copyOf(names);
    }
}
