package com.arg_studios.arg_weaponry.weapon.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

/**
 * The sound an equip action selects, and the variants it may fall back to.
 *
 * <p>These tests pin the reported regression: equipping the M100 DMR played an <em>inspection</em> sound. That specific
 * mistake came from the weapon configuration, but the code now also guarantees that an equip action can never select an
 * inspect key, and that a declared-but-unplayable variant degrades to the next draw/holster variant instead of muting the
 * action.</p>
 */
class WeaponSoundsEquipTest {

    private static final String NAMESPACE = "arg_pack";

    private static final String PATH = "weapons/test/";

    // ---------------------------------------------------------------- draw order

    @Test
    void aFirstDrawPrefersTheFirstDrawSoundAndKeepsTheNormalDrawAsItsFallback() {
        Map<String, ResourceId> sounds = sounds("draw_first", "draw", "draw_empty");

        assertEquals(List.of(WeaponSoundKey.DRAW_FIRST, WeaponSoundKey.DRAW_EMPTY, WeaponSoundKey.DRAW),
                WeaponSounds.drawKeys(sounds, true, true),
                "a first draw of an empty weapon offers every declared variant, best first");
        assertEquals(WeaponSoundKey.DRAW_FIRST, WeaponSounds.drawKey(sounds, true, true));
    }

    @Test
    void aFirstDrawWithoutItsOwnSoundUsesTheEmptyThenTheNormalDraw() {
        Map<String, ResourceId> sounds = sounds("draw", "draw_empty");

        assertEquals(List.of(WeaponSoundKey.DRAW_EMPTY, WeaponSoundKey.DRAW),
                WeaponSounds.drawKeys(sounds, true, true),
                "no draw_first entry means the next declared variant is used, never silence");
        assertEquals(List.of(WeaponSoundKey.DRAW), WeaponSounds.drawKeys(sounds, true, false));
    }

    @Test
    void aNormalDrawUsesTheNormalDrawSound() {
        Map<String, ResourceId> sounds = sounds("draw_first", "draw", "draw_empty");

        assertEquals(List.of(WeaponSoundKey.DRAW), WeaponSounds.drawKeys(sounds, false, false));
        assertEquals(WeaponSoundKey.DRAW, WeaponSounds.drawKey(sounds, false, false));
    }

    @Test
    void anEmptyDrawPrefersTheEmptySoundAndFallsBackToTheNormalOne() {
        Map<String, ResourceId> sounds = sounds("draw", "draw_empty");

        assertEquals(List.of(WeaponSoundKey.DRAW_EMPTY, WeaponSoundKey.DRAW),
                WeaponSounds.drawKeys(sounds, false, true));

        assertEquals(List.of(WeaponSoundKey.DRAW), WeaponSounds.drawKeys(sounds("draw"), false, true),
                "a weapon without a draw_empty entry draws with the normal sound");
    }

    // ---------------------------------------------------------------- holster order

    @Test
    void aLoadedHolsterUsesTheHolsterSoundAndAnEmptyOnePrefersTheEmptyVariant() {
        Map<String, ResourceId> sounds = sounds("holster", "holster_empty");

        assertEquals(List.of(WeaponSoundKey.HOLSTER), WeaponSounds.holsterKeys(sounds, false));
        assertEquals(List.of(WeaponSoundKey.HOLSTER_EMPTY, WeaponSoundKey.HOLSTER),
                WeaponSounds.holsterKeys(sounds, true));
        assertEquals(List.of(WeaponSoundKey.HOLSTER), WeaponSounds.holsterKeys(sounds("holster"), true),
                "a weapon without a holster_empty entry is still audible when put away empty");
    }

    // ---------------------------------------------------------------- silence and scope

    @Test
    void aWeaponThatDeclaresNoEquipSoundIsSilentInsteadOfBorrowingOne() {
        Map<String, ResourceId> onlyActions = sounds("fire", "reload", "inspect", "inspect_empty");

        assertTrue(WeaponSounds.drawKeys(onlyActions, true, true).isEmpty());
        assertNull(WeaponSounds.drawKey(onlyActions, true, true));
        assertTrue(WeaponSounds.holsterKeys(onlyActions, true).isEmpty());
        assertNull(WeaponSounds.holsterKey(onlyActions, true));
        assertTrue(WeaponSounds.drawKeys(null, true, true).isEmpty(), "no sounds object at all is silent");
    }

    @Test
    void anEquipActionNeverSelectsAnInspectOrFiringSound() {
        Map<String, ResourceId> sounds = sounds("fire", "inspect", "inspect_empty", "draw", "holster");

        for (boolean firstDraw : new boolean[] {true, false}) {
            for (boolean empty : new boolean[] {true, false}) {
                for (WeaponSoundKey key : WeaponSounds.drawKeys(sounds, firstDraw, empty)) {
                    assertTrue(isDrawKey(key), "a draw may only select a draw variant but selected " + key);
                }
            }
            for (WeaponSoundKey key : WeaponSounds.holsterKeys(sounds, true)) {
                assertTrue(isHolsterKey(key), "a holster may only select a holster variant but selected " + key);
            }
        }
        assertFalse(WeaponSounds.drawKeys(sounds, true, true).contains(WeaponSoundKey.INSPECT_EMPTY),
                "equipping a weapon must never play the empty-inspection sound");
    }

    @Test
    void theServerKeyIsTheFirstElementOfTheClientChain() {
        // The server broadcasts one identifier and cannot know which audio a client can play, so its key has to be exactly
        // the variant the client tries first; otherwise other players would hear a different sound than the player does.
        Map<String, ResourceId> sounds = sounds("draw_first", "draw", "draw_empty", "holster", "holster_empty");

        for (boolean firstDraw : new boolean[] {true, false}) {
            for (boolean empty : new boolean[] {true, false}) {
                List<WeaponSoundKey> keys = WeaponSounds.drawKeys(sounds, firstDraw, empty);
                assertEquals(keys.get(0), WeaponSounds.drawKey(sounds, firstDraw, empty));
            }
        }
        assertEquals(WeaponSounds.holsterKeys(sounds, true).get(0), WeaponSounds.holsterKey(sounds, true));
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isDrawKey(WeaponSoundKey key) {
        return key == WeaponSoundKey.DRAW || key == WeaponSoundKey.DRAW_FIRST || key == WeaponSoundKey.DRAW_EMPTY;
    }

    private static boolean isHolsterKey(WeaponSoundKey key) {
        return key == WeaponSoundKey.HOLSTER || key == WeaponSoundKey.HOLSTER_EMPTY;
    }

    private static Map<String, ResourceId> sounds(String... keys) {
        Map<String, ResourceId> sounds = new LinkedHashMap<>();
        for (String key : keys) {
            sounds.put(key, new ResourceId(NAMESPACE, PATH + key));
        }
        return sounds;
    }
}
