package com.arg_studios.arg_weaponry.weapon.muzzle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.model.BedrockGeometryParser;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Attachment-point selection for one shot.
 *
 * <p>{@code bone} and {@code bones} are the same kind of data - the points a flash may appear at - so one rule covers
 * both: names the model does not contain are dropped, and exactly one of the survivors is used. A single survivor is
 * therefore used every shot, which is what the single-bone form means, and several survivors are chosen at random, never
 * all at once.</p>
 */
class MuzzleFlashAttachmentTest {

    @Test
    void aSingleConfiguredBoneIsUsedEveryShot() {
        List<String> present = MuzzleFlashAttachment.present(List.of("muzzle_flash"), model());

        assertEquals(List.of("muzzle_flash"), present);
        for (int shot = 0; shot < 20; shot++) {
            assertEquals("muzzle_flash", MuzzleFlashAttachment.select(present, new Random(shot)),
                    "one attachment point means the same point every shot");
        }
    }

    @Test
    void severalConfiguredBonesAreChosenOnePerShot() {
        List<String> present = MuzzleFlashAttachment.present(
                List.of("muzzle_flash", "muzzle_flash_2", "muzzle_flash_3"), model());
        assertEquals(3, present.size());

        Set<String> chosen = new HashSet<>();
        Random random = new Random(1234L);
        for (int shot = 0; shot < 200; shot++) {
            String bone = MuzzleFlashAttachment.select(present, random);
            assertNotNull(bone);
            assertTrue(present.contains(bone), "the chosen bone is always one of the configured points");
            chosen.add(bone);
        }
        assertEquals(3, chosen.size(), "over many shots every configured point is used, but one per shot");
    }

    @Test
    void aBoneTheModelDoesNotContainIsNeverChosen() {
        List<String> configured = List.of("missing_bone", "muzzle_flash");
        WeaponModel model = model();

        assertEquals(List.of("missing_bone"), MuzzleFlashAttachment.missing(configured, model));
        List<String> present = MuzzleFlashAttachment.present(configured, model);
        assertEquals(List.of("muzzle_flash"), present);
        for (int shot = 0; shot < 20; shot++) {
            assertEquals("muzzle_flash", MuzzleFlashAttachment.select(present, new Random(shot)));
        }
    }

    @Test
    void nothingUsableSelectsNothing() {
        assertEquals(List.of(), MuzzleFlashAttachment.present(List.of("a", "b"), model()));
        assertEquals(List.of(), MuzzleFlashAttachment.present(List.of(), model()));
        assertEquals(List.of(), MuzzleFlashAttachment.present(List.of("muzzle_flash"), (WeaponModel) null));
        assertNull(MuzzleFlashAttachment.select(List.of(), new Random()));
        assertNull(MuzzleFlashAttachment.select(null, new Random()));
    }

    @Test
    void duplicatesAndBlankEntriesAreDropped() {
        List<String> present = MuzzleFlashAttachment.present(
                List.of("muzzle_flash", "muzzle_flash", "  ", "muzzle_flash_2"), model());

        assertEquals(List.of("muzzle_flash", "muzzle_flash_2"), present);
    }

    // ---------------------------------------------------------------- helpers

    private static WeaponModel model() {
        JsonObject root = JsonParser.parseString(GEOMETRY).getAsJsonObject();
        ValidationReport report = new ValidationReport();
        WeaponModel model = BedrockGeometryParser.parse("arg_pack", new ResourceId("arg_pack", "models/test"),
                "models/test.json", root, report);
        assertNotNull(model, () -> "the test model must parse: " + report.diagnostics());
        return model;
    }

    /** Three attachment bones plus an unrelated one, all of them transform-only anchors. */
    private static final String GEOMETRY = """
            {
              "format_version": "1.12.0",
              "minecraft:geometry": [
                {
                  "description": {
                    "identifier": "geometry.arg_muzzle_test",
                    "texture_width": 64,
                    "texture_height": 64
                  },
                  "bones": [
                    { "name": "weapon", "pivot": [0, 0, 0],
                      "cubes": [ { "origin": [0, 0, 0], "size": [1, 1, 1], "uv": [0, 0] } ] },
                    { "name": "muzzle_flash", "parent": "weapon", "pivot": [0, 6, -24] },
                    { "name": "muzzle_flash_2", "parent": "weapon", "pivot": [0, 6, -20] },
                    { "name": "muzzle_flash_3", "parent": "weapon", "pivot": [0, 6, -16] },
                    { "name": "sights", "parent": "weapon", "pivot": [0, 8, 0] }
                  ]
                }
              ]
            }
            """;
}
