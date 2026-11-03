package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The locomotion layer's render-time behaviour: one timeline across a tick boundary, no phase stall on a direction change,
 * live controller-to-controller blending and a phase that survives a weapon switch.
 *
 * <h2>Why the assertions are on frames and not on ticks</h2>
 * <p>Every rule in this file is about what a <em>frame</em> sees. The previous suite verified tick-level sequences - state
 * names, clock values - and was therefore green while the rendered pose stepped at every tick boundary, because the clock
 * advanced at {@code delta * speedScale} while the sampling offset did not. The clips used here are linear ramps, so the
 * sampled root position is a direct read-out of the animation time: a step in the value is a step in the timeline.</p>
 */
class MovementLayerTimingTest {

    private static final double TICK = 0.05;

    /** Frames sampled inside one tick; a real client renders between four and forty. */
    private static final int FRAMES_PER_TICK = 4;

    /** Ten model units per second of clip time: {@code y} is the clip's own time, scaled by ten. */
    private static final double UNITS_PER_SECOND = 10.0;

    @Test
    void theFramesInsideATickAdvanceAsFarAsTheTickBoundaryDoes() {
        assertUniformFrames(0.5);
        assertUniformFrames(1.0);
        assertUniformFrames(2.0);
    }

    @Test
    void aDirectionChangeDoesNotStallTheSharedPhase() {
        MovementLayer layer = rampWalkLayer();
        for (int tick = 0; tick < 20; tick++) {
            layer.update(walkingForward(1.0), TICK);
        }
        double before = rootY(layer, 0.0);

        for (int tick = 0; tick < 3; tick++) {
            layer.update(strafeRight(1.0), TICK);
        }

        assertEquals(LocomotionState.WALKING_RIGHT, layer.state());
        assertEquals(before + 3 * UNITS_PER_SECOND * TICK, rootY(layer, 0.0), 1.0E-6,
                "the shared phase kept running through the direction change; a cross-fade between two samples of the "
                        + "same controller would have frozen it for two ticks and then caught up");
    }

    @Test
    void walkingWhileAimingStaysInTheWalkingController() {
        MovementLayer layer = rampWalkLayer();
        for (int tick = 0; tick < 20; tick++) {
            layer.update(walkingForward(1.0), TICK, true, false, false);
        }
        double before = rootY(layer, 0.0);

        for (int tick = 0; tick < 3; tick++) {
            layer.update(walkingForward(1.0), TICK, true, true, false);
        }

        assertEquals(LocomotionState.WALKING_AIMING, layer.state());
        assertEquals(before + 3 * UNITS_PER_SECOND * TICK, rootY(layer, 0.0), 1.0E-6,
                "the pack authors no walk_aiming clip, so the aiming walk samples the very same blend and must not fade "
                        + "into itself");
    }

    @Test
    void theOutgoingControllerKeepsPlayingWhileTheFadeRuns() {
        MovementLayer layer = walkAndRunLayer();
        for (int tick = 0; tick < 20; tick++) {
            layer.update(walkingForward(1.0), TICK);
        }
        double phaseBefore = layer.walkPhase();

        layer.update(sprinting(1.0), TICK);
        assertEquals(LocomotionState.PREPARE_RUNNING, layer.state());
        double phaseAfterEntry = layer.walkPhase();
        assertTrue(phaseAfterEntry > phaseBefore,
                "the outgoing walk controller advances on the tick the fade starts (" + phaseBefore + " -> "
                        + phaseAfterEntry + ")");

        layer.update(sprinting(1.0), TICK);
        assertTrue(layer.walkPhase() > phaseAfterEntry,
                "and it keeps advancing for as long as it is the pose being faded out");
    }

    @Test
    void aSprintJumpHoldsTheRunningPhaseAndResumesItOnLanding() {
        MovementLayer layer = walkAndRunLayer();
        for (int tick = 0; tick < 8; tick++) {
            layer.update(sprinting(1.0), TICK);
        }
        assertEquals(LocomotionState.RUNNING, layer.state());
        double takeOff = rootY(layer, 0.0);
        double runTimeAtTakeOff = layer.runTime();

        layer.update(airborneSprinting(), TICK);
        assertEquals(LocomotionState.OFF_GROUND_SPRINTING, layer.state());
        assertEquals(takeOff, rootY(layer, 0.0), 1.0E-6,
                "take-off continues the running cycle instead of cutting to the single-frame run_hold pose");

        for (int tick = 0; tick < 5; tick++) {
            layer.update(airborneSprinting(), TICK);
            assertEquals(takeOff, rootY(layer, 0.0), 1.0E-6, "and the phase is held for the whole airtime");
        }

        layer.update(sprinting(1.0), TICK);
        assertEquals(LocomotionState.RUNNING, layer.state());
        assertEquals(runTimeAtTakeOff + TICK, layer.runTime(), 1.0E-9, "the landing resumes exactly that phase");
        assertEquals(takeOff + UNITS_PER_SECOND * TICK, rootY(layer, 0.0), 1.0E-6,
                "so the first grounded frame continues the airborne pose instead of snapping to a new one");
    }

    @Test
    void aTimelineCanBeContinuedByAnotherLayer() {
        MovementLayer outgoing = rampWalkLayer();
        for (int tick = 0; tick < 20; tick++) {
            outgoing.update(walkingForward(1.0), TICK);
        }

        MovementLayer incoming = rampWalkLayer();
        incoming.restore(outgoing.timeline());

        assertEquals(outgoing.state(), incoming.state(), "the state machine continues in the same state");
        assertEquals(outgoing.walkPhase(), incoming.walkPhase(), 0.0, "with the same phase");
        assertEquals(outgoing.weight(), incoming.weight(), 0.0, "and the same overlay weight, so the walk does not fade in");

        outgoing.update(walkingForward(1.0), TICK);
        incoming.update(walkingForward(1.0), TICK);
        assertEquals(rootY(outgoing, 0.0), rootY(incoming, 0.0), 1.0E-9,
                "and both advance identically from there, which is what makes a weapon switch invisible");
    }

    // ---------------------------------------------------------------- frame sampling

    /**
     * Samples one tick as {@link #FRAMES_PER_TICK} frames and asserts that every frame advances the pose by the same amount,
     * including the frame that crosses the tick boundary.
     *
     * @param rate the playback rate the player's speed maps to
     */
    private static void assertUniformFrames(double rate) {
        MovementLayer layer = rampWalkLayer();
        MovementState walking = walkingForward(rate);
        for (int tick = 0; tick < 40; tick++) {
            layer.update(walking, TICK);
        }

        List<Double> frames = new ArrayList<>();
        for (int tick = 0; tick < 6; tick++) {
            for (int frame = 0; frame < FRAMES_PER_TICK; frame++) {
                frames.add(rootY(layer, frame * TICK / FRAMES_PER_TICK));
            }
            layer.update(walking, TICK);
        }

        double expected = UNITS_PER_SECOND * rate * TICK / FRAMES_PER_TICK;
        assertTrue(expected > 0.0);
        for (int index = 1; index < frames.size(); index++) {
            double step = frames.get(index) - frames.get(index - 1);
            assertEquals(expected, step, expected * 0.01,
                    "frame " + index + " of a " + rate + "x walk covered " + step + " units instead of " + expected
                            + "; a tick boundary must not change the speed of the pose");
        }
    }

    // ---------------------------------------------------------------- layer and clip helpers

    private static double rootY(MovementLayer layer, double offsetSeconds) {
        WeaponPose pose = layer.pose(offsetSeconds);
        return pose == null ? Double.NaN : pose.bone("root").position().y();
    }

    private static MovementState walkingForward(double rate) {
        return MovementState.of(1.0, 0.0, false, false,
                new LocomotionIntent(true, false, true, false, false, false, false, true, rate));
    }

    private static MovementState strafeRight(double rate) {
        return MovementState.of(0.0, 1.0, false, false,
                new LocomotionIntent(true, false, false, false, false, true, false, true, rate));
    }

    private static MovementState sprinting(double rate) {
        return MovementState.of(1.0, 0.0, true, false,
                new LocomotionIntent(true, true, true, false, false, false, false, true, rate));
    }

    private static MovementState airborneSprinting() {
        return MovementState.of(0.0, 0.0, true, true,
                new LocomotionIntent(false, true, false, false, false, false, false, true, 1.0));
    }

    /**
     * Four walking clips that share one linear ramp, so {@code y} reads the phase directly and a direction change only
     * changes {@code x}. The clips are a minute long, so no test runs into the loop seam.
     */
    private static MovementLayer rampWalkLayer() {
        MovementLayer layer = new MovementLayer();
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, rampClip(MovementLayer.WALK_FORWARD, 0.0F));
        clips.put(MovementLayer.WALK_BACKWARD, rampClip(MovementLayer.WALK_BACKWARD, 1.0F));
        clips.put(MovementLayer.WALK_LEFT, rampClip(MovementLayer.WALK_LEFT, -1.0F));
        clips.put(MovementLayer.WALK_RIGHT, rampClip(MovementLayer.WALK_RIGHT, 1.0F));
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips));
        return layer;
    }

    /** The walking clips above plus a running sequence, for the walk-to-run and sprint-jump tests. */
    private static MovementLayer walkAndRunLayer() {
        MovementLayer layer = new MovementLayer();
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, rampClip(MovementLayer.WALK_FORWARD, 0.0F));
        clips.put(MovementLayer.RUN_START, new BedrockAnimation(MovementLayer.RUN_START, 0.2, LoopMode.ONCE,
                Map.of("root", new BoneAnimation("root", null, ramp(0.0F, 60.0, 600.0F), null))));
        clips.put(MovementLayer.RUN, new BedrockAnimation(MovementLayer.RUN, 60.0, LoopMode.LOOP,
                Map.of("root", new BoneAnimation("root", null, ramp(0.0F, 60.0, 600.0F), null))));
        clips.put(MovementLayer.RUN_HOLD, new BedrockAnimation(MovementLayer.RUN_HOLD, 0.04, LoopMode.LOOP,
                Map.of("root", new BoneAnimation("root", null,
                        AnimationChannel.Constant.ofVector(new Vec3(0.0F, -1.0F, 0.0F)), null))));
        clips.put(MovementLayer.RUN_END, new BedrockAnimation(MovementLayer.RUN_END, 0.2, LoopMode.ONCE,
                Map.of("root", new BoneAnimation("root", null,
                        AnimationChannel.Constant.ofVector(new Vec3(0.0F, 0.0F, 0.0F)), null))));
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips));
        return layer;
    }

    private static BedrockAnimation rampClip(String suffix, float x) {
        return new BedrockAnimation("anim.model." + suffix, 60.0, LoopMode.LOOP,
                Map.of("root", new BoneAnimation("root", null, ramp(x, 60.0, 600.0F), null)));
    }

    private static AnimationChannel ramp(float x, double length, float endY) {
        return new AnimationChannel.Keyframed(List.of(
                Keyframe.plain(0.0, new Vec3(x, 0.0F, 0.0F)),
                Keyframe.plain(length, new Vec3(x, endY, 0.0F))));
    }
}
