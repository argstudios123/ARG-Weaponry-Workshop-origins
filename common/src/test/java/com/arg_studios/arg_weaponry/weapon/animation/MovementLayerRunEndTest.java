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
 * The running-end recovery: its playback rate is its own, so releasing sprint does not make it crawl.
 *
 * <h2>The bug this pins</h2>
 * <p>Every locomotion clock used to advance by {@code delta * speedScale}. That multiplier is derived from the player's speed,
 * and a player who has just released sprint is decelerating, so it sits around 0.5 at exactly the moment the recovery starts:
 * the authored 0.42 s {@code run_end} played over roughly 0.8 s and the state stayed alive for about 15 ticks instead of 9.
 * A transition pose is a recovery, not a gait - it plays at its authored speed ({@link MovementLayer#RUN_END_PLAYBACK_RATE}),
 * while the walk and the run keep following the player's speed.</p>
 */
class MovementLayerRunEndTest {

    private static final double TICK = 0.05;

    /** {@code run_end} in this test pack is 0.42 s long and ramps 0 -> 8.4, like the shipped clip. */
    private static final double RUN_END_LENGTH = 0.42;
    private static final double RUN_END_RAMP = 8.4;

    @Test
    void theRunEndAdvancesAtItsOwnRateWhateverTheMovementSpeedIs() {
        MovementLayer layer = runLayer();
        sprintUntilRunning(layer);

        // Releasing sprint with a slow movement intent: the gait multiplier is far below 1, the recovery must ignore it.
        layer.update(release(), TICK);
        assertEquals(LocomotionState.COMPLETE_RUNNING, layer.state());
        double afterEntry = layer.completeTime();
        assertEquals(TICK * MovementLayer.RUN_END_PLAYBACK_RATE, afterEntry, 1.0E-9,
                "run_end took its first step at its own rate, not at the movement's (" + afterEntry + ")");

        layer.update(release(), TICK);
        assertEquals(2 * TICK * MovementLayer.RUN_END_PLAYBACK_RATE, layer.completeTime(), 1.0E-9,
                "and keeps doing so while the state runs");
    }

    @Test
    void theRunEndFinishesInItsOwnLengthInsteadOfTheMovementRate() {
        MovementLayer layer = runLayer();
        sprintUntilRunning(layer);
        layer.update(release(), TICK);
        assertEquals(LocomotionState.COMPLETE_RUNNING, layer.state());

        int ticks = 1;
        while (layer.state() == LocomotionState.COMPLETE_RUNNING && ticks < 60) {
            layer.update(release(), TICK);
            ticks++;
        }

        // The clock needs ceil(length / tick) advances to reach the end of the clip, and the machine leaves the state on the
        // update after that, so one extra tick is expected. What matters is that the recovery is not stretched: at the
        // movement's ~0.5x it would need about twice as long.
        int advances = (int) Math.ceil(RUN_END_LENGTH / (TICK * MovementLayer.RUN_END_PLAYBACK_RATE));
        assertEquals(advances + 1, ticks,
                "the recovery lasts its authored " + RUN_END_LENGTH + "s (" + ticks + " ticks); a rate inherited from the "
                        + "movement would stretch it towards " + (int) Math.ceil(RUN_END_LENGTH / (TICK * 0.5)) + " ticks");
        assertTrue(ticks < 13, "and it is nowhere near the stretched length (" + ticks + " ticks)");
    }

    @Test
    void sprintingDuringTheRunStartCarriesOverAndThenPlaysAtTheOwnRate() {
        MovementLayer layer = runLayer();
        for (int tick = 0; tick < 4; tick++) {
            layer.update(walking(), TICK);
        }
        layer.update(sprinting(), TICK);
        layer.update(sprinting(), TICK);
        assertEquals(LocomotionState.PREPARE_RUNNING, layer.state(), "the sprint began with run_start");

        double prepareReached = layer.prepareTime();
        assertTrue(prepareReached > 0.0 && prepareReached < 0.3,
                "the take-off clip is only part-played (" + prepareReached + ")");

        layer.update(release(), TICK);

        assertEquals(LocomotionState.COMPLETE_RUNNING, layer.state(), "releasing sprint plays run_end");
        double carriedOver = (1.0 - prepareReached / 0.3) * RUN_END_LENGTH;
        assertEquals(carriedOver + TICK * MovementLayer.RUN_END_PLAYBACK_RATE, layer.completeTime(), 1.0E-9,
                "run_end starts at the mirrored phase of the take-off clip and advances at its own rate ("
                        + layer.completeTime() + " vs " + carriedOver + ")");
    }

    @Test
    void theRunEndIsPlayedOnceAndNeverRestarted() {
        MovementLayer layer = runLayer();
        sprintUntilRunning(layer);
        layer.update(release(), TICK);

        List<Double> times = new ArrayList<>();
        for (int tick = 0; tick < 4; tick++) {
            times.add(layer.completeTime());
            layer.update(release(), TICK);
            assertEquals(LocomotionState.COMPLETE_RUNNING, layer.state(),
                    "run_end owns the state for its whole length instead of being restarted");
        }

        for (int index = 1; index < times.size(); index++) {
            assertTrue(times.get(index) > times.get(index - 1),
                    "the recovery clock only moves forward (" + times + ")");
        }
    }

    @Test
    void theWalkingClipsStillFollowTheMovementSpeed() {
        MovementLayer slow = runLayer();
        MovementLayer fast = runLayer();
        for (int tick = 0; tick < 40; tick++) {
            slow.update(walking(0.5), TICK);
            fast.update(walking(1.0), TICK);
        }

        assertEquals(2.0, fast.walkPhase() / slow.walkPhase(), 0.05,
                "a half-speed walk advances its gait at half the rate - only the recovery is rate-independent ("
                        + slow.walkPhase() + " vs " + fast.walkPhase() + ")");
    }

    @Test
    void theRunEndSampleAdvancesAtTheSameRateAsItsClock() {
        MovementLayer layer = runLayer();
        sprintUntilRunning(layer);
        layer.update(release(), TICK);
        // Let the fade out of the running pose finish first: until it does, the sampled value is a blend of two controllers.
        for (int tick = 0; tick < 3; tick++) {
            layer.update(release(), TICK);
        }
        assertEquals(LocomotionState.COMPLETE_RUNNING, layer.state(), "the recovery is still running");

        // An 8.4-unit ramp over 0.42 s moves 20 units per second of clip time, so a 1x rate moves 1.0 per tick and 0.25 per
        // quarter-tick frame - including the frame that crosses the tick boundary.
        int framesPerTick = 4;
        List<Double> steps = new ArrayList<>();
        double previous = rootY(layer, 0.0);
        for (int tick = 0; tick < 3 && layer.state() == LocomotionState.COMPLETE_RUNNING; tick++) {
            for (int frame = 1; frame < framesPerTick; frame++) {
                double value = rootY(layer, frame * TICK / framesPerTick);
                steps.add(value - previous);
                previous = value;
            }
            layer.update(release(), TICK);
            double next = rootY(layer, 0.0);
            steps.add(next - previous);
            previous = next;
        }

        double expected = RUN_END_RAMP / RUN_END_LENGTH * MovementLayer.RUN_END_PLAYBACK_RATE * TICK / framesPerTick;
        assertTrue(steps.size() >= 8, "the state lasted long enough to sample several frames (" + steps.size() + ")");
        for (double step : steps) {
            assertEquals(expected, step, expected * 0.05,
                    "every frame of the recovery advances by its own rate, with no step at the tick boundary");
        }
    }

    @Test
    void theWalkPhaseSurvivesTheRunningEnd() {
        MovementLayer layer = runLayer();
        MovementState walking = walking();
        for (int tick = 0; tick < 8; tick++) {
            layer.update(walking, TICK);
        }
        double walkPhaseBeforeSprint = layer.walkPhase();
        assertTrue(walkPhaseBeforeSprint > 0.2, "the walk cycle is running before the sprint (" + walkPhaseBeforeSprint + ")");

        for (int tick = 0; tick < 20; tick++) {
            layer.update(sprinting(), TICK);
        }
        assertEquals(LocomotionState.RUNNING, layer.state());

        layer.update(release(), TICK);
        assertEquals(LocomotionState.COMPLETE_RUNNING, layer.state());
        int ticks = 0;
        while (layer.state() == LocomotionState.COMPLETE_RUNNING && ticks < 40) {
            layer.update(release(), TICK);
            ticks++;
        }

        assertEquals(LocomotionState.WALKING, layer.state(), "the sprint ends in the walk");
        assertTrue(layer.walkPhase() >= walkPhaseBeforeSprint,
                "the recovery hands the walk back at the phase it left it instead of restarting the cycle at frame zero ("
                        + walkPhaseBeforeSprint + " -> " + layer.walkPhase() + ")");
        assertTrue(layer.walkPhase() > 0.1, "and the cycle is still a running cycle, not a reset one (" + layer.walkPhase() + ")");
    }

    // ---------------------------------------------------------------- helpers

    private static void sprintUntilRunning(MovementLayer layer) {
        for (int tick = 0; tick < 20; tick++) {
            layer.update(sprinting(), TICK);
        }
        assertEquals(LocomotionState.RUNNING, layer.state(), "the sprint reached its running stage");
    }

    private static double rootY(MovementLayer layer, double offsetSeconds) {
        WeaponPose pose = layer.pose(offsetSeconds);
        return pose == null ? Double.NaN : pose.bone("root").position().y();
    }

    private static MovementState walking() {
        return walking(1.0);
    }

    private static MovementState walking(double speedScale) {
        return MovementState.of(1.0, 0.0, false, false,
                new LocomotionIntent(true, false, true, false, false, false, false, true, speedScale));
    }

    private static MovementState sprinting() {
        return MovementState.of(1.0, 0.0, true, false,
                new LocomotionIntent(true, true, true, false, false, false, false, true, 1.0));
    }

    /** Sprint released, still walking forward, with the gait multiplier a decelerating player really has. */
    private static MovementState release() {
        return MovementState.of(1.0, 0.0, false, false,
                new LocomotionIntent(true, false, true, false, false, false, false, true, 0.3));
    }

    private static MovementLayer runLayer() {
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, ramp(MovementLayer.WALK_FORWARD, 60.0, 0.0F, 600.0F));
        clips.put(MovementLayer.RUN_START, new BedrockAnimation(MovementLayer.RUN_START, 0.3, LoopMode.ONCE,
                Map.of("root", new BoneAnimation("root", null,
                        AnimationChannel.Constant.ofVector(new Vec3(0.0F, 2.0F, 0.0F)), null))));
        clips.put(MovementLayer.RUN, ramp(MovementLayer.RUN, 60.0, 2.0F, 602.0F));
        clips.put(MovementLayer.RUN_END, ramp(MovementLayer.RUN_END, RUN_END_LENGTH, 0.0F, (float) RUN_END_RAMP));
        MovementLayer layer = new MovementLayer();
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips));
        return layer;
    }

    private static BedrockAnimation ramp(String suffix, double length, float startY, float endY) {
        return new BedrockAnimation("anim.model." + suffix, length, LoopMode.LOOP,
                Map.of("root", new BoneAnimation("root", null, new AnimationChannel.Keyframed(List.of(
                        Keyframe.plain(0.0, new Vec3(0.0F, startY, 0.0F)),
                        Keyframe.plain(length, new Vec3(0.0F, endY, 0.0F)))), null)));
    }
}
