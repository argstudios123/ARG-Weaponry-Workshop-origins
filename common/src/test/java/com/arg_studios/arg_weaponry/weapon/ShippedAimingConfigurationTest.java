package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.weapon.animation.AnimationFile;
import com.arg_studios.arg_weaponry.weapon.animation.BedrockAnimationParser;
import com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve;
import com.arg_studios.arg_weaponry.weapon.model.BedrockGeometryParser;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The shipped weapon's new aiming configuration and the assets the features depend on. */
class ShippedAimingConfigurationTest {

    private static final String PACK = "assets/arg_weaponry/weapon_packs/arg_default_pack/assets/arg_pack/";
    private static final String WEAPON = PACK + "weapons_registry/weapons/dmrs/m100_dmr.json";
    private static final String MODEL = PACK + "models/weapons/dmrs/m100_dmr/m100_dmr.json";
    private static final String ANIMATIONS = PACK
            + "animations/weapons/dmrs/m100_dmr/m100_dmr.animation.json";
    private static final String LIBRARY = PACK
            + "default_animations/weapons/pistols/walking_animations.animation.json";

    @Test
    void theShippedWeaponDeclaresTheNewAimingBlocksAndTheReusableLibrary() throws Exception {
        JsonObject root = readJson(WEAPON).getAsJsonObject();
        ValidationReport report = new ValidationReport();
        WeaponDefinitionParser parser = new WeaponDefinitionParser("arg_default_pack", WEAPON,
                new ResourceId("arg_pack", "weapons/dmrs/m100_dmr"), report);
        WeaponDefinition.Aiming aiming = parser.readAiming(root);

        assertFalse(report.hasErrors(), "the shipped aiming block must parse without errors");
        assertNotNull(aiming);
        assertTrue(aiming.enabled());
        assertTrue(aiming.adsConstraint().enabled());
        assertTrue(aiming.adsConstraint().camera());
        assertTrue(aiming.adsConstraint().hasCameraConstraint());
        assertEquals(0.2, aiming.adsConstraint().cameraConstraintModifier(), 1.0E-9);
        assertTrue(aiming.adsReloadZoom().enabled());
        assertEquals("smooth", aiming.adsReloadZoom().zoomEffectType());
        assertEquals(0.08, aiming.adsReloadZoom().zoomAmount(), 1.0E-9);
        assertEquals(ZoomCurve.SMOOTH, aiming.adsReloadZoom().curve());
        List<String> libraries = new java.util.ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("reusable_default_animations")) {
            libraries.add(element.getAsString());
        }
        assertEquals(List.of("default_animations/weapons/pistols/walking_animations"), libraries);
    }

    @Test
    void theShippedWeaponDeclaresTheSprintGateAndKickbackAimingModifier() throws Exception {
        JsonObject root = readJson(WEAPON).getAsJsonObject();
        ValidationReport report = new ValidationReport();
        WeaponDefinitionParser parser = new WeaponDefinitionParser("arg_default_pack", WEAPON,
                new ResourceId("arg_pack", "weapons/dmrs/m100_dmr"), report);

        WeaponDefinition.Recoil recoil = parser.readRecoil(root);

        assertFalse(parser.readAllowFiringWhenSprinting(root),
                "the shipped weapon explicitly forbids firing while sprinting");
        assertNotNull(recoil);
        assertEquals(0.06, recoil.kickbackAimingModifier(), 1.0E-9);
        assertTrue(report.diagnostics().isEmpty(), "both new properties are part of the schema, not warnings");
    }

    @Test
    void theShippedModelUsesAdsOffsetAndNoLongerUsesConstraint() throws Exception {
        ValidationReport report = new ValidationReport();
        WeaponModel model = BedrockGeometryParser.parse("arg_default_pack",
                new ResourceId("arg_pack", "models/weapons/dmrs/m100_dmr/m100_dmr"), MODEL,
                readJson(MODEL).getAsJsonObject(), report);

        assertNotNull(model);
        assertFalse(report.hasErrors());
        ModelBone offset = model.bone("ads_offset");
        assertNotNull(offset, "the ADS constraint bone must exist");
        assertEquals("Right_arm_with_rifle", offset.parent(),
                "the shipped asset roots the retention bone under its arm hierarchy");
        assertNull(model.bone("constraint"), "the old bone name must not be used");
    }

    @Test
    void theShippedAnimationAnimatesAdsOffsetAndTheLibraryProvidesTheWalkingClips() throws Exception {
        ValidationReport animationReport = new ValidationReport();
        AnimationFile animations = BedrockAnimationParser.parse("arg_default_pack",
                new ResourceId("arg_pack", "animations/weapons/dmrs/m100_dmr/m100_dmr"), ANIMATIONS,
                readJson(ANIMATIONS).getAsJsonObject(), animationReport);

        assertNotNull(animations);
        assertFalse(animationReport.hasErrors());
        assertNotNull(animations.animation("anim.model.idle").bones().get("ads_offset"),
                "the idle clip keeps the retention encoding the reference implementation expects");
        assertNull(animations.animation("anim.model.idle").bones().get("constraint"));

        ValidationReport libraryReport = new ValidationReport();
        AnimationFile library = BedrockAnimationParser.parse("arg_default_pack",
                new ResourceId("arg_pack", "default_animations/weapons/pistols/walking_animations"),
                LIBRARY, readJson(LIBRARY).getAsJsonObject(), libraryReport);

        assertNotNull(library);
        assertFalse(libraryReport.hasErrors());
        for (String clip : List.of("anim.model.walk_forward", "anim.model.walk_aiming",
                "anim.model.walk_backward", "anim.model.walk_left", "anim.model.walk_right")) {
            assertNotNull(library.animation(clip), "the reusable library must provide " + clip);
        }
    }

    private static JsonElement readJson(String resource) throws Exception {
        try (InputStream stream = ShippedAimingConfigurationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(stream, "the shipped resource " + resource + " is not on the test classpath");
            return JsoncReader.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
