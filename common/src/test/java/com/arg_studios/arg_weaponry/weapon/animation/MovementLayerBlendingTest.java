package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The movement layer's blending: smoothed direction weights, a phase that is never restarted, cross-faded run stages and
 * an overlay gate that keeps the timelines alive while an action owns the pose.
 *
 * <p>These are the rules that turn the previously abrupt movement animation into a blending one, so each of them is
 * asserted on the pose that a caller would actually render.</p>
 */
class MovementLayerBlendingTest {

    private static final double TICK = 0.05;

    // ---------------------------------------------------------------- helpers

    /**
     * @return the root contribution the renderer would see, i.e. the layer pose blended into a base pose that animates the
     *         same bone - which is what makes the layer weight visible in the result
     */
    private static double renderedY(MovementLayer layer) {
        WeaponPose base = new WeaponPose("base", 0.0, Map.of("root", new SampledBone("root", null,
                new Vec3(0.0F, 0.0F, 0.0F), null)), List.of());
        WeaponPose merged = WeaponPoseLayers.overlay(base, layer.pose(0.0), layer.weight());
        return merged.bone("root").position().y();
    }

    @Test
    void aDirectionChangeMovesThroughIntermediatePoses() {
        MovementLayer layer = walkLayer();
        feed(layer, new MovementState(1.0, 0.0, false));
        assertEquals(1.0, layer.pose(0.0).bone("root").position().y(), 1.0E-6);

        layer.update(new MovementState(0.0, 1.0, false), TICK);
        // The pose is sampled part-way through the tick, because that is what a frame is: offset 0 is the instant the tick
        // resolved, and the layer now interpolates between the direction weights at the start of the tick and the ones it
        // resolved, so the change is spread over the frames instead of landing on the boundary.
        WeaponPose first = layer.pose(0.6 * TICK);

        double x = first.bone("root").position().x();
        double y = first.bone("root").position().y();
        assertTrue(x > 0.0 && x < 1.0, "the new direction is not switched to in one frame");
        assertTrue(y > 0.0 && y < 1.0, "the old direction is not dropped in one frame either");

        for (int tick = 0; tick < 16; tick++) {
            layer.update(new MovementState(0.0, 1.0, false), TICK);
        }
        assertEquals(1.0, layer.pose(0.0).bone("root").position().x(), 1.0E-6);
        assertEquals(0.0, layer.pose(0.0).bone("root").position().y(), 1.0E-6);
    }

    @Test
    void aDirectionChangeDoesNotRestartTheDestinationClip() {
        MovementLayer layer = phaseLayer();

        // Walk forward long enough that the shared phase is well past the start of the cycle.
        for (int tick = 0; tick < 12; tick++) {
            layer.update(new MovementState(1.0, 0.0, false), TICK);
        }
        double phaseBefore = layer.walkPhase();
        assertTrue(phaseBefore > 0.3, "the walk cycle is running");

        for (int tick = 0; tick < 4; tick++) {
            layer.update(new MovementState(0.0, 1.0, false), TICK);
        }

        assertTrue(layer.walkPhase() > phaseBefore, "the shared clock kept advancing");
        double x = layer.pose(0.0).bone("root").position().x();
        assertTrue(x > 1.0, "the lateral clip is sampled at the shared phase, not restarted at its first frame");
    }

    @Test
    void aDiagonalIsABlendOfBothAxes() {
        MovementLayer layer = walkLayer();
        feed(layer, MovementState.of(1.0, 1.0, false));

        WeaponPose pose = layer.pose(0.0);

        assertEquals(0.5, pose.bone("root").position().y(), 1.0E-6);
        assertEquals(0.5, pose.bone("root").position().x(), 1.0E-6);
    }

    @Test
    void aReversalIsContinuous() {
        MovementLayer layer = walkLayer();
        feed(layer, new MovementState(1.0, 0.0, false));

        double previous = renderedY(layer);
        int intermediateFrames = 0;
        for (int tick = 0; tick < 16; tick++) {
            layer.update(new MovementState(-1.0, 0.0, false), TICK);
            double current = renderedY(layer);
            assertTrue(Math.abs(current - previous) <= 1.2,
                    "no frame may cover more than 60% of the whole 2.0-unit reversal");
            if (current > -0.9 && current < 0.9) {
                intermediateFrames++;
            }
            previous = current;
        }
        assertEquals(-1.0, layer.pose(0.0).bone("root").position().y(), 1.0E-6);
        assertEquals(1.0F, layer.weight(), 1.0E-6);
        assertTrue(intermediateFrames >= 2,
                "the reversal passes through intermediate poses instead of switching clips (" + intermediateFrames + ")");
    }

    @Test
    void stoppingFadesTheWalkOutAndResetsThePhaseForTheNextStart() {
        MovementLayer layer = walkLayer();
        feed(layer, new MovementState(1.0, 0.0, false));

        layer.update(MovementState.STILL, TICK);
        assertTrue(layer.weight() < 1.0F);
        assertNotNull(layer.pose(0.0), "the pose is still blended while the layer fades out");

        for (int tick = 0; tick < 10; tick++) {
            layer.update(MovementState.STILL, TICK);
        }
        assertEquals(0.0F, layer.weight(), 1.0E-6);
        assertNull(layer.pose(0.0));

        // The phase resets when the next walk *starts*, which is when the reset is observable and when it matters.
        layer.update(new MovementState(1.0, 0.0, false), TICK);
        assertTrue(layer.walkPhase() <= TICK,
                "a new walk starts from the authored first frame, advanced by the tick it started on");
    }

    @Test
    void aHeldOverlayGateKeepsTheTimelinesAlive() {
        MovementLayer visible = walkLayer();
        MovementLayer hidden = walkLayer();
        MovementState walking = new MovementState(1.0, 0.0, false);

        for (int tick = 0; tick < 20; tick++) {
            visible.update(walking, TICK, true);
            hidden.update(walking, TICK, false);
        }

        assertEquals(0.0F, hidden.weight(), 0.0, "an action hides the overlay");
        assertTrue(Math.abs(visible.walkPhase() - hidden.walkPhase()) <= 2 * TICK,
                "but the walk cycle kept running behind it (" + visible.walkPhase() + " vs " + hidden.walkPhase()
                        + ")");

        hidden.update(walking, TICK, true);
        assertEquals(visible.pose(0.0).bone("root").position().y(),
                hidden.pose(0.0).bone("root").position().y(), 1.0E-9,
                "the pose is sampled at the preserved phase");

        for (int tick = 0; tick < 4; tick++) {
            visible.update(walking, TICK, true);
            hidden.update(walking, TICK, true);
        }
        assertEquals(1.0F, hidden.weight(), 1.0E-6, "the overlay fades back in");
        assertEquals(visible.pose(0.0).bone("root").position().y(),
                hidden.pose(0.0).bone("root").position().y(), 1.0E-9,
                "so the movement resumes where it was instead of restarting");
    }

    void walkingIntoRunningIsCrossFaded() {
        MovementLayer layer = combinedLayer();
        feed(layer, new MovementState(1.0, 0.0, false));
        assertEquals(1.0, layer.pose(0.0).bone("root").position().y(), 1.0E-6);

        layer.update(new MovementState(1.0, 0.0, true), TICK);

        assertEquals(LocomotionState.PREPARE_RUNNING, layer.state());
        double y = layer.pose(0.0).bone("root").position().y();
        assertTrue(y > 1.0 && y < 2.0, "the raise into the running pose is blended, not cut");
    }

    @Test
    void theRunningClockIsFrozenWhileAirborneAndResumesOnLanding() {
        MovementLayer layer = runLayer();
        MovementState sprinting = new MovementState(1.0, 0.0, true);
        for (int tick = 0; tick < 7; tick++) {
            layer.update(sprinting, TICK);
        }
        assertEquals(LocomotionState.RUNNING, layer.state());
        double runningTime = layer.runTime();

        MovementState airborne = new MovementState(0.0, 0.0, true, true);
        for (int tick = 0; tick < 6; tick++) {
            layer.update(airborne, TICK);
            assertEquals(LocomotionState.OFF_GROUND_SPRINTING, layer.state(),
                    "an airborne sprint holds the sprinting airborne state on every tick");
            assertEquals(runningTime, layer.runTime(), 0.0, "and the running clock is frozen, not restarted");
        }

        layer.update(new MovementState(1.0, 0.0, true), TICK);
        assertEquals(LocomotionState.RUNNING, layer.state(), "landing returns to run");
        assertTrue(layer.runTime() >= runningTime && layer.runTime() <= runningTime + 2 * TICK,
                "the cycle resumes at the phase it was frozen at (" + runningTime + " -> " + layer.runTime() + ")");
    }

    @Test
    void jumpingHoldsTheRunningPhaseInsteadOfSnappingToTheHoldPose() {
        MovementLayer layer = runLayer();
        MovementState sprinting = new MovementState(1.0, 0.0, true);
        // Eight ticks: run_start (0.2 s) plays out, run takes over and its own cross-fade has finished, so the measured pose
        // is the running cycle itself and not a fade position.
        for (int tick = 0; tick < 8; tick++) {
            layer.update(sprinting, TICK);
        }
        double before = layer.pose(0.0).bone("root").position().y();
        double runningTime = layer.runTime();

        layer.update(new MovementState(0.0, 0.0, true, true), TICK);
        assertEquals(before, layer.pose(0.0).bone("root").position().y(), 1.0E-4,
                "the first airborne frame continues the running phase; run_hold is not a point of the cycle, so fading to "
                        + "it would move the weapon by the distance between them");

        for (int tick = 0; tick < 4; tick++) {
            layer.update(new MovementState(0.0, 0.0, true, true), TICK);
        }
        assertEquals(before, layer.pose(0.0).bone("root").position().y(), 1.0E-4,
                "the running cycle is held at the phase the player left the ground with");
        assertEquals(runningTime, layer.runTime(), 0.0, "and its clock is frozen, not restarted");
    }

    @Test
    void aPackWithoutARunClipFallsBackToItsAuthoredHoldPose() {
        MovementLayer layer = holdOnlyRunLayer();
        MovementState sprinting = new MovementState(1.0, 0.0, true);
        for (int tick = 0; tick < 8; tick++) {
            layer.update(sprinting, TICK);
        }

        layer.update(new MovementState(0.0, 0.0, true, true), TICK);
        for (int tick = 0; tick < 4; tick++) {
            layer.update(new MovementState(0.0, 0.0, true, true), TICK);
        }

        assertEquals(1.0, layer.pose(0.0).bone("root").position().y(), 1.0E-4,
                "with nothing to hold the cycle at, the authored run_hold pose is what the pack asked for");
    }

    @Test
    void releasingSprintPlaysRunEndOnceAndThenReturnsToTheGroundState() {
        MovementLayer layer = runLayer();
        MovementState sprinting = new MovementState(1.0, 0.0, true);
        for (int tick = 0; tick < 6; tick++) {
            layer.update(sprinting, TICK);
        }

        layer.update(new MovementState(1.0, 0.0, false), TICK);
        assertEquals(LocomotionState.COMPLETE_RUNNING, layer.state());
        double first = layer.completeTime();

        for (int tick = 0; tick < 3; tick++) {
            layer.update(new MovementState(1.0, 0.0, false), TICK);
            assertEquals(LocomotionState.COMPLETE_RUNNING, layer.state(),
                    "run_end is played once, not restarted per tick");
        }
        assertTrue(layer.completeTime() > first, "run_end advances normally");

        for (int tick = 0; tick < 8; tick++) {
            layer.update(new MovementState(1.0, 0.0, false), TICK);
        }
        assertEquals(LocomotionState.WALKING, layer.state(), "and the walk continues afterwards");
    }

    // ---------------------------------------------------------------- helpers

    @Test
    void jumpingHoldsTheWalkStepInsteadOfFadingItOut() {
        MovementLayer layer = walkLayer();
        MovementState walking = new MovementState(1.0, 0.0, false);
        for (int tick = 0; tick < 16; tick++) {
            layer.update(walking, TICK);
        }
        double takeOffPhase = layer.walkPhase();

        MovementState airborne = new MovementState(0.0, 0.0, false, true);
        for (int tick = 0; tick < 10; tick++) {
            layer.update(airborne, TICK);
            assertEquals(1.0F, layer.weight(), 1.0E-6,
                    "an airborne player keeps the movement overlay instead of fading it out");
            assertEquals(takeOffPhase, layer.walkPhase(), 0.0,
                    "and the walk cycle is frozen at the step they left the ground with");
            assertNotNull(layer.pose(0.0), "the held step is still drawn while in the air");
        }

        for (int tick = 0; tick < 4; tick++) {
            layer.update(walking, TICK);
        }
        assertTrue(layer.walkPhase() > takeOffPhase, "landing resumes the same cycle");
        assertEquals(1.0, layer.pose(0.0).bone("root").position().y(), 1.0E-6,
                "and the direction is still the one the player was walking in");
    }

    @Test
    void aJumpDoesNotRestartTheWalkCycle() {
        MovementLayer settled = walkLayer();
        MovementLayer jumped = walkLayer();
        MovementState walking = new MovementState(1.0, 0.0, false);

        for (int tick = 0; tick < 16; tick++) {
            settled.update(walking, TICK);
            jumped.update(walking, TICK);
        }
        for (int tick = 0; tick < 6; tick++) {
            jumped.update(new MovementState(0.0, 0.0, false, true), TICK);
            settled.update(walking, TICK);
        }
        for (int tick = 0; tick < 6; tick++) {
            settled.update(walking, TICK);
            jumped.update(walking, TICK);
        }

        assertEquals(settled.walkPhase() - 6 * TICK, jumped.walkPhase(), 1.0E-9,
                "the jump freezes the cycle for exactly the airborne ticks and nothing else");
    }

    /** Feeds the layer enough ticks to reach its full weight. */
    private static void feed(MovementLayer layer, MovementState state) {
        for (int tick = 0; tick < 16; tick++) {
            layer.update(state, TICK);
        }
    }

    /** Four constant walk clips: {@code y} forward/backward, {@code x} right/left. */
    private static MovementLayer walkLayer() {
        MovementLayer layer = new MovementLayer();
        layer.bind(document(
                clip(MovementLayer.WALK_FORWARD, 0.8, constant(new Vec3(0.0F, 1.0F, 0.0F)), LoopMode.LOOP),
                clip(MovementLayer.WALK_BACKWARD, 0.8, constant(new Vec3(0.0F, -1.0F, 0.0F)), LoopMode.LOOP),
                clip(MovementLayer.WALK_LEFT, 0.8, constant(new Vec3(-1.0F, 0.0F, 0.0F)), LoopMode.LOOP),
                clip(MovementLayer.WALK_RIGHT, 0.8, constant(new Vec3(1.0F, 0.0F, 0.0F)), LoopMode.LOOP)));
        return layer;
    }

    /** Four walking clips whose value grows with the clip time, so the shared phase is observable in the pose. */
    private static MovementLayer phaseLayer() {
        MovementLayer layer = new MovementLayer();
        layer.bind(document(
                clip(MovementLayer.WALK_FORWARD, 0.8,
                        ramp(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(0.0F, 8.0F, 0.0F), 0.8), LoopMode.LOOP),
                clip(MovementLayer.WALK_BACKWARD, 0.8,
                        ramp(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(0.0F, -8.0F, 0.0F), 0.8), LoopMode.LOOP),
                clip(MovementLayer.WALK_LEFT, 0.8,
                        ramp(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(-8.0F, 0.0F, 0.0F), 0.8), LoopMode.LOOP),
                clip(MovementLayer.WALK_RIGHT, 0.8,
                        ramp(new Vec3(0.0F, 0.0F, 0.0F), new Vec3(8.0F, 0.0F, 0.0F), 0.8), LoopMode.LOOP)));
        return layer;
    }

    /**
     * The running sequence with authored values that differ per stage, so a restart or a snap is visible in the pose.
     */
    private static MovementLayer runLayer() {
        MovementLayer layer = new MovementLayer();
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.RUN_START, clip(MovementLayer.RUN_START, 0.2,
                constant(new Vec3(0.0F, 2.0F, 0.0F)), LoopMode.ONCE));
        clips.put(MovementLayer.RUN, clip(MovementLayer.RUN, 0.6,
                ramp(new Vec3(0.0F, 2.0F, 0.0F), new Vec3(0.0F, 4.0F, 0.0F), 0.6), LoopMode.LOOP));
        clips.put(MovementLayer.RUN_HOLD, clip(MovementLayer.RUN_HOLD, 0.04,
                constant(new Vec3(0.0F, 1.0F, 0.0F)), LoopMode.LOOP));
        clips.put(MovementLayer.RUN_END, clip(MovementLayer.RUN_END, 0.42,
                ramp(new Vec3(0.0F, 3.0F, 0.0F), new Vec3(0.0F, 0.0F, 0.0F), 0.42), LoopMode.ONCE));
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips));
        return layer;
    }

    /**
     * A running sequence without the {@code run} clip, so the authored {@code run_hold} pose is the only thing left to hold.
     */
    private static MovementLayer holdOnlyRunLayer() {
        MovementLayer layer = new MovementLayer();
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.RUN_START, clip(MovementLayer.RUN_START, 0.2,
                constant(new Vec3(0.0F, 2.0F, 0.0F)), LoopMode.ONCE));
        clips.put(MovementLayer.RUN_HOLD, clip(MovementLayer.RUN_HOLD, 0.04,
                constant(new Vec3(0.0F, 1.0F, 0.0F)), LoopMode.LOOP));
        clips.put(MovementLayer.RUN_END, clip(MovementLayer.RUN_END, 0.2,
                constant(new Vec3(0.0F, 0.0F, 0.0F)), LoopMode.ONCE));
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips));
        return layer;
    }

    /** Walking and running clips in one document, to observe the walk-to-run cross-fade. */
    private static MovementLayer combinedLayer() {
        MovementLayer layer = new MovementLayer();
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, clip(MovementLayer.WALK_FORWARD, 0.8,
                constant(new Vec3(0.0F, 1.0F, 0.0F)), LoopMode.LOOP));
        clips.put(MovementLayer.RUN_START, clip(MovementLayer.RUN_START, 0.2,
                constant(new Vec3(0.0F, 2.0F, 0.0F)), LoopMode.ONCE));
        clips.put(MovementLayer.RUN, clip(MovementLayer.RUN, 0.6,
                constant(new Vec3(0.0F, 3.0F, 0.0F)), LoopMode.LOOP));
        clips.put(MovementLayer.RUN_END, clip(MovementLayer.RUN_END, 0.2,
                constant(new Vec3(0.0F, 0.0F, 0.0F)), LoopMode.ONCE));
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips));
        return layer;
    }

    private static AnimationFile document(BedrockAnimation first, BedrockAnimation second,
                                          BedrockAnimation third, BedrockAnimation fourth) {
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(first.name(), first);
        clips.put(second.name(), second);
        clips.put(third.name(), third);
        clips.put(fourth.name(), fourth);
        return new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips);
    }

    private static AnimationChannel constant(Vec3 value) {
        return AnimationChannel.Constant.ofVector(value);
    }

    private static AnimationChannel ramp(Vec3 start, Vec3 end, double length) {
        return new AnimationChannel.Keyframed(List.of(
                Keyframe.plain(0.0, start),
                Keyframe.plain(length, end)));
    }

    private static BedrockAnimation clip(String suffix, double length, AnimationChannel position, LoopMode loopMode) {
        return new BedrockAnimation("anim.model." + suffix, length, loopMode, Map.of("root",
                new BoneAnimation("root", null, position, null)));
    }
}
