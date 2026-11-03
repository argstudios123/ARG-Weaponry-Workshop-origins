package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponSwayStyle;
import com.google.gson.JsonObject;

/**
 * The shipped weapon's {@code weapons_sway} block, read through the real parser.
 *
 * <p>This is the compatibility half of the expanded sway configuration: the block in the content pack uses the documented
 * names ({@code weapon_sway_type}, {@code sway_sensitivity}, {@code aim_multiplier} above {@code 1}) and must parse without
 * a single diagnostic, so an existing pack keeps working and a future edit of the shipped file is covered by the test.</p>
 */
class ShippedSwayConfigurationTest {

    private static final String WEAPON =
            "assets/arg_weaponry/weapon_packs/arg_default_pack/assets/arg_pack/weapons_registry/weapons/dmrs/m100_dmr.json";

    @Test
    void theShippedSwayBlockIsReadExactlyAsWritten() throws Exception {
        JsonObject root = root();
        ValidationReport report = new ValidationReport();
        WeaponDefinitionParser parser = new WeaponDefinitionParser("arg_default_pack", WEAPON,
                new ResourceId("arg_pack", "dmrs/m100_dmr"), report);

        WeaponDefinition.Sway sway = parser.readSway(root);

        assertEquals(WeaponSwayStyle.TYPE_1, sway.style());
        assertTrue(sway.enabled());
        assertEquals(0.2, sway.intensity(), 1.0E-9);
        assertEquals(3.0, sway.smoothing(), 1.0E-9);
        assertEquals(3.0, sway.sensitivity(), 1.0E-9);
        assertEquals(6.0, sway.returnSpeed(), 1.0E-9);
        assertEquals(0.25, sway.aimMultiplier(), 1.0E-9, "the shipped aim multiplier is read exactly");
        assertTrue(report.diagnostics().isEmpty(), "the shipped block must parse without a single diagnostic");
    }

    private static JsonObject root() throws Exception {
        try (InputStream stream = ShippedSwayConfigurationTest.class.getClassLoader().getResourceAsStream(WEAPON)) {
            assertNotNull(stream, "the shipped weapon definition must be on the test classpath");
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return JsoncReader.parse(text).getAsJsonObject();
        }
    }
}
