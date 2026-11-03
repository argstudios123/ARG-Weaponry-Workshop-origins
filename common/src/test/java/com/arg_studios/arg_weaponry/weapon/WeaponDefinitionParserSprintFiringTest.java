package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The optional {@code allow_firing_when_sprinting} property.
 *
 * <p>The compatibility contract is explicit: absent means {@code true}, malformed means {@code true} with a diagnostic,
 * and only a real JSON {@code false} disables sprint firing.</p>
 */
class WeaponDefinitionParserSprintFiringTest {

    @Test
    void theDocumentedValueIsRead() {
        assertFalse(read("{\"allow_firing_when_sprinting\": false}", new ValidationReport()));
        assertTrue(read("{\"allow_firing_when_sprinting\": true}", new ValidationReport()));
    }

    @Test
    void anAbsentPropertyKeepsTheLegacyBehaviour() {
        assertTrue(read("{}", new ValidationReport()), "existing packs must keep firing while sprinting");
    }

    @Test
    void aWronglyTypedValueFallsBackToTrueAndIsReported() {
        ValidationReport report = new ValidationReport();

        assertTrue(read("{\"allow_firing_when_sprinting\": \"no\"}", report),
                "a typo must not silently disable firing");
        assertFalse(report.diagnostics().isEmpty(), "the creator is told about the mistake");
    }

    private static boolean read(String json, ValidationReport report) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new WeaponDefinitionParser("arg_pack", "weapons/sprint_weapon.json",
                new ResourceId("arg_pack", "weapons/sprint_weapon"), report)
                .readAllowFiringWhenSprinting(root);
    }
}
