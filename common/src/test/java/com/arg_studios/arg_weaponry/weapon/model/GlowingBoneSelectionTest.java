package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The glowing-part hierarchy rules, resolved against a real parsed model.
 *
 * <p>{@code part_name} names a <em>parent</em> bone, and everything under it glows. These tests build a geometry document
 * with a cube-only bone, a bone that carries several cubes and a polymesh, a nested child, a grandchild and two further
 * independent sections, so the recursion, the geometry-type independence and the multiple-section case are all exercised
 * on data the geometry parser really produces.</p>
 */
class GlowingBoneSelectionTest {

    @Test
    void everyDescendantOfTheConfiguredParentGlows() {
        GlowingBoneSelection.Resolved resolved =
                GlowingBoneSelection.resolve(model(), List.of("glowing_part"));

        assertTrue(resolved.glows("glowing_part"), "the named parent itself glows");
        assertTrue(resolved.glows("child_bone"), "a direct child glows");
        assertTrue(resolved.glows("grandchild_bone"), "a grandchild glows: the walk is recursive");
        assertTrue(resolved.glows("another_child"), "a sibling branch below the parent glows");
        assertFalse(resolved.glows("weapon"), "the root above the parent does not glow");
        assertFalse(resolved.glows("non_glowing"), "an unrelated bone does not glow");
        assertTrue(resolved.missingParents().isEmpty());
        assertEquals(4, resolved.bones().size(),
                () -> "expected the parent and its three descendants: " + resolved.bones());
    }

    @Test
    void cubesAndMeshesAreBothCovered() {
        WeaponModel model = model();
        GlowingBoneSelection.Resolved resolved = GlowingBoneSelection.resolve(model, List.of("glowing_part"));

        // The parent carries two cubes and a polymesh; the child carries a cube and a polymesh as well. A bone stores one
        // mesh per geometry kind, so two cubes contribute a single cube mesh and the polymesh is its own mesh.
        assertEquals(2, model.bone("glowing_part").meshes().size(), "several cubes and a mesh live on one bone");
        assertEquals(2, model.bone("child_bone").meshes().size());
        boolean sawCube = false;
        boolean sawMesh = false;
        for (String bone : resolved.bones()) {
            for (BoneMesh mesh : model.bone(bone).meshes()) {
                sawCube |= mesh.source() == BoneMesh.Source.CUBE;
                sawMesh |= mesh.source() == BoneMesh.Source.POLY_MESH;
            }
        }
        assertTrue(sawCube, "cube geometry of a glowing hierarchy is part of the selection");
        assertTrue(sawMesh, "poly_mesh geometry of a glowing hierarchy is part of the selection");
    }

    @Test
    void independentSectionsAllGlowAtOnce() {
        GlowingBoneSelection.Resolved resolved = GlowingBoneSelection.resolve(model(),
                List.of("glowing_part", "glowing_part_2", "glowing_part_3"));

        assertTrue(resolved.glows("glowing_part"));
        assertTrue(resolved.glows("glowing_part_2"));
        assertTrue(resolved.glows("glowing_part_3"));
        assertTrue(resolved.glows("grandchild_bone"), "the descendants of the first section are still included");
        assertFalse(resolved.glows("non_glowing"));
        assertEquals(6, resolved.bones().size(),
                () -> "three sections plus their descendants: " + resolved.bones());
    }

    @Test
    void aMissingBoneIsReportedWithoutAffectingTheOthers() {
        GlowingBoneSelection.Resolved resolved =
                GlowingBoneSelection.resolve(model(), List.of("glowing_part", "missing_bone"));

        assertEquals(List.of("missing_bone"), resolved.missingParents());
        assertTrue(resolved.glows("glowing_part"), "the entries that do match still work");
        assertFalse(resolved.glows("missing_bone"), "a name the model does not contain is ignored");
    }

    @Test
    void duplicateAndNestedParentsAreNotVisitedTwice() {
        GlowingBoneSelection.Resolved duplicated =
                GlowingBoneSelection.resolve(model(), List.of("glowing_part", "glowing_part"));
        GlowingBoneSelection.Resolved nested =
                GlowingBoneSelection.resolve(model(), List.of("glowing_part", "child_bone"));

        assertEquals(4, duplicated.bones().size(), "a repeated entry adds nothing");
        assertEquals(4, nested.bones().size(), "a parent inside an already-glowing hierarchy adds nothing");
    }

    @Test
    void anEmptyConfigurationSelectsNothing() {
        assertTrue(GlowingBoneSelection.resolve(model(), List.of()).isEmpty());
        assertEquals(GlowingBoneSelection.NONE, GlowingBoneSelection.resolve(model(), List.of()));
        assertTrue(GlowingBoneSelection.resolve(null, List.of("glowing_part")).isEmpty());
        assertTrue(GlowingBoneSelection.resolve(model(), null).isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    /** @return the model the tests resolve against, parsed by the real geometry parser. */
    private static WeaponModel model() {
        JsonObject root = JsonParser.parseString(GEOMETRY.formatted(TRIANGLE, TRIANGLE, TRIANGLE)).getAsJsonObject();
        ValidationReport report = new ValidationReport();
        WeaponModel model = BedrockGeometryParser.parse("arg_pack", new ResourceId("arg_pack", "models/test"),
                "models/test.json", root, report);
        assertNotNull(model, () -> "the test model must parse: " + report.diagnostics());
        return model;
    }

    /**
     * A weapon-shaped model: a root, a glowing parent with two cubes and a mesh, a child with a cube and a mesh, a
     * grandchild, a sibling branch, an unrelated bone and two further independent glowing sections.
     */
    private static final String GEOMETRY = """
            {
              "format_version": "1.12.0",
              "minecraft:geometry": [
                {
                  "description": {
                    "identifier": "geometry.arg_glow_test",
                    "texture_width": 64,
                    "texture_height": 64
                  },
                  "bones": [
                    { "name": "weapon", "pivot": [0, 0, 0],
                      "cubes": [ { "origin": [0, 0, 0], "size": [1, 1, 1], "uv": [0, 0] } ] },
                    { "name": "glowing_part", "parent": "weapon", "pivot": [0, 0, 0],
                      "cubes": [
                        { "origin": [1, 0, 0], "size": [1, 1, 1], "uv": [0, 0] },
                        { "origin": [2, 0, 0], "size": [1, 1, 1], "uv": [0, 0] }
                      ],
                      "poly_mesh": %s },
                    { "name": "child_bone", "parent": "glowing_part", "pivot": [0, 0, 0],
                      "cubes": [ { "origin": [0, 1, 0], "size": [1, 1, 1], "uv": [0, 0] } ],
                      "poly_mesh": %s },
                    { "name": "grandchild_bone", "parent": "child_bone", "pivot": [0, 0, 0],
                      "cubes": [ { "origin": [0, 2, 0], "size": [1, 1, 1], "uv": [0, 0] } ] },
                    { "name": "another_child", "parent": "glowing_part", "pivot": [0, 0, 0],
                      "cubes": [ { "origin": [0, 0, 1], "size": [1, 1, 1], "uv": [0, 0] } ] },
                    { "name": "non_glowing", "parent": "weapon", "pivot": [0, 0, 0],
                      "cubes": [ { "origin": [3, 0, 0], "size": [1, 1, 1], "uv": [0, 0] } ] },
                    { "name": "glowing_part_2", "parent": "weapon", "pivot": [0, 0, 0],
                      "poly_mesh": %s },
                    { "name": "glowing_part_3", "parent": "weapon", "pivot": [0, 0, 0],
                      "cubes": [ { "origin": [4, 0, 0], "size": [1, 1, 1], "uv": [0, 0] } ] }
                  ]
                }
              ]
            }
            """;

    /** One normalized triangle, the smallest polymesh the geometry parser accepts. */
    private static final String TRIANGLE = """
            {
              "normalized_uvs": true,
              "positions": [ [0, 0, 0], [1, 0, 0], [0, 1, 0] ],
              "normals": [ [0, 0, 1], [0, 0, 1], [0, 0, 1] ],
              "uvs": [ [0.0, 0.0], [1.0, 0.0], [0.0, 1.0] ],
              "polys": [ [ [0, 0, 0], [1, 1, 1], [2, 2, 2] ] ]
            }
            """;
}
