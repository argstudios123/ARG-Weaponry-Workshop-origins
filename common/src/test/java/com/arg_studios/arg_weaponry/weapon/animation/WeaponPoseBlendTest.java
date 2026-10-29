package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The pose algebra behind the equip cross-fade, tested without a game, a resource pack or a renderer.
 *
 * <p>The properties that matter are the ones a naive implementation gets wrong: both ends must be reproduced exactly so a
 * finished fade is the clip's own pose, rotations must take the shortest arc and must not be interpolated as three
 * independent Euler angles, a bone that only one side animates must fade against the authored transform instead of
 * snapping, and every step must stay on that same short path.</p>
 */
class WeaponPoseBlendTest {

    @Test
    void theTwoEndsAreReproducedExactly() {
        WeaponPose from = pose("from", bone("root", new Vec3(0.0F, 40.0F, 0.0F), null, null));
        WeaponPose to = pose("to", bone("root", new Vec3(0.0F, -40.0F, 0.0F), null, null));

        assertSame(from, WeaponPoseBlend.blend(from, to, 0.0F), "weight zero is the outgoing pose itself");
        assertSame(to, WeaponPoseBlend.blend(from, to, 1.0F), "weight one is the incoming pose itself");
        assertSame(to, WeaponPoseBlend.blend(from, to, 2.0F), "weights above one are clamped");
        assertSame(from, WeaponPoseBlend.blend(from, to, -1.0F), "weights below zero are clamped");
    }

    @Test
    void aNullOrEmptySideIsHandledWithoutInventingData() {
        WeaponPose pose = pose("only", bone("root", new Vec3(0.0F, 30.0F, 0.0F), null, null));

        assertSame(pose, WeaponPoseBlend.blend(null, pose, 0.5F), "nothing to fade from means the target is returned");
        assertSame(pose, WeaponPoseBlend.blend(WeaponPose.NONE, pose, 0.0F));
        assertEquals(WeaponPose.NONE, WeaponPoseBlend.blend(pose, null, 1.0F));

        // Fading towards nothing is fading towards the authored pose: the animated deltas shrink to identity.
        WeaponPose fading = WeaponPoseBlend.blend(pose, null, 0.5F);
        SampledBone root = fading.bone("root");
        assertNotNull(root);
        assertEquals(15.0, root.rotation().y(), 1.0E-4, "half way to the authored zero rotation");
        assertEquals(0.0, root.position().y(), 1.0E-4);
        assertEquals(1.0F, root.scale().y(), 1.0E-4);
    }

    @Test
    void positionAndScaleAreInterpolatedComponentWise() {
        WeaponPose from = pose("from", bone("root", null, new Vec3(0.0F, 0.0F, 0.0F), new Vec3(1.0F, 1.0F, 1.0F)));
        WeaponPose to = pose("to", bone("root", null, new Vec3(2.0F, 4.0F, -6.0F), new Vec3(0.0F, 0.5F, 2.0F)));

        SampledBone middle = WeaponPoseBlend.blend(from, to, 0.25F).bone("root");
        assertNotNull(middle);
        assertEquals(0.5, middle.position().x(), 1.0E-4);
        assertEquals(1.0, middle.position().y(), 1.0E-4);
        assertEquals(-1.5, middle.position().z(), 1.0E-4);
        assertEquals(0.75, middle.scale().x(), 1.0E-4);
        assertEquals(0.875, middle.scale().y(), 1.0E-4);
        assertEquals(1.25, middle.scale().z(), 1.0E-4);
    }

    // ---------------------------------------------------------------- rotation: shortest arc, on the sphere

    @Test
    void rotationTakesTheShortestArcAcrossTheWrap() {
        // 170 and -170 degrees are 20 degrees apart, not 340: a component-wise Euler interpolation would swing the weapon
        // all the way round through zero.
        Vec3 from = new Vec3(0.0F, 170.0F, 0.0F);
        Vec3 to = new Vec3(0.0F, -170.0F, 0.0F);
        Vec3 middle = WeaponPoseBlend.blendRotation(from, to, 0.5F);

        double whole = WeaponPoseBlend.angleDegrees(from, to);
        assertEquals(20.0, whole, 0.5, "the two ends are twenty degrees apart");
        assertEquals(10.0, WeaponPoseBlend.angleDegrees(from, middle), 0.5,
                "the midpoint is ten degrees from the outgoing pose, going through 180 rather than through zero");
        assertEquals(10.0, WeaponPoseBlend.angleDegrees(middle, to), 0.5);
        assertEquals(whole, WeaponPoseBlend.angleDegrees(from, middle) + WeaponPoseBlend.angleDegrees(middle, to),
                0.5, "the three poses are collinear on the short arc");
    }

    @Test
    void aRotationIsInterpolatedOnTheSphereNotComponentWise() {
        Vec3 from = new Vec3(0.0F, 0.0F, 0.0F);
        Vec3 to = new Vec3(90.0F, 90.0F, 0.0F);
        Vec3 middle = WeaponPoseBlend.blendRotation(from, to, 0.5F);

        double whole = WeaponPoseBlend.angleDegrees(from, to);
        assertEquals(whole / 2.0, WeaponPoseBlend.angleDegrees(from, middle), 0.5,
                "the midpoint is half the geodesic away from both ends");
        assertEquals(whole / 2.0, WeaponPoseBlend.angleDegrees(middle, to), 0.5);
        // Component-wise averaging would give (45, 45, 0), which is not the midpoint of the arc.
        assertTrue(Math.abs(middle.x() - 45.0) > 1.0 || Math.abs(middle.y() - 45.0) > 1.0,
                "the interpolated triple is the arc's midpoint, not the mean of the Euler angles");
    }

    @Test
    void everyStepOfTheBlendStaysOnTheSameShortPath() {
        Vec3 from = new Vec3(-12.0F, -130.0F, 8.0F);
        Vec3 to = new Vec3(35.0F, 120.0F, -20.0F);
        double whole = WeaponPoseBlend.angleDegrees(from, to);

        double previous = 0.0;
        for (int step = 1; step <= 20; step++) {
            Vec3 current = WeaponPoseBlend.blendRotation(from, to, step / 20.0F);
            double along = WeaponPoseBlend.angleDegrees(from, current);
            assertTrue(along >= previous - 1.0E-3,
                    "the blend must move steadily away from the outgoing pose, step " + step);
            assertTrue(along <= whole + 1.0E-3, "and must never overshoot the arc, step " + step);
            previous = along;
        }
        assertEquals(whole, previous, 0.5, "the last step reaches the incoming rotation");
        assertEquals(0.0, WeaponPoseBlend.angleDegrees(to, WeaponPoseBlend.blendRotation(from, to, 1.0F)), 1.0E-3);
    }

    // ---------------------------------------------------------------- missing channels and missing bones

    @Test
    void aBoneOnlyOneSideAnimatesFadesAgainstTheAuthoredTransform() {
        WeaponPose from = pose("from",
                bone("root", new Vec3(0.0F, 60.0F, 0.0F), new Vec3(0.0F, 4.0F, 0.0F), null),
                bone("only_in_from", new Vec3(0.0F, 20.0F, 0.0F), null, null));
        WeaponPose to = pose("to",
                bone("root", new Vec3(0.0F, 90.0F, 0.0F), null, null),
                bone("only_in_to", new Vec3(0.0F, -20.0F, 0.0F), null, null));

        WeaponPose middle = WeaponPoseBlend.blend(from, to, 0.5F);
        assertEquals(3, middle.boneCount(), "the union of both bone sets is kept, so nothing pops out of the pose");

        SampledBone leaving = middle.bone("only_in_from");
        assertNotNull(leaving);
        assertEquals(10.0, leaving.rotation().y(), 0.5, "a bone the target does not animate fades to the authored pose");

        SampledBone entering = middle.bone("only_in_to");
        assertNotNull(entering);
        assertEquals(-10.0, entering.rotation().y(), 0.5);

        SampledBone root = middle.bone("root");
        assertNotNull(root);
        assertEquals(75.0, root.rotation().y(), 0.5);
        assertEquals(2.0, root.position().y(), 1.0E-4, "a channel the target omits fades towards no offset");
        assertEquals(1.0F, root.scale().y(), 1.0E-4);
    }

    @Test
    void theBlendKeepsTheTargetIdentityAndItsIssues() {
        SamplingIssue issue = SamplingIssue.unsupportedInterpolation("root", ChannelKind.ROTATION, "step");
        WeaponPose from = pose("from", bone("root", new Vec3(0.0F, 10.0F, 0.0F), null, null));
        WeaponPose to = new WeaponPose("target", 0.75,
                Map.of("root", bone("root", new Vec3(0.0F, 20.0F, 0.0F), null, null)), List.of(issue));

        WeaponPose middle = WeaponPoseBlend.blend(from, to, 0.5F);
        assertEquals("target", middle.animationName());
        assertEquals(0.75, middle.timeSeconds(), 1.0E-9);
        assertEquals(List.of(issue), middle.issues(), "a fade must not swallow the sampling diagnostics of the clip");
        assertFalse(middle.isEmpty());
    }

    @Test
    void anIdenticalRotationIsReusedInsteadOfReDerived() {
        Vec3 rotation = new Vec3(3.0F, -7.0F, 11.0F);
        WeaponPose from = pose("from", bone("root", rotation, null, null));
        WeaponPose to = pose("to", bone("root", rotation, null, null));

        SampledBone blended = WeaponPoseBlend.blend(from, to, 0.5F).bone("root");
        assertNotNull(blended);
        assertSame(rotation, blended.rotation(), "an unchanged rotation is not needlessly re-derived");
    }

    // ---------------------------------------------------------------- helpers

    private static WeaponPose pose(String name, SampledBone... bones) {
        Map<String, SampledBone> map = new LinkedHashMap<>();
        for (SampledBone bone : bones) {
            map.put(bone.boneName(), bone);
        }
        return new WeaponPose(name, 0.0, map, List.of());
    }

    private static SampledBone bone(String name, Vec3 rotation, Vec3 position, Vec3 scale) {
        return new SampledBone(name, rotation, position, scale);
    }
}
