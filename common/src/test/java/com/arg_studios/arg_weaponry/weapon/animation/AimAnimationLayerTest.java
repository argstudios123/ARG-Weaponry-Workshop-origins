package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The aiming animation layer: {@code aim_in}, {@code aim_out}, the held pose and the priority gate.
 *
 * <p>The clips are built here rather than read from a content pack, so the layer's rules - play once, hold the last frame,
 * cross-fade, never replay while aiming is held, never overwrite a higher-priority action - are verified without a game.</p>
 */
class AimAnimationLayerTest {

    private static final double TICK = 0.05;

    @Test
    void withBothClipsTheWeaponRaisesOnceThenHoldsTheLastFrame() {
        AimAnimationLayer layer = new AimAnimationLayer();
        layer.bind(document(raiseClip(), lowerClip()), "anim.test.aim_in", "anim.test.aim_out");

        aimFor(layer, 0.4);
        assertEquals(AimAnimationLayer.Phase.HOLD, layer.phase(), "aim_in played once and its end is held");
        assertEquals(1.0F, layer.weight(), 1.0E-6);

        WeaponPose held = layer.pose(0.0);
        assertNotNull(held);
        assertEquals(4.0, held.bone("root").position().y(), 1.0E-4, "the last authored frame is what is held");

        aimFor(layer, 2.0);
        assertEquals(AimAnimationLayer.Phase.HOLD, layer.phase(), "a held aim never replays aim_in");
        assertEquals(held.bone("root").position().y(), layer.pose(0.0).bone("root").position().y(), 1.0E-6);
    }

    @Test
    void releasingPlaysAimOutOnceAndThenFadesBackToTheBasePose() {
        AimAnimationLayer layer = new AimAnimationLayer();
        layer.bind(document(raiseClip(), lowerClip()), "anim.test.aim_in", "anim.test.aim_out");
        aimFor(layer, 0.4);

        layer.update(false, true, TICK);
        assertEquals(AimAnimationLayer.Phase.OUT, layer.phase(), "releasing aim starts aim_out");

        for (int tick = 0; tick < 5; tick++) {
            layer.update(false, true, TICK);
        }
        assertEquals(AimAnimationLayer.Phase.NONE, layer.phase(), "aim_out is a one-shot clip");

        for (int tick = 0; tick < 10; tick++) {
            layer.update(false, true, TICK);
        }
        assertEquals(0.0F, layer.weight(), 1.0E-6);
        assertNull(layer.pose(0.0), "a faded-out layer contributes nothing");
    }

    @Test
    void anAimInWithoutAnAimOutFadesTheHeldPoseBackInstead() {
        AimAnimationLayer layer = new AimAnimationLayer();
        layer.bind(document(raiseClip(), null), "anim.test.aim_in", null);
        aimFor(layer, 0.4);

        layer.update(false, true, TICK);
        assertEquals(AimAnimationLayer.Phase.NONE, layer.phase(),
                "without aim_out there is nothing to play, so the layer fades out from what it showed");
        assertNotNull(layer.pose(0.0), "the fade starts from the held aim pose, not from an abrupt cut");

        for (int tick = 0; tick < 10; tick++) {
            layer.update(false, true, TICK);
        }
        assertNull(layer.pose(0.0));
    }

    @Test
    void anAimOutWithoutAnAimInContributesNothingWhileAiming() {
        AimAnimationLayer layer = new AimAnimationLayer();
        assertTrue(layer.bind(document(raiseClip(), lowerClip()), null, "anim.test.aim_out"),
                "an aim_out alone is a valid configuration");
        assertFalse(layer.isEmpty());

        aimFor(layer, 0.4);
        assertEquals(0.0F, layer.weight(), 1.0E-6, "there is no aim pose to show, so the default transition applies");
        assertNull(layer.pose(0.0));
    }

    @Test
    void aWeaponWithoutAimingClipsLeavesTheLayerInactive() {
        AimAnimationLayer layer = new AimAnimationLayer();

        assertFalse(layer.bind(document(raiseClip(), lowerClip()), null, null));
        assertTrue(layer.isEmpty());
        layer.update(true, true, TICK);
        assertNull(layer.pose(0.0), "the framework's placement and zoom transition remains the whole behaviour");
    }

    @Test
    void aHigherPriorityActionHidesTheLayerWithoutRestartingIt() {
        AimAnimationLayer layer = new AimAnimationLayer();
        layer.bind(document(raiseClip(), lowerClip()), "anim.test.aim_in", "anim.test.aim_out");
        aimFor(layer, 0.4);
        double heldY = layer.pose(0.0).bone("root").position().y();

        for (int tick = 0; tick < 5; tick++) {
            layer.update(true, false, TICK);
        }
        assertEquals(0.0F, layer.weight(), 1.0E-6, "a reload or a shot owns the pose while it plays");

        for (int tick = 0; tick < 5; tick++) {
            layer.update(true, true, TICK);
        }
        assertEquals(AimAnimationLayer.Phase.HOLD, layer.phase(), "the aim animation was never restarted");
        assertEquals(heldY, layer.pose(0.0).bone("root").position().y(), 1.0E-4);
        assertEquals(1.0F, layer.weight(), 1.0E-6);
    }

    @Test
    void aScaleChannelInAnAimingClipNeverReachesThePose() {
        AimAnimationLayer layer = new AimAnimationLayer();
        BedrockAnimation scaling = new BedrockAnimation("aim_in", 0.3, LoopMode.LOOP, Map.of("root",
                new BoneAnimation("root", null, null, AnimationChannel.Constant.ofVector(new Vec3(3.0F, 3.0F, 3.0F)))));
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "aiming"), "", Map.of("aim_in", scaling)),
                "aim_in", null);

        aimFor(layer, 0.2);
        assertNull(layer.pose(0.0), "an aiming clip that only scales is dropped instead of resizing the weapon");
    }

    @Test
    void theClipTimeIsFrameRateIndependent() {
        AimAnimationLayer fast = new AimAnimationLayer();
        AimAnimationLayer slow = new AimAnimationLayer();
        fast.bind(document(raiseClip(), lowerClip()), "anim.test.aim_in", "anim.test.aim_out");
        slow.bind(document(raiseClip(), lowerClip()), "anim.test.aim_in", "anim.test.aim_out");

        for (int tick = 0; tick < 60; tick++) {
            fast.update(true, true, 1.0 / 60.0);
        }
        for (int tick = 0; tick < 4; tick++) {
            slow.update(true, true, 0.25);
        }

        assertEquals(AimAnimationLayer.Phase.HOLD, fast.phase());
        assertEquals(AimAnimationLayer.Phase.HOLD, slow.phase());
        assertEquals(fast.weight(), slow.weight(), 1.0E-6);
        assertEquals(fast.pose(0.0).bone("root").position().y(),
                slow.pose(0.0).bone("root").position().y(), 1.0E-6);
    }

    // ---------------------------------------------------------------- helpers

    private static void aimFor(AimAnimationLayer layer, double seconds) {
        int ticks = (int) Math.round(seconds / TICK);
        for (int tick = 0; tick < ticks; tick++) {
            layer.update(true, true, TICK);
        }
    }

    private static AnimationFile document(BedrockAnimation aimIn, BedrockAnimation aimOut) {
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        if (aimIn != null) {
            clips.put(aimIn.name(), aimIn);
        }
        if (aimOut != null) {
            clips.put(aimOut.name(), aimOut);
        }
        return new AnimationFile(new ResourceId("arg_pack", "aiming"), "", clips);
    }

    /** A raise from rest to {@code y = 4} over 0.3 s. */
    private static BedrockAnimation raiseClip() {
        return clip("anim.test.aim_in", 0.3, 0.0F, 4.0F);
    }

    /** A return from {@code y = 4} to rest over 0.2 s. */
    private static BedrockAnimation lowerClip() {
        return clip("anim.test.aim_out", 0.2, 4.0F, 0.0F);
    }

    private static BedrockAnimation clip(String name, double length, float startY, float endY) {
        AnimationChannel position = new AnimationChannel.Keyframed(List.of(
                Keyframe.plain(0.0, new Vec3(0.0F, startY, 0.0F)),
                Keyframe.plain(length, new Vec3(0.0F, endY, 0.0F))));
        return new BedrockAnimation(name, length, LoopMode.LOOP, Map.of("root",
                new BoneAnimation("root", null, position, null)));
    }
}
