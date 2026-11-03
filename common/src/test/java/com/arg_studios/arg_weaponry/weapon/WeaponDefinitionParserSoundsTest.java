package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundKey;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundSet;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The three accepted shapes of one {@code sounds} entry: a single identifier, an array of them, and an object with
 * {@code variants} plus tuning.
 *
 * <p>The contract under test is that the third shape is a strict superset of the first: an unchanged pack keeps exactly the
 * sound it declared, plus the gunshot variation, and a pack that declares several samples can rely on getting all of them
 * in the declared order.</p>
 */
class WeaponDefinitionParserSoundsTest {

    @Test
    void asingleIdentifierIsAOneSampleSet() {
        WeaponDefinitionParser.DeclaredSounds sounds = read("""
                {"sounds": {"fire": "arg_pack:guns/rifle_fire"}}
                """, new ValidationReport());

        WeaponSoundSet fire = sounds.sets().get("fire");
        assertNotNull(fire);
        assertEquals(1, fire.size());
        assertEquals("arg_pack:guns/rifle_fire", fire.primary().asString());
        assertEquals("arg_pack:guns/rifle_fire", sounds.primary().get("fire").asString(),
                "the primary identifier is unchanged, which is what every pre-existing caller reads");
        assertEquals(WeaponSoundSet.DEFAULT_FIRE_PITCH_VARIATION, fire.pitchVariation(),
                "a gunshot gets the default spread without the pack declaring anything");
        assertEquals(1.0F, fire.pitch());
        assertEquals(1.0F, fire.volume());
    }

    @Test
    void aNonFiringKeyKeepsItsDeclaredSoundUnvaried() {
        WeaponSoundSet reload = read("""
                {"sounds": {"reload": "arg_pack:guns/rifle_reload"}}
                """, new ValidationReport()).sets().get("reload");

        assertEquals(0.0F, reload.pitchVariation(), "a reload must not be re-pitched by default");
        assertEquals(0.0F, reload.volumeVariation());
        assertEquals(1.0F, reload.pitchAt(0.0F));
    }

    @Test
    void anArrayDeclaresSeveralSamplesInOrder() {
        WeaponSoundSet fire = read("""
                {"sounds": {"fire": ["arg_pack:guns/fire_1", "arg_pack:guns/fire_2", "arg_pack:guns/fire_3"]}}
                """, new ValidationReport()).sets().get("fire");

        assertEquals(3, fire.size());
        assertEquals("arg_pack:guns/fire_1", fire.variant(0).asString());
        assertEquals("arg_pack:guns/fire_2", fire.variant(1).asString());
        assertEquals("arg_pack:guns/fire_3", fire.variant(2).asString());
    }

    @Test
    void anObjectDeclaresVariantsAndTuning() {
        WeaponSoundSet fire = read("""
                {"sounds": {"fire": {
                  "variants": ["arg_pack:guns/fire_1", "arg_pack:guns/fire_2"],
                  "volume": 0.8,
                  "pitch": 0.95,
                  "pitch_variation": 0.02,
                  "volume_variation": 0.05
                }}}
                """, new ValidationReport()).sets().get("fire");

        assertEquals(2, fire.size());
        assertEquals(0.8F, fire.volume(), 1.0E-6F);
        assertEquals(0.95F, fire.pitch(), 1.0E-6F);
        assertEquals(0.02F, fire.pitchVariation(), 1.0E-6F);
        assertEquals(0.05F, fire.volumeVariation(), 1.0E-6F);
    }

    @Test
    void anObjectMayReuseTheSingleIdentifierSpelling() {
        WeaponSoundSet fire = read("""
                {"sounds": {"fire": {"sound": "arg_pack:guns/fire_1", "pitch_variation": 0.01}}}
                """, new ValidationReport()).sets().get("fire");

        assertEquals(1, fire.size());
        assertEquals("arg_pack:guns/fire_1", fire.primary().asString());
        assertEquals(0.01F, fire.pitchVariation(), 1.0E-6F);
    }

    @Test
    void outOfRangeTuningIsClampedInsteadOfRejected() {
        WeaponSoundSet fire = read("""
                {"sounds": {"fire": {"variants": ["arg_pack:guns/fire_1"], "volume": 40.0, "pitch": 0.01,
                            "pitch_variation": 9.0}}}
                """, new ValidationReport()).sets().get("fire");

        assertEquals(WeaponSoundSet.MAX_VOLUME, fire.volume());
        assertEquals(WeaponSoundSet.MIN_PITCH, fire.pitch());
        assertEquals(WeaponSoundSet.MAX_VARIATION, fire.pitchVariation());
    }

    @Test
    void aBrokenVariantIsDroppedAndTheRestIsKept() {
        ValidationReport report = new ValidationReport();

        WeaponSoundSet fire = read("""
                {"sounds": {"fire": ["arg_pack:guns/fire_1", "not a resource id !", "", "arg_pack:guns/fire_2"]}}
                """, report).sets().get("fire");

        assertEquals(2, fire.size(), "the two valid samples survive");
        assertEquals("arg_pack:guns/fire_2", fire.variant(1).asString());
        assertFalse(report.diagnostics().isEmpty(), "the creator is told about the invalid entry");
    }

    @Test
    void anEntryWithoutAnyUsableVariantIsIgnoredAndReported() {
        ValidationReport report = new ValidationReport();

        WeaponDefinitionParser.DeclaredSounds sounds = read("""
                {"sounds": {"fire": [], "reload": {"volume": 0.5}}}
                """, report);

        assertTrue(sounds.sets().isEmpty());
        assertTrue(sounds.primary().isEmpty());
        assertFalse(report.diagnostics().isEmpty());
    }

    @Test
    void anUnknownKeyIsIgnoredWithoutLosingTheKnownOnes() {
        ValidationReport report = new ValidationReport();

        WeaponDefinitionParser.DeclaredSounds sounds = read("""
                {"sounds": {"fire": "arg_pack:guns/fire_1", "firing": "arg_pack:guns/fire_2"}}
                """, report);

        assertNotNull(sounds.sets().get("fire"));
        assertFalse(sounds.sets().containsKey("firing"));
        assertFalse(report.diagnostics().isEmpty());
    }

    @Test
    void aMissingSoundsObjectIsSilenceAndNotAnError() {
        ValidationReport report = new ValidationReport();

        WeaponDefinitionParser.DeclaredSounds sounds = read("{}", report);

        assertTrue(sounds.sets().isEmpty());
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void everyDeclaredKeyKeepsItsPrimaryIdentifierConsistent() {
        WeaponDefinitionParser.DeclaredSounds sounds = read("""
                {"sounds": {
                  "fire": ["arg_pack:guns/fire_1", "arg_pack:guns/fire_2"],
                  "reload": "arg_pack:guns/reload",
                  "draw": {"variants": ["arg_pack:guns/draw_1", "arg_pack:guns/draw_2"], "volume": 0.7}
                }}
                """, new ValidationReport());

        for (WeaponSoundKey key : WeaponSoundKey.values()) {
            WeaponSoundSet set = sounds.sets().get(key.jsonName());
            if (set == null) {
                continue;
            }
            assertEquals(set.primary(), sounds.primary().get(key.jsonName()),
                    "the primary map must always name the first variant of the set for " + key.jsonName());
        }
    }

    private static WeaponDefinitionParser.DeclaredSounds read(String json, ValidationReport report) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new WeaponDefinitionParser("arg_pack", "weapons/sound_weapon.json",
                new ResourceId("arg_pack", "weapons/sound_weapon"), report).readSounds(root);
    }
}
