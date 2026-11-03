package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The per-weapon {@code aiming} block: every documented property, the deprecated {@code aimimg_zoom} spelling, the easing
 * vocabulary and the speed sanitising.
 */
class WeaponDefinitionParserAimingTest {

    private static final String FULL = """
            {
              "aiming": {
                "enabled": true,
                "aiming_zoom": 0.3,
                "aiming_zoom_type": "ease_out",
                "aiming_speed": 0.3,
                "aiming_animation_type": "smooth",
                "aiming_zoom_speed": 1.2,
                "no_aiming_on_inspect": true,
                "no_aiming_on_reload": true,
                "aim_in": "anim.model.aim_in",
                "aim_out": "anim.model.aim_out",
                "ads_constraint": {
                  "enabled": true,
                  "camera": true,
                  "camera_constraint_modifier": 0.7
                },
                "ads_reload_zoom_control": {
                  "enabled": true,
                  "zoom_effect_type": "smooth",
                  "zoom_amount": 0.06
                }
              }
            }
            """;

    @Test
    void aCompleteBlockIsReadAsWritten() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Aiming aiming = read(FULL, report);

        assertTrue(report.diagnostics().isEmpty(), "a valid aiming block must not produce a finding");
        assertTrue(aiming.enabled());
        assertTrue(aiming.noAimingOnInspect());
        assertTrue(aiming.noAimingOnReload());
        assertEquals(0.3, aiming.zoom());
        assertEquals(ZoomCurve.EASE_OUT_QUAD, aiming.zoomCurve());
        assertEquals(ZoomCurve.SMOOTH, aiming.animationCurve());
        assertEquals(0.3, aiming.animationSpeed());
        assertEquals(1.2, aiming.zoomSpeed());
        assertEquals("anim.model.aim_in", aiming.aimIn());
        assertEquals("anim.model.aim_out", aiming.aimOut());
        assertTrue(aiming.hasAimIn());
        assertTrue(aiming.hasAimOut());
        assertTrue(aiming.adsConstraint().enabled());
        assertTrue(aiming.adsConstraint().camera());
        assertTrue(aiming.adsConstraint().hasCameraConstraint());
        assertEquals(0.7, aiming.adsConstraint().cameraConstraintModifier());
        assertTrue(aiming.adsReloadZoom().enabled());
        assertEquals("smooth", aiming.adsReloadZoom().zoomEffectType());
        assertEquals(0.06, aiming.adsReloadZoom().zoomAmount());
        assertEquals(ZoomCurve.SMOOTH, aiming.adsReloadZoom().curve());
    }

    @Test
    void theAdsBlocksDefaultToDisabledAndTheCameraDefaultsToOn() {
        WeaponDefinition.Aiming aiming = read("""
                {"aiming": {"enabled": true, "ads_constraint": {"enabled": true}}}
                """, new ValidationReport());

        assertTrue(aiming.adsConstraint().enabled());
        assertTrue(aiming.adsConstraint().camera(), "the reference implementation defaults camera correction on");
        assertFalse(aiming.adsConstraint().hasCameraConstraint(), "no modifier means the ads_offset bone is used");
        assertFalse(aiming.adsReloadZoom().enabled());
        assertEquals(0.0, aiming.adsReloadZoom().zoomAmount());
    }

    @Test
    void theCameraConstraintModifierIsARetentionFractionAndIsClamped() {
        WeaponDefinition.AdsConstraint low = new WeaponDefinition.AdsConstraint(true, true, -0.5);
        WeaponDefinition.AdsConstraint high = new WeaponDefinition.AdsConstraint(true, true, 1.5);

        assertEquals(0.0, low.cameraConstraintModifier(), "a negative retention becomes full constraint");
        assertEquals(1.0, high.cameraConstraintModifier(), "a retention above one becomes no constraint");
    }

    @Test
    void theReloadZoomAmountIsAFractionAndUnknownEasingFallsBackToSmooth() {
        ValidationReport report = new ValidationReport();
        WeaponDefinition.Aiming aiming = read("""
                {"aiming": {"enabled": true,
                  "ads_reload_zoom_control": {
                    "enabled": true, "zoom_effect_type": "wobble", "zoom_amount": 2.5
                  }}}
                """, report);

        assertFalse(report.diagnostics().isEmpty(), "both problems are reported");
        assertEquals(1.0, aiming.adsReloadZoom().zoomAmount());
        assertEquals(ZoomCurve.SMOOTH, aiming.adsReloadZoom().curve());
        assertTrue(aiming.adsReloadZoom().hasEffect());
    }

    @Test
    void everyDocumentedReloadZoomEasingResolves() {
        for (String name : new String[]{"linear", "smooth", "ease_in", "ease_out", "ease_in_out", "sine_in",
                "sine_out", "sine_in_out"}) {
            WeaponDefinition.AdsReloadZoom config = read("""
                    {"aiming": {"enabled": true, "ads_reload_zoom_control": {
                      "enabled": true, "zoom_effect_type": "%s", "zoom_amount": 0.1
                    }}}
                    """.formatted(name), new ValidationReport()).adsReloadZoom();

            assertTrue(config.curve() != ZoomCurve.NONE, "the easing '" + name + "' must resolve");
        }
    }

    @Test
    void anAbsentBlockMeansAimingIsNotConfigured() {
        assertNull(read("{}", new ValidationReport()), "a weapon without the block keeps its previous behaviour");
    }

    @Test
    void theDeprecatedMisspellingStillWorksAndIsReported() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Aiming aiming = read("""
                {"aiming": {"enabled": true, "aimimg_zoom": 0.5}}
                """, report);

        assertEquals(0.5, aiming.zoom(), "an existing pack keeps its zoom after the spelling was corrected");
        assertFalse(report.diagnostics().isEmpty(), "and the creator is told about the deprecated property");
    }

    @Test
    void theCorrectedSpellingWinsWhenBothAreDeclared() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Aiming aiming = read("""
                {"aiming": {"enabled": true, "aiming_zoom": 0.4, "aimimg_zoom": 0.9}}
                """, report);

        assertEquals(0.4, aiming.zoom(), "the documented property is authoritative");
        assertFalse(report.diagnostics().isEmpty(), "the duplicate is reported instead of being silently resolved");
    }

    @Test
    void everyDocumentedEasingNameResolves() {
        for (String name : new String[]{"linear", "smooth", "ease_in", "ease_out", "ease_in_out", "sine_in",
                "sine_out", "sine_in_out"}) {
            assertTrue(ZoomCurve.lookup(name) != null, "the documented easing '" + name + "' must resolve");
        }
    }

    @Test
    void anUnknownEasingNameFallsBackToLinearAndIsReported() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Aiming aiming = read("""
                {"aiming": {"enabled": true, "aiming_animation_type": "wobble"}}
                """, report);

        assertEquals(ZoomCurve.LINEAR, aiming.animationCurve(), "a typo cannot disable aiming");
        assertFalse(report.diagnostics().isEmpty());
    }

    @Test
    void aNonsensicalSpeedIsReplacedByTheNeutralMultiplier() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Aiming aiming = read("""
                {"aiming": {"enabled": true, "aiming_speed": 0.0, "aiming_zoom_speed": -2.0}}
                """, report);

        assertEquals(WeaponDefinition.Aiming.DEFAULT_SPEED, aiming.animationSpeed());
        assertEquals(WeaponDefinition.Aiming.DEFAULT_SPEED, aiming.zoomSpeed());
        assertFalse(report.diagnostics().isEmpty());
    }

    @Test
    void anExtremeSpeedIsClampedInsteadOfProducingASnap() {
        WeaponDefinition.Aiming aiming = read("""
                {"aiming": {"enabled": true, "aiming_speed": 500.0}}
                """, new ValidationReport());

        assertEquals(WeaponDefinition.Aiming.MAX_SPEED, aiming.animationSpeed());
    }

    @Test
    void anUnknownPropertyIsReportedButTheBlockStillParses() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Aiming aiming = read("""
                {"aiming": {"enabled": true, "aiming_zoom": 0.7, "aimimg_zoom_speed": 9.0}}
                """, report);

        assertEquals(0.7, aiming.zoom());
        assertFalse(report.diagnostics().isEmpty(), "a typo in a property name is visible");
    }

    @Test
    void blankClipIdentifiersMeanTheWeaponDoesNotAuthorThem() {
        WeaponDefinition.Aiming aiming = read("""
                {"aiming": {"enabled": true, "aim_in": "  ", "aim_out": ""}}
                """, new ValidationReport());

        assertNull(aiming.aimIn());
        assertNull(aiming.aimOut());
        assertFalse(aiming.hasAimIn());
        assertFalse(aiming.hasAimOut());
    }

    private static WeaponDefinition.Aiming read(String json, ValidationReport report) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new WeaponDefinitionParser("arg_pack", "weapons/aiming_weapon.json",
                new ResourceId("arg_pack", "weapons/aiming_weapon"), report).readAiming(root);
    }
}
