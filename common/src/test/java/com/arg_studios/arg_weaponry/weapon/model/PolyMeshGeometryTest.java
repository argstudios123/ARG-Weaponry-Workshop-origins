package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The {@code poly_mesh} geometry layer, and the way it shares a bone with cube geometry.
 *
 * <p>The cube path has its own regression suite ({@link BedrockCubeGeometryTest}, {@link BedrockCubeUvMappingTest},
 * {@link ShippedCubeGeometryTest}) and the glowing selection already covers "a bone may hold both kinds"
 * ({@link GlowingBoneSelectionTest}). What is new here is only what those do not answer: that one bone can carry
 * <em>several</em> poly meshes, that an n-gon becomes the right triangles wound against the normal it is shaded with, and
 * that poly-mesh UVs are flipped once for their bottom-left origin while cube UVs are not.</p>
 */
class PolyMeshGeometryTest {

    @Test
    void severalPolyMeshesAndCubesShareOneBone() {
        WeaponModel model = parse(GEOMETRY.formatted(QUAD, TRIANGLE, CUBE_BONE_UVS));
        ModelBone bone = model.bone("hybrid");
        assertNotNull(bone, "the hybrid bone must survive parsing");

        // Declaration order, and both geometry systems on the same bone: the two poly meshes first, then the cubes.
        List<BoneMesh> meshes = bone.meshes();
        assertEquals(3, meshes.size(), "two poly meshes and one cube array live on one bone");
        assertSame(BoneMesh.Source.POLY_MESH, meshes.get(0).source());
        assertSame(BoneMesh.Source.POLY_MESH, meshes.get(1).source());
        assertSame(BoneMesh.Source.CUBE, meshes.get(2).source());

        // Each block keeps its own polygon count, and the bone's triangle count is the sum of all three.
        assertEquals(1, meshes.get(0).polygonCount(), "the quad is one polygon");
        assertEquals(1, meshes.get(1).polygonCount(), "the triangle is one polygon");
        assertEquals(6, meshes.get(2).polygonCount(), "the cube contributes its six faces");
        assertEquals(2 + 1 + 12, bone.triangleCount(), "two triangles, one triangle, twelve cube triangles");

        // The model totals are the model's, not one block's: it counts every source polygon, cube faces included.
        assertEquals(8, model.polygonCount(), "two poly meshes and six cube faces are eight polygons");
        assertEquals(1, model.cubeCount(), "the cube is counted as a cube");
    }

    @Test
    void anNGonIsTriangulatedAndWoundAgainstItsNormal() {
        WeaponModel model = parse(GEOMETRY.formatted(PENTAGON, TRIANGLE, CUBE_BONE_UVS));
        BoneMesh mesh = model.bone("hybrid").meshes().get(0);

        assertEquals(1, mesh.polygonCount(), "the pentagon is one polygon");
        assertEquals(3, mesh.triangleCount(), "a five-corner polygon fans into three triangles");

        float[] positions = mesh.positionsUnsafe();
        float[] normals = mesh.normalsUnsafe();
        int[] indices = mesh.indicesUnsafe();
        for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
            int a = indices[triangle * 3];
            int b = indices[triangle * 3 + 1];
            int c = indices[triangle * 3 + 2];
            Vec3 cross = cross(position(positions, a), position(positions, b), position(positions, c));
            Vec3 normal = new Vec3(normals[a * 3], normals[a * 3 + 1], normals[a * 3 + 2]);
            assertTrue(dot(cross, normal) > 0.0F,
                    "triangle " + triangle + " must be wound to agree with the normal it is shaded with");
        }
    }

    @Test
    void polyMeshUvsAreFlippedOnceForTheirBottomLeftOrigin() {
        WeaponModel model = parse(GEOMETRY.formatted(QUAD, TRIANGLE, CUBE_BONE_UVS));
        BoneMesh mesh = model.bone("hybrid").meshes().get(0);

        // Authored as (0.25, 0.25) .. (0.5, 0.5) in the poly mesh's bottom-left space, which is the top-left quarter of
        // the texture once flipped: v becomes 1 - v exactly once and the corner order stays the authored one.
        float[] uvs = mesh.uvsUnsafe();
        assertEquals(0.25F, uvs[0], 1.0E-5F);
        assertEquals(0.75F, uvs[1], 1.0E-5F);
        assertEquals(0.5F, uvs[2], 1.0E-5F);
        assertEquals(0.75F, uvs[3], 1.0E-5F);
        assertEquals(0.5F, uvs[4], 1.0E-5F);
        assertEquals(0.5F, uvs[5], 1.0E-5F);
        assertEquals(0.25F, uvs[6], 1.0E-5F);
        assertEquals(0.5F, uvs[7], 1.0E-5F);
    }

    // ---------------------------------------------------------------- fixtures

    private static WeaponModel parse(String document) {
        JsonObject root = JsonParser.parseString(document).getAsJsonObject();
        ValidationReport report = new ValidationReport();
        WeaponModel model = BedrockGeometryParser.parse("arg_pack", new ResourceId("arg_pack", "models/test"),
                "models/test.json", root, report);
        assertNotNull(model, () -> "the test model must parse: " + report.diagnostics());
        assertTrue(report.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.severity() == PackDiagnostic.Severity.ERROR),
                "the test model must parse without errors: " + report.diagnostics());
        return model;
    }

    /** One bone that carries two poly meshes (the array form) and a cube array. */
    private static final String GEOMETRY = """
            {
              "format_version": "1.12.0",
              "minecraft:geometry": [
                {
                  "description": {
                    "identifier": "geometry.arg_polymesh_test",
                    "texture_width": 16,
                    "texture_height": 16
                  },
                  "bones": [
                    { "name": "hybrid", "pivot": [0, 0, 0],
                      "poly_mesh": [ %s, %s ],
                      "cubes": [ { "origin": [0, 0, 0], "size": [1, 1, 1], "uv": %s } ] }
                  ]
                }
              ]
            }
            """;

    /** A quad authored in normalised, bottom-left UV space. */
    private static final String QUAD = """
            {
              "normalized_uvs": true,
              "positions": [ [0, 0, 0], [2, 0, 0], [2, 2, 0], [0, 2, 0] ],
              "normals": [ [0, 0, 1], [0, 0, 1], [0, 0, 1], [0, 0, 1] ],
              "uvs": [ [0.25, 0.25], [0.5, 0.25], [0.5, 0.5], [0.25, 0.5] ],
              "polys": [ [ [0, 0, 0], [1, 1, 1], [2, 2, 2], [3, 3, 3] ] ]
            }
            """;

    /** The smallest poly mesh the geometry layer accepts. */
    private static final String TRIANGLE = """
            {
              "normalized_uvs": true,
              "positions": [ [0, 0, 1], [1, 0, 1], [0, 1, 1] ],
              "normals": [ [0, 0, 1], [0, 0, 1], [0, 0, 1] ],
              "uvs": [ [0.0, 0.0], [1.0, 0.0], [0.0, 1.0] ],
              "polys": [ [ [0, 0, 0], [1, 1, 1], [2, 2, 2] ] ]
            }
            """;

    /** A convex pentagon, so the fan has to produce three triangles rather than two. */
    private static final String PENTAGON = """
            {
              "normalized_uvs": true,
              "positions": [ [0, 0, 0], [2, 0, 0], [2, 2, 0], [1, 3, 0], [0, 2, 0] ],
              "normals": [ [0, 0, 1], [0, 0, 1], [0, 0, 1], [0, 0, 1], [0, 0, 1] ],
              "uvs": [ [0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.5, 1.5], [0.0, 1.0] ],
              "polys": [ [ [0, 0, 0], [1, 1, 1], [2, 2, 2], [3, 3, 3], [4, 4, 4] ] ]
            }
            """;

    /** A box-mapped UV, so the cube block of the hybrid bone needs no per-face objects. */
    private static final String CUBE_BONE_UVS = "[0, 0]";

    // ---------------------------------------------------------------- vector helpers

    private static Vec3 position(float[] positions, int corner) {
        return new Vec3(positions[corner * 3], positions[corner * 3 + 1], positions[corner * 3 + 2]);
    }

    private static Vec3 cross(Vec3 a, Vec3 b, Vec3 c) {
        Vec3 ab = new Vec3(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
        Vec3 ac = new Vec3(c.x() - a.x(), c.y() - a.y(), c.z() - a.z());
        return new Vec3(ab.y() * ac.z() - ab.z() * ac.y(),
                ab.z() * ac.x() - ab.x() * ac.z(),
                ab.x() * ac.y() - ab.y() * ac.x());
    }

    private static float dot(Vec3 first, Vec3 second) {
        return first.x() * second.x() + first.y() * second.y() + first.z() * second.z();
    }
}
