package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The cube path of {@link BedrockGeometryParser}, checked cube by cube on synthetic geometry.
 *
 * <p>The shipped content only exercises part of the cube format: its inflation values are a few thousandths and no bone
 * declares a bone-level inflation, so the interesting interactions have to be built here. Every scenario the cube
 * renderer has to survive has its own test: a plain cube, positive and negative inflation, a rotation about a custom
 * pivot, inflation combined with rotation, a cube far from the geometry origin, a cube inside a rotated bone, several
 * independently transformed cubes in one bone, and all of those at once.</p>
 *
 * <h2>What the expected side is</h2>
 * <p>Nothing here reuses the parser's rotation arithmetic. The expected corners and normals come from the authoring
 * tool's own rules through {@link BlockbenchTransformReference}: a Bedrock file is displayed mirrored in X with its
 * rotations shown as {@code (-x, -y, z)}, a cube is one {@code Rz * Ry * Rx} matrix about its pivot, and the model
 * space this renderer draws in is the mirror of that displayed space. Because the reference is independent, a wrong
 * sign, a wrong composition order, a missing conversion or a transformation applied twice cannot pass.</p>
 *
 * <h2>What every test also asserts</h2>
 * <ul>
 *     <li>each face is one flat-shaded quad: all of its corners carry the same normal, and that normal is unit
 *     length;</li>
 *     <li>the emitted triangle winding agrees with that normal, so the surface faces the way it is lit;</li>
 *     <li>every pair of corners of a quad differs along a direction perpendicular to the normal, which a rigid rotation
 *     must preserve;</li>
 *     <li>the corners of the bone's mesh are exactly the authored corners - every one present, nothing extra.</li>
 * </ul>
 */
class BedrockCubeGeometryTest {

    /** Tolerance in model units for a corner or UV comparison. */
    private static final float TOLERANCE = 1.0E-3F;

    /** Declared texture size of every synthetic geometry, so UVs are easy to reason about. */
    private static final int TEXTURE_SIZE = 64;

    private static final List<String> FACES = List.of("north", "east", "south", "west", "up", "down");


    // ---------------------------------------------------------------- 1. plain cube

    @Test
    void aPlainCubeEmitsEveryFaceWhereItWasAuthored() {
        Authored cube = new Authored(new Vec3(2.0F, -3.0F, 4.0F), new Vec3(3.0F, 5.0F, 7.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);
        Parsed parsed = parse(geometry(bone("body", null, null, null, null, cube("box", cube, allFaces()))));

        assertTrue(!parsed.report().hasErrors(), parsed.describe());
        assertEquals(1, parsed.model().cubeCount(), "the bone holds one cube");
        assertEquals(6, parsed.model().polygonCount(), "one polygon per face");
        assertEquals(12, parsed.triangles(), "two triangles per face");
        assertCube(parsed.mesh(), cube);
    }

    // ---------------------------------------------------------------- 2. positive inflation

    @Test
    void positiveInflationGrowsTheCubeOnEverySideWithoutMovingIt() {
        Authored plain = new Authored(new Vec3(1.0F, 2.0F, 3.0F), new Vec3(2.0F, 2.0F, 2.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);
        Authored inflated = plain.withInflate(0.5F);
        Parsed parsed = parse(geometry(bone("body", null, null, null, null, cube("box", inflated, allFaces()))));

        assertCube(parsed.mesh(), inflated);

        Bounds bounds = bounds(parsed.mesh());
        assertVec(plain.centre(), bounds.centre(), "an inflated cube keeps its centre");
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(plain.extent(axis) + 1.0F, bounds.extent(axis), 1.0E-4F,
                    "axis " + axis + " must grow by twice the inflation");
        }
    }

    // ---------------------------------------------------------------- 3. negative inflation

    @Test
    void negativeInflationShrinksTheCubeOnEverySideWithoutMovingIt() {
        Authored plain = new Authored(new Vec3(-1.5F, 0.0F, -2.0F), new Vec3(4.0F, 2.0F, 3.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);
        Authored deflated = plain.withInflate(-0.25F);
        Parsed parsed = parse(geometry(bone("body", null, null, null, null, cube("box", deflated, allFaces()))));

        assertCube(parsed.mesh(), deflated);

        Bounds bounds = bounds(parsed.mesh());
        assertVec(plain.centre(), bounds.centre(), "a deflated cube keeps its centre");
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(plain.extent(axis) - 0.5F, bounds.extent(axis), 1.0E-4F,
                    "axis " + axis + " must shrink by twice the deflation");
        }
    }

    @Test
    void inflationAlsoWorksOnACubeWithAZeroLengthSide() {
        // A flat plate (a decal, a rail) has no thickness to scale, so inflate has to act on the extents: the plate has to
        // gain thickness instead of staying flat.
        Authored plate = new Authored(new Vec3(0.0F, 4.0F, 0.0F), new Vec3(8.0F, 0.0F, 3.0F), 0.125F, Vec3.ZERO,
                Vec3.ZERO);
        Parsed parsed = parse(geometry(bone("body", null, null, null, null, cube("plate", plate, allFaces()))));

        assertEquals(0.25F, bounds(parsed.mesh()).extent(1), 1.0E-4F, "the flat side gains 2x inflate of thickness");
        assertCube(parsed.mesh(), plate);
    }

    @Test
    void anOverDeflatedCubeCollapsesToAPlaneInsteadOfTurningInsideOut() {
        // Half of the thinnest side is the smallest box a uniform deflation can produce. Beyond it the extents would swap,
        // which keeps the shading normals but moves every face to the opposite side: an inside-out cube lit from behind.
        // The parser clamps at zero thickness instead, and reports it.
        Authored collapsing = new Authored(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(4.0F, 2.0F, 2.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO).withInflate(-5.0F);
        Authored survivor = new Authored(new Vec3(20.0F, 0.0F, 0.0F), new Vec3(1.0F, 1.0F, 1.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);
        Parsed parsed = parse(geometry(bone("body", null, null, null, null,
                cube("collapsing", collapsing, allFaces()),
                cube("survivor", survivor, allFaces()))));

        assertNotNull(parsed.model(), parsed.describe());
        assertTrue(parsed.hasDiagnostic(PackDiagnostic.Severity.WARNING, "clamped"),
                "the clamp has to be reported: " + parsed.describe());

        // The clamped box is [1,3] x [1,1] x [1,1] of the original [0,4] x [0,2] x [0,2]: a flat plate centred where
        // the cube was, and not a box whose faces sit on the opposite side.
        Bounds bounds = boundsOf(parsed.mesh(), collapsing.withInflate(-1.0F));
        assertEquals(2.0F, bounds.extent(0), 1.0E-4F, "the wide side still shrinks by the clamped amount");
        assertEquals(0.0F, bounds.extent(1), 1.0E-4F, "the thinnest side collapses to zero thickness");
        assertEquals(0.0F, bounds.extent(2), 1.0E-4F, "the thinnest side collapses to zero thickness");
        assertEquals(1.0F, bounds.minY(), 1.0E-4F, "the collapsed axis stays at the cube's centre");
        assertEquals(1.0F, bounds.minZ(), 1.0E-4F, "the collapsed axis stays at the cube's centre");

        assertCube(parsed.mesh(), survivor);
    }

    // ---------------------------------------------------------------- 4. rotation about a custom pivot

    @Test
    void aRotatedCubeTurnsAboutItsOwnCustomPivot() {
        // The pivot is deliberately nowhere near the centre: an implementation that rotates about the centre, about the
        // origin or about the bone pivot lands tens of units away from where the authoring tool puts the cube.
        Authored cube = new Authored(new Vec3(-2.0F, 1.0F, 6.0F), new Vec3(3.0F, 2.0F, 8.0F), 0.0F,
                new Vec3(25.0F, -40.0F, 15.0F), new Vec3(0.0F, 4.5F, 0.0F));
        Parsed parsed = parse(geometry(bone("body", null, null, null, null, cube("box", cube, allFaces()))));

        assertCube(parsed.mesh(), cube);

        // Now the pivot is a corner of the box: the pivot is the fixed point of the rotation, so that corner must not move
        // at all, which is the sharpest statement of "the rotation happens about the authored point".
        Authored atPivot = new Authored(cube.pivot(), new Vec3(1.0F, 1.0F, 1.0F), 0.0F, cube.rotation(),
                cube.pivot());
        Parsed pivotCube = parse(geometry(bone("body", null, null, null, null,
                cube("around_pivot", atPivot, allFaces()))));
        assertCube(pivotCube.mesh(), atPivot);
        assertCorner(pivotCube.mesh(), cube.pivot(), null, "a corner placed on the pivot cannot move");
    }

    // ---------------------------------------------------------------- 5./6. inflation with rotation

    @Test
    void anInflatedRotatedCubeMatchesTheAuthoringTransform() {
        Authored cube = new Authored(new Vec3(4.0F, -1.0F, 2.0F), new Vec3(2.0F, 3.0F, 1.0F), 0.4F,
                new Vec3(-30.0F, 20.0F, 45.0F), new Vec3(3.0F, 0.0F, 2.0F));
        Parsed parsed = parse(geometry(bone("body", null, null, null, null, cube("box", cube, allFaces()))));

        assertCube(parsed.mesh(), cube);
    }

    @Test
    void aDeflatedRotatedCubeMatchesTheAuthoringTransform() {
        Authored cube = new Authored(new Vec3(-6.0F, 2.5F, -4.0F), new Vec3(4.0F, 4.0F, 4.0F), -0.6F,
                new Vec3(12.0F, 75.0F, -18.0F), new Vec3(-6.0F, 4.5F, -4.0F));
        Parsed parsed = parse(geometry(bone("body", null, null, null, null, cube("box", cube, allFaces()))));

        assertCube(parsed.mesh(), cube);
    }

    // ---------------------------------------------------------------- 7. far from the origin

    @Test
    void aCubeFarFromTheGeometryOriginKeepsItsAuthoredPosition() {
        Authored cube = new Authored(new Vec3(37.5F, -12.25F, 84.0F), new Vec3(1.5F, 3.0F, 2.5F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);
        Parsed parsed = parse(geometry(bone("far", null, new float[]{40.0F, -8.0F, 90.0F}, null, null,
                cube("box", cube, allFaces()))));

        assertCube(parsed.mesh(), cube);
        // A bone pivot must not move a cube: the bone is applied by the renderer, not baked into the vertices.
        assertVec(cube.centre(), bounds(parsed.mesh()).centre(), "the bone pivot is not baked into the vertices");
    }


    // ---------------------------------------------------------------- 8. inside a rotated bone

    @Test
    void aCubeInARotatedBoneIsBakedOnceAndFollowsTheHierarchy() {
        // root -> arm, each with an authored pivot and rotation, and the cube carries its own inflation and rotation. The
        // parser must leave the bone transforms out of the vertices (the renderer applies them, so baking them would apply
        // them twice) and the renderer's chain must then match the authoring tool's chain.
        Authored cube = new Authored(new Vec3(1.0F, -2.0F, 0.5F), new Vec3(2.0F, 4.0F, 1.0F), 0.25F,
                new Vec3(0.0F, 0.0F, 35.0F), new Vec3(1.0F, 0.0F, 0.5F));
        float[] rootPivot = {0.0F, 6.0F, 0.0F};
        float[] rootRotation = {12.0F, -20.0F, 6.0F};
        float[] armPivot = {-2.0F, 5.0F, 0.0F};
        float[] armRotation = {-87.5F, 5.0F, -179.8F};

        Parsed parsed = parse(geometry(
                bone("root", null, rootPivot, rootRotation, null),
                bone("arm", "root", armPivot, armRotation, null, cube("box", cube, allFaces()))));

        assertCube(parsed.mesh(), cube);

        List<ModelBone> chain = parsed.model().bonesInOrder();
        assertEquals(2, chain.size(), "the hierarchy must be parsed as a chain");

        for (Vec3 corner : cube.corners()) {
            assertVec(BlockbenchTransformReference.transformPoint(chain, corner, Map.of(), Map.of(), Map.of()),
                    transform(chain, corner, Map.of(), Map.of(), Map.of()),
                    "the posed corner " + corner + " must match the authoring tool's chain");
        }

        // An animated pose on the parent must move the cube with it, still matching the authoring tool.
        Map<String, Vec3> animatedRotations = Map.of("root", new Vec3(10.0F, 0.0F, 0.0F));
        for (Vec3 corner : cube.corners()) {
            assertVec(BlockbenchTransformReference.transformPoint(chain, corner, Map.of(), animatedRotations,
                            Map.of()),
                    transform(chain, corner, Map.of(), animatedRotations, Map.of()),
                    "the posed corner " + corner + " must follow an animated parent rotation");
        }
    }

    // ---------------------------------------------------------------- 9. several cubes, different transforms

    @Test
    void severalCubesInOneBoneKeepTheirOwnTransforms() {
        Authored first = new Authored(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(2.0F, 2.0F, 2.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);
        Authored second = new Authored(new Vec3(6.0F, 1.0F, -2.0F), new Vec3(1.0F, 3.0F, 2.0F), 0.3F,
                new Vec3(0.0F, 30.0F, 0.0F), new Vec3(6.5F, 2.5F, -1.0F));
        Authored third = new Authored(new Vec3(-5.0F, -4.0F, 3.0F), new Vec3(3.0F, 1.0F, 4.0F), -0.2F,
                new Vec3(45.0F, 0.0F, 10.0F), Vec3.ZERO);
        Parsed parsed = parse(geometry(bone("body", null, null, null, null,
                cube("first", first, allFaces()),
                cube("second", second, allFaces()),
                cube("third", third, allFaces()))));

        assertEquals(3, parsed.model().cubeCount(), "all three cubes must be kept");
        assertEquals(36, parsed.triangles(), "two triangles per face, six faces per cube");
        assertExactCoverage(parsed.mesh(), List.of(first, second, third));
    }

    // ---------------------------------------------------------------- 10. everything at once

    @Test
    void aCubeWithEveryTransformAtOnceMatchesTheAuthoringChain() {
        Authored cube = new Authored(new Vec3(3.5F, -6.0F, 12.25F), new Vec3(1.5F, 2.5F, 4.0F), -0.15F,
                new Vec3(22.5F, -35.0F, 8.0F), new Vec3(0.0F, 1.0F, 11.875F));
        float[] rootPivot = {0.5F, -1.0F, 2.0F};
        float[] rootRotation = {-13.3F, -13.0F, -13.8F};
        float[] bodyPivot = {-0.25F, 3.5F, 9.0F};
        float[] bodyRotation = {40.0F, -55.0F, 12.5F};

        Parsed parsed = parse(geometry(
                bone("root", null, rootPivot, rootRotation, null),
                bone("body", "root", bodyPivot, bodyRotation, null, cube("box", cube, allFaces()))));

        assertCube(parsed.mesh(), cube);

        List<ModelBone> chain = parsed.model().bonesInOrder();
        Map<String, Vec3> positions = Map.of("root", new Vec3(1.0F, -2.0F, 3.0F));
        Map<String, Vec3> animated = Map.of("body", new Vec3(-7.5F, 4.0F, 20.0F));
        Map<String, Vec3> scales = Map.of("body", new Vec3(1.0F, 1.5F, 1.0F));
        for (Vec3 corner : cube.corners()) {
            assertVec(BlockbenchTransformReference.transformPoint(chain, corner, positions, animated, scales),
                    transform(chain, corner, positions, animated, scales),
                    "corner " + corner + " of the fully transformed cube");
        }
    }

    // ---------------------------------------------------------------- bone-level inflation

    @Test
    void boneLevelInflationIsTheDefaultAndACubeCanOverrideIt() {
        Authored inheriting = new Authored(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(2.0F, 2.0F, 2.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);
        Authored overriding = new Authored(new Vec3(10.0F, 0.0F, 0.0F), new Vec3(2.0F, 2.0F, 2.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);

        Parsed parsed = parse(geometry(bone("body", null, null, null, 0.5F,
                cube("inheriting", inheriting, allFaces()),
                cube("overriding", overriding.withInflate(-0.5F), allFaces()))));

        // The inheriting cube uses the bone's 0.5 and grows by one unit per axis; the overriding cube uses its own -0.5
        // and shrinks by one unit per axis.
        assertExactCoverage(parsed.mesh(), List.of(inheriting.withInflate(0.5F), overriding.withInflate(-0.5F)));
        assertEquals(3.0F, boundsOf(parsed.mesh(), inheriting.withInflate(0.5F)).extent(0), 1.0E-4F,
                "the bone inflation grows the cube");
        assertEquals(1.0F, boundsOf(parsed.mesh(), overriding.withInflate(-0.5F)).extent(0), 1.0E-4F,
                "the cube's own inflation overrides the bone's");
    }

    // ---------------------------------------------------------------- robustness

    @Test
    void aNumberTooLargeToRepresentIsReportedInsteadOfThrown() {
        // A JSON number can overflow a float. Vec3 rejects non-finite components, so without the guard in the numeric
        // readers one absurd value would abort the whole model load instead of skipping the cube it belongs to.
        JsonObject broken = cube("broken", new Authored(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(1.0F, 1.0F, 1.0F),
                0.0F, Vec3.ZERO, Vec3.ZERO), allFaces());
        broken.getAsJsonArray("origin").set(0, new JsonPrimitive(1.0E39));

        Parsed parsed = parse(geometry(bone("body", null, null, null, null, broken)));

        assertNull(parsed.model(), "the only cube is unusable, so there is no renderable geometry");
        assertTrue(parsed.hasDiagnostic(PackDiagnostic.Severity.ERROR, "too large to represent"),
                "the unusable number has to be reported: " + parsed.describe());
    }

    @Test
    void anInflationTooLargeToRepresentFallsBackToTheBoneValue() {
        Authored cube = new Authored(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(2.0F, 2.0F, 2.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);
        JsonObject broken = cube("box", cube, allFaces());
        broken.addProperty("inflate", 1.0E39);

        Parsed parsed = parse(geometry(bone("body", null, null, null, 0.25F, broken)));

        assertNotNull(parsed.model(), parsed.describe());
        assertTrue(parsed.hasDiagnostic(PackDiagnostic.Severity.WARNING, "too large to represent"),
                "the fallback has to be reported: " + parsed.describe());
        assertCube(parsed.mesh(), cube.withInflate(0.25F));
    }

    // ---------------------------------------------------------------- UVs

    @Test
    void everyFaceSamplesItsOwnUvRectangle() {
        Map<String, float[]> uvs = new LinkedHashMap<>();
        for (int index = 0; index < FACES.size(); index++) {
            uvs.put(FACES.get(index), new float[]{index * 4.0F, index * 4.0F, 4.0F, 4.0F});
        }
        Authored cube = new Authored(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(2.0F, 2.0F, 2.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);
        Parsed parsed = parse(geometry(bone("body", null, null, null, null, cube("box", cube, uvs))));

        for (String face : FACES) {
            float[] rect = uvs.get(face);
            List<Corner> quad = quad(parsed.mesh(), axisNormal(face));
            assertEquals(4, quad.size(), "face '" + face + "' must produce exactly one quad");
            assertRectCorners(rect, quad, "face '" + face + "'");
        }
    }

    @Test
    void aNegativeUvSizeFlipsTheSamplingDirectionOfThatFace() {
        // Negative uv_size is how the format and the authoring tool flip a face's texture: the rectangle covers the same
        // texels, but the corner that sampled one end now samples the other.
        Map<String, float[]> positive = new LinkedHashMap<>();
        Map<String, float[]> negative = new LinkedHashMap<>();
        for (int index = 0; index < FACES.size(); index++) {
            positive.put(FACES.get(index), new float[]{index * 4.0F, index * 4.0F, 2.0F, 2.0F});
            negative.put(FACES.get(index), new float[]{index * 4.0F, index * 4.0F, -2.0F, -2.0F});
        }
        Authored cube = new Authored(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(2.0F, 2.0F, 2.0F), 0.0F, Vec3.ZERO,
                Vec3.ZERO);
        Parsed straight = parse(geometry(bone("body", null, null, null, null, cube("box", cube, positive))));
        Parsed flipped = parse(geometry(bone("body", null, null, null, null, cube("box", cube, negative))));

        for (String face : FACES) {
            List<Corner> straightQuad = quad(straight.mesh(), axisNormal(face));
            List<Corner> flippedQuad = quad(flipped.mesh(), axisNormal(face));
            assertEquals(4, flippedQuad.size(), "face '" + face + "' must still produce one quad");
            assertRectCorners(negative.get(face), flippedQuad, "flipped face '" + face + "'");

            // The two quads have identical corners in the same order, so the flip has to show up in the UV pairs: every
            // corner samples the opposite end of the rectangle.
            for (int index = 0; index < 4; index++) {
                Corner before = straightQuad.get(index);
                Corner after = flippedQuad.get(index);
                assertVec(before.position(), after.position(), "the flip must not move any corner");
                assertEquals(before.u() + after.u(), 2.0F * negative.get(face)[0] / TEXTURE_SIZE, 1.0E-4F,
                        "u is mirrored within the rectangle");
                assertEquals(before.v() + after.v(), 2.0F * negative.get(face)[1] / TEXTURE_SIZE, 1.0E-4F,
                        "v is mirrored within the rectangle");
            }
        }
    }

    // ---------------------------------------------------------------- geometry fixtures

    /**
     * One cube as authored, resolved into the model space the renderer draws in.
     *
     * <p>Only the authoring tool's rules are used here: the box is the authored origin/size grown by {@code inflate} on
     * every side, its corners are displayed mirrored in X, rotated by one {@code ZYX} matrix from the displayed triple
     * about the displayed pivot, and the result is mirrored back into model space. That is the whole expected side of
     * this test - none of the parser's rotation code is involved.</p>
     */
    private record Authored(Vec3 origin, Vec3 size, float inflate, Vec3 rotation, Vec3 pivot) {

        Authored withInflate(float value) {
            return new Authored(origin, size, value, rotation, pivot);
        }

        float min(int axis) {
            return Math.min(component(origin, axis), component(origin, axis) + component(size, axis)) - inflate;
        }

        float max(int axis) {
            return Math.max(component(origin, axis), component(origin, axis) + component(size, axis)) + inflate;
        }

        float extent(int axis) {
            return max(axis) - min(axis);
        }

        Vec3 centre() {
            return new Vec3((min(0) + max(0)) / 2.0F, (min(1) + max(1)) / 2.0F, (min(2) + max(2)) / 2.0F);
        }

        /** @return the rotation pivot, i.e. the authored one or the box centre when the cube declares none. */
        Vec3 effectivePivot() {
            return pivot.isZero() ? centre() : pivot;
        }

        /** @return the four corners of one face of the box, in the order the faces are built from. */
        List<Vec3> faceCorners(String face) {
            float minX = min(0);
            float minY = min(1);
            float minZ = min(2);
            float maxX = max(0);
            float maxY = max(1);
            float maxZ = max(2);
            return switch (face) {
                case "north" -> List.of(new Vec3(minX, minY, minZ), new Vec3(maxX, minY, minZ),
                        new Vec3(maxX, maxY, minZ), new Vec3(minX, maxY, minZ));
                case "south" -> List.of(new Vec3(maxX, minY, maxZ), new Vec3(minX, minY, maxZ),
                        new Vec3(minX, maxY, maxZ), new Vec3(maxX, maxY, maxZ));
                case "east" -> List.of(new Vec3(maxX, minY, maxZ), new Vec3(maxX, minY, minZ),
                        new Vec3(maxX, maxY, minZ), new Vec3(maxX, maxY, maxZ));
                case "west" -> List.of(new Vec3(minX, minY, minZ), new Vec3(minX, minY, maxZ),
                        new Vec3(minX, maxY, maxZ), new Vec3(minX, maxY, minZ));
                case "up" -> List.of(new Vec3(minX, maxY, minZ), new Vec3(maxX, maxY, minZ),
                        new Vec3(maxX, maxY, maxZ), new Vec3(minX, maxY, maxZ));
                case "down" -> List.of(new Vec3(minX, minY, maxZ), new Vec3(maxX, minY, maxZ),
                        new Vec3(maxX, minY, minZ), new Vec3(minX, minY, minZ));
                default -> throw new IllegalArgumentException("Unknown face '" + face + "'");
            };
        }

        /** @return every corner this cube contributes, i.e. the corners of the six faces it renders. */
        List<Vec3> corners() {
            List<Vec3> corners = new ArrayList<>(24);
            for (String face : FACES) {
                for (Vec3 corner : faceCorners(face)) {
                    corners.add(rotated(corner));
                }
            }
            return corners;
        }

        /** @return the four expected corners of one face, after the cube's own rotation. */
        List<Vec3> rotatedFaceCorners(String face) {
            List<Vec3> corners = new ArrayList<>(4);
            for (Vec3 corner : faceCorners(face)) {
                corners.add(rotated(corner));
            }
            return corners;
        }

        /** @return where the authoring tool puts one model-space corner, still in model space. */
        Vec3 rotated(Vec3 corner) {
            Vec3 displayedPivot = BlockbenchTransformReference.displayedPosition(effectivePivot());
            Vec3 displayed = BlockbenchTransformReference.displayedPosition(corner);
            double[] local = {displayed.x() - displayedPivot.x(), displayed.y() - displayedPivot.y(),
                    displayed.z() - displayedPivot.z(), 1.0};
            double[] result = BlockbenchTransformReference.multiply(matrix(), local);
            Vec3 rotated = new Vec3((float) (result[0] + displayedPivot.x()),
                    (float) (result[1] + displayedPivot.y()), (float) (result[2] + displayedPivot.z()));
            return BlockbenchTransformReference.displayedPosition(rotated);
        }

        /** @return the expected shading normal of one face, after the cube's own rotation. */
        Vec3 rotatedFaceNormal(String face) {
            Vec3 displayed = BlockbenchTransformReference.displayedPosition(axisNormal(face));
            double[] result = BlockbenchTransformReference.multiply(matrix(),
                    new double[]{displayed.x(), displayed.y(), displayed.z(), 1.0});
            return BlockbenchTransformReference.displayedPosition(normalise(new Vec3((float) result[0],
                    (float) result[1], (float) result[2])));
        }

        private double[][] matrix() {
            Vec3 displayed = BlockbenchTransformReference.displayedRotation(rotation);
            return displayed.isZero() ? BlockbenchTransformReference.identity()
                    : BlockbenchTransformReference.zyx(displayed);
        }

        private static float component(Vec3 vector, int axis) {
            return switch (axis) {
                case 0 -> vector.x();
                case 1 -> vector.y();
                default -> vector.z();
            };
        }
    }

    // ---------------------------------------------------------------- geometry builders

    private static JsonObject geometry(JsonObject... bones) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry.arg_weaponry_test");
        description.addProperty("texture_width", TEXTURE_SIZE);
        description.addProperty("texture_height", TEXTURE_SIZE);

        JsonArray boneArray = new JsonArray();
        for (JsonObject bone : bones) {
            boneArray.add(bone);
        }

        JsonObject geometry = new JsonObject();
        geometry.add("description", description);
        geometry.add("bones", boneArray);

        JsonArray geometries = new JsonArray();
        geometries.add(geometry);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.16.0");
        root.add(BedrockGeometryParser.GEOMETRY_KEY, geometries);
        return root;
    }

    private static JsonObject bone(String name, String parent, float[] pivot, float[] rotation, Float inflate,
                                   JsonObject... cubes) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        if (parent != null) {
            bone.addProperty("parent", parent);
        }
        if (pivot != null) {
            bone.add("pivot", array(pivot));
        }
        if (rotation != null) {
            bone.add("rotation", array(rotation));
        }
        if (inflate != null) {
            bone.addProperty("inflate", inflate);
        }
        JsonArray cubeArray = new JsonArray();
        for (JsonObject cube : cubes) {
            cubeArray.add(cube);
        }
        bone.add("cubes", cubeArray);
        return bone;
    }

    private static JsonObject cube(String name, Authored cube, Map<String, float[]> faceUvs) {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.add("origin", array(new float[]{cube.origin().x(), cube.origin().y(), cube.origin().z()}));
        object.add("size", array(new float[]{cube.size().x(), cube.size().y(), cube.size().z()}));
        if (cube.inflate() != 0.0F) {
            object.addProperty("inflate", cube.inflate());
        }
        if (!cube.rotation().isZero()) {
            object.add("rotation", array(new float[]{cube.rotation().x(), cube.rotation().y(), cube.rotation().z()}));
        }
        if (!cube.pivot().isZero()) {
            object.add("pivot", array(new float[]{cube.pivot().x(), cube.pivot().y(), cube.pivot().z()}));
        }
        object.add("uv", faces(faceUvs));
        return object;
    }

    private static JsonObject faces(Map<String, float[]> faceUvs) {
        JsonObject uv = new JsonObject();
        for (Map.Entry<String, float[]> entry : faceUvs.entrySet()) {
            JsonObject face = new JsonObject();
            face.add("uv", array(new float[]{entry.getValue()[0], entry.getValue()[1]}));
            face.add("uv_size", array(new float[]{entry.getValue()[2], entry.getValue()[3]}));
            uv.add(entry.getKey(), face);
        }
        return uv;
    }

    /** @return the per-face UV rectangles of a cube that maps every face to its own 2x2 texel block. */
    private static Map<String, float[]> allFaces() {
        Map<String, float[]> uvs = new LinkedHashMap<>();
        for (int index = 0; index < FACES.size(); index++) {
            uvs.put(FACES.get(index), new float[]{index * 2.0F, 0.0F, 2.0F, 2.0F});
        }
        return uvs;
    }

    private static JsonArray array(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }
        return array;
    }

    // ---------------------------------------------------------------- parsing

    private static Parsed parse(JsonObject root) {
        ValidationReport report = new ValidationReport();
        WeaponModel model = BedrockGeometryParser.parse("arg_default_pack",
                new ResourceId("arg_pack", "models/test_cube"), "models/test_cube.json", root, report);
        BoneMesh mesh = null;
        if (model != null) {
            for (ModelBone bone : model.bonesInOrder()) {
                for (BoneMesh candidate : bone.meshes()) {
                    if (candidate.source() == BoneMesh.Source.CUBE) {
                        mesh = candidate;
                        break;
                    }
                }
                if (mesh != null) {
                    break;
                }
            }
        }
        return new Parsed(model, report, mesh);
    }

    /** The result of one parse, with the cube mesh and the diagnostics kept together. */
    private record Parsed(WeaponModel model, ValidationReport report, BoneMesh mesh) {

        int triangles() {
            return mesh == null ? 0 : mesh.triangleCount();
        }

        boolean hasDiagnostic(PackDiagnostic.Severity severity, String fragment) {
            return report.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.severity() == severity
                            && diagnostic.message().toLowerCase().contains(fragment.toLowerCase()));
        }

        String describe() {
            return "diagnostics: " + report.diagnostics().stream().map(PackDiagnostic::format).toList();
        }
    }


    // ---------------------------------------------------------------- assertions

    /** Asserts that every corner of one cube is present with its face's normal, and that the mesh is well formed. */
    private static void assertCube(BoneMesh mesh, Authored cube) {
        assertNotNull(mesh, "the cube must produce geometry");
        for (String face : FACES) {
            Vec3 expectedNormal = cube.rotatedFaceNormal(face);
            for (Vec3 corner : cube.rotatedFaceCorners(face)) {
                assertCorner(mesh, corner, expectedNormal, "face '" + face + "' of the cube");
            }
            assertEquals(4, quad(mesh, expectedNormal).size(),
                    "face '" + face + "' must be present as a quad of the mesh");
        }
        assertWellFormed(mesh);
    }

    /** Asserts that the mesh holds exactly the corners of the given cubes - no cube dropped, none invented. */
    private static void assertExactCoverage(BoneMesh mesh, List<Authored> cubes) {
        assertNotNull(mesh, "the cubes must produce geometry");
        List<Vec3> expected = new ArrayList<>();
        for (Authored cube : cubes) {
            expected.addAll(cube.corners());
        }
        assertEquals(expected.size(), mesh.cornerCount(), "the mesh must hold one corner per face corner");
        for (Vec3 corner : expected) {
            assertCorner(mesh, corner, null, "authored corner");
        }
        assertWellFormed(mesh);
    }

    private static void assertCorner(BoneMesh mesh, Vec3 expected, Vec3 normal, String description) {
        float[] positions = mesh.positionsUnsafe();
        float[] normals = mesh.normalsUnsafe();
        for (int corner = 0; corner < positions.length / 3; corner++) {
            if (distance(position(positions, corner), expected) > TOLERANCE) {
                continue;
            }
            if (normal == null) {
                return;
            }
            Vec3 actual = new Vec3(normals[corner * 3], normals[corner * 3 + 1], normals[corner * 3 + 2]);
            if (distance(actual, normal) <= TOLERANCE) {
                return;
            }
        }
        // A position is shared by several faces of a cube and each copy keeps its own normal, so the search above must
        // look at every corner before it gives up - and the diagnostic below names the normals that were found there.
        throw new AssertionError(description + ": no corner is at " + expected + (normal == null ? ""
                : " with the normal " + normal) + "; the nearest corner is " + nearest(mesh, expected)
                + " model units away and carries the normal(s) " + normalsAt(mesh, expected));
    }

    /** @return the normals of every corner of the mesh that sits at the given position. */
    private static String normalsAt(BoneMesh mesh, Vec3 expected) {
        float[] positions = mesh.positionsUnsafe();
        float[] normals = mesh.normalsUnsafe();
        List<String> found = new ArrayList<>();
        for (int corner = 0; corner < positions.length / 3; corner++) {
            if (distance(position(positions, corner), expected) <= TOLERANCE) {
                found.add(String.valueOf(new Vec3(normals[corner * 3], normals[corner * 3 + 1],
                        normals[corner * 3 + 2])));
            }
        }
        return found.isEmpty() ? "none" : String.join(", ", found);
    }

    /** @return the distance from {@code point} to the closest corner of the mesh. */
    private static float nearest(BoneMesh mesh, Vec3 point) {
        float[] positions = mesh.positionsUnsafe();
        float nearest = Float.MAX_VALUE;
        for (int corner = 0; corner < positions.length / 3; corner++) {
            nearest = Math.min(nearest, distance(position(positions, corner), point));
        }
        return nearest;
    }


    /**
     * Checks the invariants every quad of a cube must satisfy, whatever the cube's transformation.
     *
     * <p>All corners of a quad share one unit normal; the winding of the triangle agrees with that normal, so the surface
     * is not lit from behind; and every corner pair differs along a direction perpendicular to the normal, which is what
     * "the face is still flat" means after a rotation.</p>
     */
    private static void assertWellFormed(BoneMesh mesh) {
        float[] positions = mesh.positionsUnsafe();
        float[] normals = mesh.normalsUnsafe();
        int[] quads = mesh.quadCornerIndicesUnsafe();
        for (int base = 0; base < quads.length; base += 4) {
            Vec3 normal = new Vec3(normals[quads[base] * 3], normals[quads[base] * 3 + 1],
                    normals[quads[base] * 3 + 2]);
            assertEquals(1.0F, length(normal), 1.0E-3F, "a shading normal must stay unit length");
            Vec3 first = position(positions, quads[base]);
            Vec3 second = position(positions, quads[base + 1]);
            Vec3 third = position(positions, quads[base + 2]);
            Vec3 fourth = position(positions, quads[base + 3]);
            for (int corner : new int[]{quads[base + 1], quads[base + 2], quads[base + 3]}) {
                Vec3 other = new Vec3(normals[corner * 3], normals[corner * 3 + 1], normals[corner * 3 + 2]);
                assertVec(normal, other, "every corner of a flat-shaded face carries the face normal");
            }
            Vec3 geometric = cross(subtract(second, first), subtract(third, first));
            if (length(geometric) < 1.0E-6F) {
                continue;
            }
            assertTrue(dot(normalise(geometric), normal) > 0.0F,
                    "the triangle winding must agree with the normal it is shaded with");
            for (Vec3 corner : List.of(second, third, fourth)) {
                assertEquals(0.0F, dot(subtract(corner, first), normal), 1.0E-3F,
                        "every corner of a face must lie in the face's plane");
            }
        }
    }

    /** @return the four corners of the mesh quad whose corners carry the given normal, or an empty list. */
    private static List<Corner> quad(BoneMesh mesh, Vec3 normal) {
        float[] positions = mesh.positionsUnsafe();
        float[] normals = mesh.normalsUnsafe();
        float[] uvs = mesh.uvsUnsafe();
        int[] quads = mesh.quadCornerIndicesUnsafe();
        for (int base = 0; base < quads.length; base += 4) {
            Vec3 actual = new Vec3(normals[quads[base] * 3], normals[quads[base] * 3 + 1],
                    normals[quads[base] * 3 + 2]);
            if (distance(actual, normal) > TOLERANCE) {
                continue;
            }
            List<Corner> corners = new ArrayList<>(4);
            for (int offset = 0; offset < 4; offset++) {
                int corner = quads[base + offset];
                corners.add(new Corner(position(positions, corner), uvs[corner * 2], uvs[corner * 2 + 1]));
            }
            return corners;
        }
        return List.of();
    }

    /** Asserts that the four UV pairs of a face are exactly the corners of the authored rectangle. */
    private static void assertRectCorners(float[] rect, List<Corner> quad, String description) {
        float minU = rect[0] / TEXTURE_SIZE;
        float minV = rect[1] / TEXTURE_SIZE;
        float maxU = (rect[0] + rect[2]) / TEXTURE_SIZE;
        float maxV = (rect[1] + rect[3]) / TEXTURE_SIZE;
        for (Corner corner : quad) {
            boolean onU = Math.min(Math.abs(corner.u() - minU), Math.abs(corner.u() - maxU)) <= 1.0E-4F;
            boolean onV = Math.min(Math.abs(corner.v() - minV), Math.abs(corner.v() - maxV)) <= 1.0E-4F;
            assertTrue(onU && onV, description + ": the UV (" + corner.u() + ", " + corner.v()
                    + ") is not a corner of the authored rectangle");
        }
    }

    /** @return the axis-aligned bounds of every corner of the mesh. */
    private static Bounds bounds(BoneMesh mesh) {
        float[] positions = mesh.positionsUnsafe();
        Bounds bounds = new Bounds();
        for (int corner = 0; corner < positions.length / 3; corner++) {
            bounds = bounds.with(position(positions, corner));
        }
        return bounds;
    }

    /** @return the bounds of the corners that belong to one cube of a multi-cube mesh. */
    private static Bounds boundsOf(BoneMesh mesh, Authored cube) {
        float[] positions = mesh.positionsUnsafe();
        List<Vec3> expected = cube.corners();
        Bounds bounds = new Bounds();
        for (int corner = 0; corner < positions.length / 3; corner++) {
            Vec3 position = position(positions, corner);
            for (Vec3 candidate : expected) {
                if (distance(position, candidate) <= TOLERANCE) {
                    bounds = bounds.with(position);
                    break;
                }
            }
        }
        return bounds;
    }

    private static void assertVec(Vec3 expected, Vec3 actual, String description) {
        assertEquals(expected.x(), actual.x(), TOLERANCE, description + " (x of " + actual + " vs " + expected + ")");
        assertEquals(expected.y(), actual.y(), TOLERANCE, description + " (y of " + actual + " vs " + expected + ")");
        assertEquals(expected.z(), actual.z(), TOLERANCE, description + " (z of " + actual + " vs " + expected + ")");
    }


    // ---------------------------------------------------------------- bone chain

    /**
     * Applies the renderer's per-bone steps down a bone chain to one model-space point.
     *
     * <p>This is the same composition the renderer performs with its {@code PoseStack}: the steps of every bone are
     * multiplied onto the matrix in order, and the point is transformed by the result. The expected side of the test
     * comes from {@link BlockbenchTransformReference#transformPoint}, so the two are independent.</p>
     */
    private static Vec3 transform(List<ModelBone> chain, Vec3 point, Map<String, Vec3> positions,
                                  Map<String, Vec3> rotations, Map<String, Vec3> scales) {
        double[][] total = BlockbenchTransformReference.identity();
        for (ModelBone bone : chain) {
            total = BlockbenchTransformReference.multiply(total, compose(BoneTransformPolicy.steps(bone,
                    positions.get(bone.name()), rotations.get(bone.name()), scales.get(bone.name()))));
        }
        double[] transformed = BlockbenchTransformReference.multiply(total,
                new double[]{point.x(), point.y(), point.z(), 1.0});
        return new Vec3((float) transformed[0], (float) transformed[1], (float) transformed[2]);
    }

    private static double[][] compose(List<BoneTransformPolicy.Step> steps) {
        double[][] result = BlockbenchTransformReference.identity();
        for (BoneTransformPolicy.Step step : steps) {
            Vec3 value = step.value();
            double[][] next = switch (step.kind()) {
                case TRANSLATE -> BlockbenchTransformReference.translation(value.x(), value.y(), value.z());
                case ROTATE_X -> BlockbenchTransformReference.rotationX(value.x());
                case ROTATE_Y -> BlockbenchTransformReference.rotationY(value.y());
                case ROTATE_Z -> BlockbenchTransformReference.rotationZ(value.z());
                case SCALE -> BlockbenchTransformReference.scale(value.x(), value.y(), value.z());
            };
            result = BlockbenchTransformReference.multiply(result, next);
        }
        return result;
    }

    // ---------------------------------------------------------------- vector helpers

    private static Vec3 axisNormal(String face) {
        return switch (face) {
            case "north" -> new Vec3(0.0F, 0.0F, -1.0F);
            case "south" -> new Vec3(0.0F, 0.0F, 1.0F);
            case "east" -> new Vec3(1.0F, 0.0F, 0.0F);
            case "west" -> new Vec3(-1.0F, 0.0F, 0.0F);
            case "up" -> new Vec3(0.0F, 1.0F, 0.0F);
            case "down" -> new Vec3(0.0F, -1.0F, 0.0F);
            default -> throw new IllegalArgumentException("Unknown face '" + face + "'");
        };
    }

    private static Vec3 position(float[] positions, int corner) {
        return new Vec3(positions[corner * 3], positions[corner * 3 + 1], positions[corner * 3 + 2]);
    }

    private static Vec3 subtract(Vec3 first, Vec3 second) {
        return new Vec3(first.x() - second.x(), first.y() - second.y(), first.z() - second.z());
    }

    private static Vec3 cross(Vec3 first, Vec3 second) {
        return new Vec3(first.y() * second.z() - first.z() * second.y(),
                first.z() * second.x() - first.x() * second.z(),
                first.x() * second.y() - first.y() * second.x());
    }

    private static float dot(Vec3 first, Vec3 second) {
        return first.x() * second.x() + first.y() * second.y() + first.z() * second.z();
    }

    private static float length(Vec3 vector) {
        return (float) Math.sqrt(dot(vector, vector));
    }

    private static Vec3 normalise(Vec3 vector) {
        float length = length(vector);
        return length < 1.0E-6F ? vector : new Vec3(vector.x() / length, vector.y() / length, vector.z() / length);
    }

    private static float distance(Vec3 first, Vec3 second) {
        return length(subtract(first, second));
    }

    // ---------------------------------------------------------------- value types

    /** One corner of a quad: where it is and what it samples. */
    private record Corner(Vec3 position, float u, float v) {
    }

    /** Axis-aligned bounds of a set of corners, in model units. */
    private record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

        Bounds() {
            this(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
                    -Float.MAX_VALUE);
        }

        Bounds with(Vec3 point) {
            return new Bounds(Math.min(minX, point.x()), Math.min(minY, point.y()), Math.min(minZ, point.z()),
                    Math.max(maxX, point.x()), Math.max(maxY, point.y()), Math.max(maxZ, point.z()));
        }

        float extent(int axis) {
            return switch (axis) {
                case 0 -> maxX - minX;
                case 1 -> maxY - minY;
                default -> maxZ - minZ;
            };
        }

        Vec3 centre() {
            return new Vec3((minX + maxX) / 2.0F, (minY + maxY) / 2.0F, (minZ + maxZ) / 2.0F);
        }
    }
}
