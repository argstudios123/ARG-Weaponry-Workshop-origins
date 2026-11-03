package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The pose overlay that merges a movement layer into a base pose.
 *
 * <p>The contract under test is the one that makes the shipped walking clips safe: only the bones and channels the layer
 * animates are touched, everything else keeps the base value, and scale is never taken from the layer.</p>
 */
class WeaponPoseLayersTest {

    @Test
    void aChannelTheLayerAnimatesReplacesTheBaseChannel() {
        WeaponPose base = pose("idle", bone("root", Vec3.ZERO, new Vec3(0.0F, 0.0F, 0.0F), null));
        WeaponPose layer = pose("walk", bone("root", Vec3.ZERO, new Vec3(0.0F, 0.5F, 0.0F), null));

        WeaponPose merged = WeaponPoseLayers.overlay(base, layer, 1.0F);

        assertEquals(0.5, (double) merged.bone("root").position().y(), 1.0E-9);
    }

    @Test
    void theWeightBlendsBetweenTheBaseAndTheLayer() {
        WeaponPose base = pose("idle", bone("root", null, new Vec3(0.0F, 0.0F, 0.0F), null));
        WeaponPose layer = pose("walk", bone("root", null, new Vec3(0.0F, 1.0F, 0.0F), null));

        assertEquals(0.25, WeaponPoseLayers.overlay(base, layer, 0.25F).bone("root").position().y(), 1.0E-9);
    }

    @Test
    void bonesTheLayerDoesNotAnimateKeepTheirBasePose() {
        WeaponPose base = pose("idle",
                bone("root", null, Vec3.ZERO, null),
                bone("right_hand", null, new Vec3(1.0F, 2.0F, 3.0F), null));
        WeaponPose layer = pose("walk", bone("root", null, new Vec3(0.0F, 4.0F, 0.0F), null));

        WeaponPose merged = WeaponPoseLayers.overlay(base, layer, 1.0F);

        assertEquals(new Vec3(1.0F, 2.0F, 3.0F), merged.bone("right_hand").position(),
                "a root-only walking clip must preserve the weapon's own hand pose");
    }

    @Test
    void channelsTheLayerDoesNotAnimateKeepTheirBaseValue() {
        WeaponPose base = pose("idle", bone("root", new Vec3(3.0F, 0.0F, 0.0F), new Vec3(0.0F, 0.0F, 0.0F), null));
        WeaponPose layer = pose("walk", bone("root", null, new Vec3(0.0F, 1.0F, 0.0F), null));

        WeaponPose merged = WeaponPoseLayers.overlay(base, layer, 1.0F);

        assertEquals(new Vec3(3.0F, 0.0F, 0.0F), merged.bone("root").rotation());
        assertEquals(1.0, (double) merged.bone("root").position().y(), 1.0E-9);
    }

    @Test
    void aScaleChannelIsNeverTakenFromTheLayer() {
        WeaponPose base = pose("idle", bone("root", null, Vec3.ZERO, new Vec3(1.0F, 1.0F, 1.0F)));
        WeaponPose layer = pose("walk", bone("root", null, null, new Vec3(2.0F, 2.0F, 2.0F)));

        WeaponPose merged = WeaponPoseLayers.overlay(base, layer, 1.0F);

        assertEquals(new Vec3(1.0F, 1.0F, 1.0F), merged.bone("root").scale(),
                "no movement animation may rescale the weapon");
    }

    @Test
    void aScaleOnlyLayerBoneIsIgnoredEntirely() {
        WeaponPose base = pose("idle", bone("root", null, Vec3.ZERO, null));

        WeaponPose merged = WeaponPoseLayers.overlay(base, pose("walk",
                bone("root", null, null, new Vec3(2.0F, 2.0F, 2.0F))), 1.0F);

        assertSame(base, merged, "nothing applicable means the base pose is returned untouched");
    }

    @Test
    void aNullLayerOrZeroWeightReturnsTheBasePose() {
        WeaponPose base = pose("idle", bone("root", null, Vec3.ZERO, null));

        assertSame(base, WeaponPoseLayers.overlay(base, null, 1.0F));
        assertSame(base, WeaponPoseLayers.overlay(base, pose("walk", bone("root", null, Vec3.ZERO, null)), 0.0F));
    }

    @Test
    void aLayerBoneTheBaseDoesNotHaveIsAdded() {
        WeaponPose base = pose("idle", bone("right_hand", null, Vec3.ZERO, null));

        WeaponPose merged = WeaponPoseLayers.overlay(base,
                pose("walk", bone("root", null, new Vec3(0.0F, 2.0F, 0.0F), null)), 1.0F);

        assertNotNull(merged.bone("root"));
        assertEquals(2.0, (double) merged.bone("root").position().y(), 1.0E-9);
        assertTrue(merged.hasBone("right_hand"));
    }

    @Test
    void aChannelTheBaseDoesNotAnimateFadesInFromTheIdentity() {
        // The base clip animates the hand's position only; the layer animates the hand's rotation only. The overlay must
        // start that rotation at the authored pose (identity) and reach the layer value only at full weight.
        WeaponPose base = pose("idle", bone("left_hand", null, new Vec3(1.0F, 2.0F, 3.0F), null));
        WeaponPose layer = pose("aim", bone("left_hand", new Vec3(0.0F, 0.0F, 90.0F), null, null));

        SampledBone quarter = WeaponPoseLayers.overlay(base, layer, 0.25F).bone("left_hand");
        double fromIdentity = WeaponPoseBlend.angleDegrees(Vec3.ZERO, quarter.rotation());
        assertEquals(22.5, fromIdentity, 1.0E-3,
                "a quarter-weight layer must apply a quarter of the rotation, never the whole of it");
        assertEquals(new Vec3(1.0F, 2.0F, 3.0F), quarter.position(),
                "the channel the layer does not animate keeps the base value");

        SampledBone full = WeaponPoseLayers.overlay(base, layer, 1.0F).bone("left_hand");
        assertEquals(90.0, WeaponPoseBlend.angleDegrees(Vec3.ZERO, full.rotation()), 1.0E-3);
        assertEquals(new Vec3(1.0F, 2.0F, 3.0F), full.position());
    }

    @Test
    void aPositionTheBaseDoesNotAnimateFadesInFromZero() {
        WeaponPose base = pose("idle", bone("right_hand", new Vec3(1.0F, 0.0F, 0.0F), null, null));
        WeaponPose layer = pose("walk", bone("right_hand", null, new Vec3(0.0F, 4.0F, 0.0F), null));

        SampledBone quarter = WeaponPoseLayers.overlay(base, layer, 0.25F).bone("right_hand");

        assertEquals(1.0, (double) quarter.position().y(), 1.0E-4,
                "an offset the base does not animate fades in from zero with the layer weight");
        assertEquals(new Vec3(1.0F, 0.0F, 0.0F), quarter.rotation(),
                "the rotation the layer does not animate keeps the base value");
    }

    @Test
    void handRotationsBlendOnTheShortestArcAcrossTheWrap() {
        // The shipped hand bones are authored near -180 degrees of roll, so two clips can describe rotations that are two
        // degrees apart as (-179) and (179). Component-wise interpolation would travel 358 degrees the wrong way through
        // the rest pose; the shortest arc is two degrees through 180.
        WeaponPose base = pose("idle", bone("left_hand", new Vec3(0.0F, 0.0F, -179.0F), null, null));
        WeaponPose layer = pose("reload", bone("left_hand", new Vec3(0.0F, 0.0F, 179.0F), null, null));

        Vec3 half = WeaponPoseLayers.overlay(base, layer, 0.5F).bone("left_hand").rotation();

        double distanceToShortPath = WeaponPoseBlend.angleDegrees(half, new Vec3(0.0F, 0.0F, 180.0F));
        assertTrue(distanceToShortPath < 1.0,
                "the blend must pass through 180 degrees, not through the rest pose; was " + half
                        + " (" + distanceToShortPath + " degrees away from the short path)");
    }

    @Test
    void thePoseNameStaysTheBaseOneSoDiagnosticsKeepNamingTheBaseClip() {
        WeaponPose base = pose("anim.model.idle", bone("root", null, Vec3.ZERO, null));

        WeaponPose merged = WeaponPoseLayers.overlay(base,
                pose("anim.model.walk_forward", bone("root", null, new Vec3(0.0F, 1.0F, 0.0F), null)), 1.0F);

        assertEquals("anim.model.idle", merged.animationName());
    }

    // ---------------------------------------------------------------- helpers

    private static WeaponPose pose(String name, SampledBone... bones) {
        Map<String, SampledBone> map = new LinkedHashMap<>();
        for (SampledBone bone : bones) {
            map.put(bone.boneName(), bone);
        }
        return new WeaponPose(name, 0.0, map, java.util.List.of());
    }

    private static SampledBone bone(String name, Vec3 rotation, Vec3 position, Vec3 scale) {
        return new SampledBone(name, rotation, position, scale);
    }
}
