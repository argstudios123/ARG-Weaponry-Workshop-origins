package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.weapon.model.BedrockGeometryParser;
import com.arg_studios.arg_weaponry.weapon.model.GlowingBoneSelection;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The shipped pack's {@code glowing_weapon_parts} against the shipped geometry.
 *
 * <p>This is the end-to-end check of the feature without a game: the definition's declarations are read exactly as the
 * loader reads them, the model is parsed exactly as the renderer parses it, and the two are resolved exactly as the
 * render cache resolves them. It therefore pins both directions of the contract - that the parent bone the pack declares
 * really glows with its whole hierarchy, and that a declared name the model does not contain is reported instead of being
 * silently invented as a glowing bone.</p>
 */
class ShippedGlowingPartsConfigurationTest {

    private static final String PACK = "assets/arg_weaponry/weapon_packs/arg_default_pack/assets/arg_pack/";
    private static final String WEAPON = PACK + "weapons_registry/weapons/dmrs/m100_dmr.json";
    private static final String MODEL = PACK + "models/weapons/dmrs/m100_dmr/m100_dmr.json";

    @Test
    void theShippedDeclarationsAreReadWithPartNameAndResolveAgainstTheModel() {
        ValidationReport definitionReport = new ValidationReport();
        List<WeaponDefinition.GlowingPart> parts = readGlowingParts(definitionReport);

        assertEquals(List.of("glowing_part"), names(parts),
                "the shipped file uses 'part_name', which is what the reader must accept");
        assertFalse(definitionReport.hasErrors(), "a valid glowing-part block never rejects the weapon");

        GlowingBoneSelection.Resolved resolved = GlowingBoneSelection.resolve(readModel(), names(parts));

        assertTrue(resolved.glows("glowing_part"), "the declared parent bone glows");
        assertTrue(resolved.glows("bone"), "and so does the child bone below it, which the file also authors");
        assertFalse(resolved.glows("main"), "the rest of the weapon is untouched");
        assertTrue(resolved.missingParents().isEmpty(),
                () -> "every shipped declaration resolves: " + resolved.missingParents());
    }

    @Test
    void theResolvedHierarchyCoversTheGeometryOfEveryBoneItContains() {
        WeaponModel model = readModel();
        GlowingBoneSelection.Resolved resolved =
                GlowingBoneSelection.resolve(model, names(readGlowingParts(new ValidationReport())));

        int meshes = 0;
        for (String bone : resolved.bones()) {
            assertNotNull(model.bone(bone), "a resolved bone is always a bone of the model");
            meshes += model.bone(bone).meshes().size();
        }
        assertTrue(meshes > 0, "the glowing part of the shipped weapon is real geometry, not an empty anchor");
        assertTrue(!model.bone("bone").meshes().isEmpty() && resolved.glows("bone"),
                "the geometry of the nested child bone is part of the glowing selection, which is what the recursion is for");
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> names(List<WeaponDefinition.GlowingPart> parts) {
        List<String> names = new ArrayList<>(parts.size());
        for (WeaponDefinition.GlowingPart part : parts) {
            names.add(part.name());
        }
        return names;
    }

    private static List<WeaponDefinition.GlowingPart> readGlowingParts(ValidationReport report) {
        JsonObject weapon = readJson(WEAPON).getAsJsonObject();
        // The reader is exercised directly: a whole-document parse also builds the firing modes, which need game classes.
        return new WeaponDefinitionParser("arg_default_pack", WEAPON,
                new ResourceId("arg_pack", "weapons/dmrs/m100_dmr"), report).readGlowingParts(weapon);
    }

    private static WeaponModel readModel() {
        ValidationReport report = new ValidationReport();
        WeaponModel model = BedrockGeometryParser.parse("arg_default_pack",
                new ResourceId("arg_pack", "weapons/dmrs/m100_dmr/m100_dmr"), MODEL,
                readJson(MODEL).getAsJsonObject(), report);
        assertNotNull(model, () -> "the shipped model must parse: " + report.diagnostics());
        return model;
    }

    private static JsonElement readJson(String resource) {
        try (InputStream stream = ShippedGlowingPartsConfigurationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(stream, "the shipped resource " + resource + " is not on the test classpath");
            return JsoncReader.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException | JsoncReader.JsoncException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }
}
