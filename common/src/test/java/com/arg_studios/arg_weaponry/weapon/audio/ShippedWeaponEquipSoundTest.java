package com.arg_studios.arg_weaponry.weapon.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The equip sounds of the shipped weapon, checked against the audio the pack actually contains.
 *
 * <p>This is the configuration half of the "equipping plays the inspection sound" report. Two different mistakes are
 * possible in a {@code sounds} block and both are covered here:</p>
 * <ul>
 *   <li>a key that names audio the pack does not ship - the action is silent even though it is configured;</li>
 *   <li>a key that borrows audio from another action - {@code draw} pointing at the empty-inspection sample is what the
 *   player heard, and no amount of code can make that sound like a draw.</li>
 * </ul>
 *
 * <p>The test reads the real weapon definition and the real pack resources from the build output, so a future edit of the
 * {@code sounds} block is covered by it.</p>
 */
class ShippedWeaponEquipSoundTest {

    /** Classpath prefix of the bundled default pack. */
    private static final String PACK = "assets/arg_weaponry/weapon_packs/arg_default_pack/assets/";

    private static final String WEAPON = PACK + "arg_pack/weapons_registry/weapons/dmrs/m100_dmr.json";

    /** The keys whose audio a draw or holster must never borrow. */
    private static final List<String> OTHER_ACTIONS = List.of("fire", "fire_suppressed", "reload", "reload_empty",
            "inspect", "inspect_empty", "headshot", "entity_hit", "entity_wasted");

    @Test
    void everyEquipActionOfTheShippedWeaponIsAudible() throws Exception {
        Map<String, ResourceId> sounds = parse(sounds());

        assertAudible("draw", sounds, WeaponSounds.drawKeys(sounds, false, false), true);
        assertAudible("draw_first", sounds, WeaponSounds.drawKeys(sounds, true, false), false);
        assertAudible("draw_empty", sounds, WeaponSounds.drawKeys(sounds, false, true), false);
        assertAudible("holster", sounds, WeaponSounds.holsterKeys(sounds, false), true);
        assertAudible("holster_empty", sounds, WeaponSounds.holsterKeys(sounds, true), false);
    }

    @Test
    void theEquipSoundsOfTheShippedWeaponNeverBorrowAnotherActionsAudio() throws Exception {
        assertNull(findBorrowedAudio(sounds()), "the shipped weapon borrows another action's audio");
    }

    @Test
    void theBorrowingCheckCatchesTheReportedInspectionMapping() throws Exception {
        // The regression itself: draw pointing at the empty-inspection sample. The shipped file no longer does this, so the
        // checker is also exercised against the configuration that caused the report.
        JsonObject buggy = JsoncReader.parse("""
                {
                  "inspect": "arg_pack:weapons/test/inspect",
                  "inspect_empty": "arg_pack:weapons/test/inspect_empty",
                  "draw": "arg_pack:weapons/test/inspect_empty"
                }
                """).getAsJsonObject();

        assertNotNull(findBorrowedAudio(buggy), "reusing the inspection audio for the draw sound must be reported");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * @param sounds a weapon's {@code sounds} object
     * @return a description of the first equip key that reuses another action's audio, or {@code null} when there is none
     */
    private static String findBorrowedAudio(JsonObject sounds) {
        for (String key : List.of("draw", "draw_first", "draw_empty", "holster", "holster_empty")) {
            JsonElement declared = sounds.get(key);
            if (declared == null || declared.isJsonNull()) {
                continue;
            }
            String id = declared.getAsString();
            if (id.contains("inspect")) {
                return "the '" + key + "' sound points at inspection audio (" + id + ")";
            }
            for (String other : OTHER_ACTIONS) {
                JsonElement otherId = sounds.get(other);
                if (otherId == null || otherId.isJsonNull()) {
                    continue;
                }
                if (id.equals(otherId.getAsString())) {
                    return "the '" + key + "' sound reuses the audio of '" + other + "' (" + id + ")";
                }
            }
        }
        return null;
    }

    /**
     * A configured equip action must be satisfiable: one of the variants the client walks has to resolve to audio the pack
     * ships. A base action ({@code draw}, {@code holster}) must also be declared at all.
     *
     * @param label    the action, for the failure message
     * @param sounds   the weapon's declared sounds
     * @param chain    the variants the client will try, best first
     * @param required whether the action has to be declared
     */
    private static void assertAudible(String label, Map<String, ResourceId> sounds, List<WeaponSoundKey> chain,
                                      boolean required) {
        if (chain.isEmpty()) {
            assertFalse(required, "the shipped weapon no longer declares a '" + label + "' sound");
            return;
        }
        boolean playable = false;
        for (WeaponSoundKey candidate : chain) {
            ResourceId id = sounds.get(candidate.jsonName());
            if (id != null && audioExists(id.asString())) {
                playable = true;
            }
        }
        assertTrue(playable, "no variant of the '" + label + "' sound resolves to audio the pack ships: " + chain);
    }

    private static Map<String, ResourceId> parse(JsonObject sounds) {
        Map<String, ResourceId> parsed = new LinkedHashMap<>();
        for (String key : sounds.keySet()) {
            String value = sounds.get(key).getAsString();
            int separator = value.indexOf(':');
            if (separator > 0) {
                parsed.put(key, new ResourceId(value.substring(0, separator), value.substring(separator + 1)));
            }
        }
        return parsed;
    }

    private static JsonObject sounds() throws Exception {
        JsonObject root = readJson(WEAPON).getAsJsonObject();
        JsonObject sounds = root.getAsJsonObject("sounds");
        assertTrue(sounds != null && !sounds.isEmpty(), "the shipped weapon declares no sounds at all");
        return sounds;
    }

    /**
     * @param soundId a sound identifier such as {@code arg_pack:weapons/dmrs/m100_dmr/m100_dmr_draw}
     * @return whether the pack ships the {@code .ogg} that identifier names
     */
    private static boolean audioExists(String soundId) {
        int separator = soundId.indexOf(':');
        if (separator <= 0) {
            return false;
        }
        String namespace = soundId.substring(0, separator);
        String path = soundId.substring(separator + 1);
        String resource = PACK + namespace + "/sounds/" + path + ".ogg";
        try (InputStream stream = ShippedWeaponEquipSoundTest.class.getClassLoader().getResourceAsStream(resource)) {
            return stream != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonElement readJson(String resource) throws Exception {
        try (InputStream stream = ShippedWeaponEquipSoundTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(stream != null, "the shipped resource " + resource + " is not on the test classpath");
            return JsoncReader.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
