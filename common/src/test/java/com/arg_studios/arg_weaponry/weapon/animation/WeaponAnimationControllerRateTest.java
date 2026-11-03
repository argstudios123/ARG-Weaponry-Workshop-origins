package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The base clip's render-time sampling: the sub-tick offset must move through the clip at the same rate the tick clock does.
 *
 * <p>A reload, an inspection or a draw is retimed onto a configured duration, so its {@code playbackRate} is routinely not
 * {@code 1}. Advancing the clock by {@code delta * rate} while sampling {@code elapsed + offset} makes the clip play at one
 * speed inside a tick and jump at every tick boundary; the assert here is that consecutive frames - including the pair that
 * straddles a boundary - advance by exactly {@code unitsPerSecond * rate * frameTime}.</p>
 */
class WeaponAnimationControllerRateTest {

    /** The ramp covers ten model units per second of clip time. */
    private static final double UNITS_PER_SECOND = 10.0;

    private static final double TICK = 0.05;

    @Test
    void theSamplingOffsetIsScaledByThePlaybackRate() {
        WeaponAnimationController unit = controller();
        WeaponAnimationController fast = controller();

        unit.tick(TICK, 1.0);
        fast.tick(TICK, 2.0);

        assertEquals(TICK, unit.elapsedSeconds(), 1.0E-9, "a 1x clip advances by the tick length");
        assertEquals(2 * TICK, fast.elapsedSeconds(), 1.0E-9, "a 2x clip advances by twice the tick length");

        assertEquals(UNITS_PER_SECOND * (TICK + TICK), rootY(unit.poseAt(TICK)), 1.0E-6,
                "the offset of a 1x clip is one tick of clip time");
        assertEquals(UNITS_PER_SECOND * (2 * TICK + 2 * TICK), rootY(fast.poseAt(TICK)), 1.0E-6,
                "and the offset of a 2x clip is two ticks of clip time, because that is what the clock will cover");
    }

    @Test
    void framesStayContinuousAcrossATickBoundaryAtAnyRate() {
        for (double rate : new double[]{0.25, 0.5, 1.0, 1.5, 2.0, 4.0}) {
            assertUniformFrames(rate);
        }
    }

    @Test
    void aStoppedControllerForgetsItsRate() {
        WeaponAnimationController controller = controller();
        controller.tick(TICK, 3.0);
        assertEquals(3.0, controller.playbackRate(), 1.0E-9);

        controller.stop();

        assertEquals(1.0, controller.playbackRate(), 1.0E-9,
                "the next clip starts at its authored speed until its owner asks for something else");
    }

    /**
     * @param rate the rate the controller plays at
     */
    private static void assertUniformFrames(double rate) {
        WeaponAnimationController controller = controller();
        // The rate is part of the controller's state and is set by a tick, the way the view controller sets it every tick
        // before a frame samples. Settle one tick so the first measured frame is already on the requested time base.
        controller.tick(TICK, rate);
        int framesPerTick = 4;
        List<Double> frames = new ArrayList<>();
        for (int tick = 0; tick < 20; tick++) {
            for (int frame = 0; frame < framesPerTick; frame++) {
                frames.add(rootY(controller.poseAt(frame * TICK / framesPerTick)));
            }
            controller.tick(TICK, rate);
        }

        double expected = UNITS_PER_SECOND * rate * TICK / framesPerTick;
        assertTrue(expected > 0.0);
        for (int index = 1; index < frames.size(); index++) {
            double step = frames.get(index) - frames.get(index - 1);
            assertEquals(expected, step, expected * 0.01,
                    "frame " + index + " at rate " + rate + " covered " + step + " units instead of " + expected);
        }
    }

    private static double rootY(WeaponPose pose) {
        return pose == null || pose.isEmpty() ? Double.NaN : pose.bone("root").position().y();
    }

    /** A one-minute ramp, so no test runs into the loop seam. */
    private static WeaponAnimationController controller() {
        WeaponAnimationController controller = new WeaponAnimationController();
        controller.play(new BedrockAnimation("anim.test.ramp", 60.0, LoopMode.LOOP,
                Map.of("root", new BoneAnimation("root", null, new AnimationChannel.Keyframed(List.of(
                        Keyframe.plain(0.0, new Vec3(0.0F, 0.0F, 0.0F)),
                        Keyframe.plain(60.0, new Vec3(0.0F, 600.0F, 0.0F)))), null))));
        return controller;
    }
}
