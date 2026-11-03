package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The jumping ladder: a take-off one-shot, an airborne loop that is never restarted, and a landing reaction that plays once and
 * blends back into the gait.
 *
 * <h2>How the values are read</h2>
 * <p>Every clip in this test pack owns a disjoint range of root {@code y}, so a sampled value identifies the clip that produced
 * it unambiguously: the walking ramp lives in {@code 10..}, the take-off in {@code 0..2}, the loop in {@code 100..}, the landing
 * in {@code 200..}, and the sprinting variants in {@code 300..}, {@code 400..} and {@code 500..}.</p>
 */
class MovementLayerJumpTest {

    private static final double TICK = 0.05;

    // ---------------------------------------------------------------- take-off

    @Test
    void theTakeOffClipPlaysOnceAndThenHandsOverToTheLoop() {
        MovementLayer layer = jumpLayer();
        walkFor(layer, 4);
        double walkingPose = rootY(layer, 0.0);

        layer.update(airborne(false), TICK);
        assertEquals(LocomotionState.OFF_GROUND, layer.state());
        double onTakeOff = rootY(layer, 0.0);
        assertTrue(onTakeOff < walkingPose && onTakeOff < 100.0,
                "the take-off clip is what plays on the frame the player leaves the ground, blended in from the walk ("
                        + walkingPose + " -> " + onTakeOff + ")");

        // The take-off clip is 0.4 s long, so it owns the pose for its own length and not longer.
        for (int tick = 0; tick < 4; tick++) {
            layer.update(airborne(false), TICK);
        }
        double takeOff = rootY(layer, 0.0);
        assertTrue(takeOff >= 0.0 && takeOff <= 2.0,
                "after the fade the take-off clip owns the pose (" + takeOff + ")");

        for (int tick = 0; tick < 8; tick++) {
            layer.update(airborne(false), TICK);
        }
        assertTrue(rootY(layer, 0.0) >= 100.0,
                "the airborne loop took over once the take-off finished (" + rootY(layer, 0.0) + ")");
    }

    @Test
    void theAirborneLoopKeepsAdvancingAndIsNeverRestarted() {
        MovementLayer layer = jumpLayer();
        walkFor(layer, 4);
        for (int tick = 0; tick < 14; tick++) {
            layer.update(airborne(false), TICK);
        }

        List<Double> frames = new ArrayList<>();
        for (int tick = 0; tick < 10; tick++) {
            layer.update(airborne(false), TICK);
            frames.add(rootY(layer, 0.0));
        }

        for (int index = 1; index < frames.size(); index++) {
            assertTrue(frames.get(index) > frames.get(index - 1),
                    "the loop advances with the airborne clock; a restart would send it back to its first frame ("
                            + frames + ")");
        }
    }

    @Test
    void anAirborneBranchChangeDoesNotRestartTheJump() {
        MovementLayer layer = jumpLayer();
        walkFor(layer, 4);
        for (int tick = 0; tick < 6; tick++) {
            layer.update(airborne(false), TICK);
        }
        double before = rootY(layer, 0.0);

        // A one-tick drop into the other airborne branch - a sprint flag flickering mid-air - must not replay the take-off.
        layer.update(airborne(true), TICK);
        layer.update(airborne(false), TICK);

        assertTrue(rootY(layer, 0.0) > before,
                "the jump kept its phase instead of restarting (" + before + " -> " + rootY(layer, 0.0) + ")");
    }

    // ---------------------------------------------------------------- landing

    @Test
    void theLandingReactionPlaysOnceAndBlendsBackIntoWalking() {
        MovementLayer layer = jumpLayer();
        walkFor(layer, 4);
        for (int tick = 0; tick < 8; tick++) {
            layer.update(airborne(false), TICK);
        }

        layer.update(walking(), TICK);
        assertEquals(LocomotionState.WALKING, layer.state(), "the machine decides the gait underneath the reaction");

        // The fade into the reaction is TRANSITION_SECONDS long; after it the landing clip owns the pose.
        for (int tick = 0; tick < 3; tick++) {
            layer.update(walking(), TICK);
        }
        double first = rootY(layer, 0.0);
        assertTrue(first >= 200.0 && first < 320.0, "the landing reaction is playing (" + first + ")");

        List<Double> frames = new ArrayList<>();
        frames.add(first);
        // The clip is 0.6 s long and its last LAND_RELEASE_SECONDS blend back into the walk, so the first 0.45 s of it are
        // the reaction itself and must advance monotonically.
        for (int tick = 0; tick < 4; tick++) {
            layer.update(walking(), TICK);
            frames.add(rootY(layer, 0.0));
        }
        for (int index = 1; index < frames.size(); index++) {
            assertTrue(frames.get(index) > frames.get(index - 1),
                    "the reaction plays once and forward; a restart would send it back to its first frame (" + frames + ")");
        }

        for (int tick = 0; tick < 8; tick++) {
            layer.update(walking(), TICK);
        }
        double afterReaction = rootY(layer, 0.0);
        assertTrue(afterReaction < 100.0,
                "the walk owns the pose again once the reaction finished (" + afterReaction + ")");
    }

    @Test
    void aStepDownDoesNotStartALandingReaction() {
        MovementLayer layer = jumpLayer();
        walkFor(layer, 4);

        // One tick off the ground: a step down a half block, not a jump.
        layer.update(airborne(false), TICK);
        assertEquals(LocomotionState.OFF_GROUND, layer.state());
        layer.update(walking(), TICK);

        assertEquals(LocomotionState.WALKING, layer.state());
        assertTrue(rootY(layer, 0.0) < 100.0,
                "no landing reaction fires for an airtime under " + MovementLayer.LANDING_MIN_AIR_SECONDS + "s ("
                        + rootY(layer, 0.0) + ")");
    }

    // ---------------------------------------------------------------- sprint context

    @Test
    void aSprintJumpUsesTheSprintingClipsAndTheSprintLanding() {
        MovementLayer layer = jumpLayer();
        for (int tick = 0; tick < 20; tick++) {
            layer.update(sprinting(), TICK);
        }
        assertTrue(layer.state().isRunningSequence(), "the sprint sequence is running (" + layer.state() + ")");

        layer.update(airborne(true), TICK);
        assertEquals(LocomotionState.OFF_GROUND_SPRINTING, layer.state(), "a sprint-jump keeps the sprinting branch");
        for (int tick = 0; tick < 4; tick++) {
            layer.update(airborne(true), TICK);
        }
        assertTrue(rootY(layer, 0.0) >= 300.0 && rootY(layer, 0.0) <= 302.0,
                "the sprinting take-off clip is used, not the walking one (" + rootY(layer, 0.0) + ")");

        for (int tick = 0; tick < 8; tick++) {
            layer.update(airborne(true), TICK);
        }
        double loop = rootY(layer, 0.0);
        assertTrue(loop >= 400.0, "the sprinting airborne loop is used, not the walking one (" + loop + ")");

        for (int tick = 0; tick < 4; tick++) {
            layer.update(sprinting(), TICK);
        }
        double landing = rootY(layer, 0.0);
        assertTrue(landing >= 500.0, "the sprint landing reaction is used, not the walking one (" + landing + ")");
    }

    @Test
    void landingFromASprintJumpReturnsToRunning() {
        MovementLayer layer = jumpLayer();
        for (int tick = 0; tick < 20; tick++) {
            layer.update(sprinting(), TICK);
        }
        layer.update(airborne(true), TICK);
        double runTimeBeforeLanding = layer.runTime();
        for (int tick = 0; tick < 8; tick++) {
            layer.update(airborne(true), TICK);
        }

        layer.update(sprinting(), TICK);

        assertEquals(LocomotionState.RUNNING, layer.state(), "the sprint continues on landing");
        assertTrue(layer.runTime() > runTimeBeforeLanding,
                "and the run cycle resumes on the ground where the jump left it (" + runTimeBeforeLanding + " -> "
                        + layer.runTime() + ")");
    }

    // ---------------------------------------------------------------- aiming

    @Test
    void aJumpWhileAimingLandsBackIntoTheAimingWalk() {
        MovementLayer layer = jumpLayer();
        for (int tick = 0; tick < 8; tick++) {
            layer.update(walking(), TICK, true, true, false);
        }
        assertEquals(LocomotionState.WALKING_AIMING, layer.state(), "the aimed walk is the state this test starts in");

        layer.update(airborne(false), TICK, true, true, false);
        assertEquals(LocomotionState.OFF_GROUND, layer.state(), "an aimed player still jumps");
        for (int tick = 0; tick < 10; tick++) {
            layer.update(airborne(false), TICK, true, true, false);
        }
        double airbornePose = rootY(layer, 0.0);
        assertTrue(airbornePose >= 100.0,
                "the airborne loop owns the pose in the air, aiming or not (" + airbornePose + ")");

        layer.update(walking(), TICK, true, true, false);
        assertEquals(LocomotionState.WALKING_AIMING, layer.state(),
                "landing while aiming continues in the aimed walk instead of leaving the weapon in the jump pose");

        // The fade into the reaction is TRANSITION_SECONDS long; after it the landing clip owns the pose.
        for (int tick = 0; tick < 3; tick++) {
            layer.update(walking(), TICK, true, true, false);
        }
        double landing = rootY(layer, 0.0);
        assertTrue(landing >= 200.0 && landing < 500.0,
                "the landing reaction plays on an aimed landing too (" + landing + ")");

        for (int tick = 0; tick < 16; tick++) {
            layer.update(walking(), TICK, true, true, false);
        }
        assertTrue(rootY(layer, 0.0) < 100.0,
                "and the aimed walk owns the pose again once the reaction finished (" + rootY(layer, 0.0) + ")");
    }

    @Test
    void anAimedJumpAtAStandstillLandsBackIntoTheIdlePose() {
        MovementLayer layer = jumpLayer();
        for (int tick = 0; tick < 4; tick++) {
            layer.update(walking(), TICK, true, true, false);
        }

        // Standing still: no movement intent, so the machine returns to STANDING on contact and the locomotion layer
        // contributes nothing (the idle base clip owns the pose) - the aimed landing must not stay airborne either.
        layer.update(airborne(false), TICK, true, true, false);
        assertEquals(LocomotionState.OFF_GROUND, layer.state());

        for (int tick = 0; tick < 4; tick++) {
            layer.update(MovementState.STILL, TICK, true, true, false);
        }

        assertEquals(LocomotionState.STANDING, layer.state(),
                "a standstill landing releases the airborne state while aiming");
    }

    // ---------------------------------------------------------------- compatibility

    @Test
    void aPackWithoutJumpClipsKeepsTheLegacyAirborneBehaviour() {
        MovementLayer layer = legacyLayer();
        walkFor(layer, 4);

        layer.update(airborne(false), TICK);
        assertEquals(LocomotionState.OFF_GROUND, layer.state());
        // The fade into the airborne pose is TRANSITION_SECONDS long, so the legacy clip owns the pose after it.
        for (int tick = 0; tick < 3; tick++) {
            layer.update(airborne(false), TICK);
        }
        assertNotNull(layer.pose(0.0));
        assertEquals(700.0, rootY(layer, 0.0), 1.0E-6,
                "the legacy off_ground clip is still what a pack without jump clips plays");

        for (int tick = 0; tick < 5; tick++) {
            layer.update(walking(), TICK);
        }
        assertTrue(rootY(layer, 0.0) < 700.0,
                "and no landing reaction is invented for a pack that authors none (" + rootY(layer, 0.0) + ")");
    }

    @Test
    void theJumpingClipsTakePriorityOverTheLegacyAirborneClip() {
        MovementLayer layer = jumpAndLegacyLayer();
        walkFor(layer, 4);

        layer.update(airborne(false), TICK);
        for (int tick = 0; tick < 6; tick++) {
            layer.update(airborne(false), TICK);
        }
        double airbornePose = rootY(layer, 0.0);

        assertTrue(airbornePose < 700.0,
                "the jumping ladder wins over the legacy single airborne clip (" + airbornePose + ")");
    }

    @Test
    void aLoopOnlyPackPlaysTheLoopFromTheStartOfTheJump() {
        MovementLayer layer = loopOnlyLayer();
        walkFor(layer, 4);

        layer.update(airborne(false), TICK);
        for (int tick = 0; tick < 3; tick++) {
            layer.update(airborne(false), TICK);
        }

        assertTrue(rootY(layer, 0.0) >= 100.0,
                "without a take-off clip the loop starts with the jump (" + rootY(layer, 0.0) + ")");
    }

    @Test
    void aShortTakeOffClipIsNotSwallowedByTheHandover() {
        MovementLayer layer = shortTakeOffLayer();
        walkFor(layer, 4);

        layer.update(airborne(false), TICK);
        double firstAirborneFrame = rootY(layer, 0.0);
        assertTrue(firstAirborneFrame <= 10.0,
                "a take-off clip shorter than the hand-over window still reads as a take-off on the frame after leaving the "
                        + "ground, instead of being half-blended into the loop (" + firstAirborneFrame + ")");

        for (int tick = 0; tick < 4; tick++) {
            layer.update(airborne(false), TICK);
        }
        assertTrue(rootY(layer, 0.0) >= 100.0,
                "and the loop takes over once the short take-off clip is over (" + rootY(layer, 0.0) + ")");
    }

    // ---------------------------------------------------------------- helpers

    /** A take-off clip shorter than {@link MovementLayer#JUMP_HANDOVER_SECONDS}, with a loop. */
    private static MovementLayer shortTakeOffLayer() {
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, ramp(MovementLayer.WALK_FORWARD, 60.0, 10.0F, 610.0F));
        clips.put(MovementLayer.JUMP_START, ramp(MovementLayer.JUMP_START, 0.1, 0.0F, 2.0F, LoopMode.ONCE));
        clips.put(MovementLayer.JUMP_LOOP, ramp(MovementLayer.JUMP_LOOP, 60.0, 100.0F, 700.0F));
        return bind(clips);
    }

    private static void walkFor(MovementLayer layer, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            layer.update(walking(), TICK);
        }
    }

    private static double rootY(MovementLayer layer, double offsetSeconds) {
        WeaponPose pose = layer.pose(offsetSeconds);
        return pose == null ? Double.NaN : pose.bone("root").position().y();
    }

    private static MovementState walking() {
        return MovementState.of(1.0, 0.0, false, false,
                new LocomotionIntent(true, false, true, false, false, false, false, true, 1.0));
    }

    private static MovementState sprinting() {
        return MovementState.of(1.0, 0.0, true, false,
                new LocomotionIntent(true, true, true, false, false, false, false, true, 1.0));
    }

    private static MovementState airborne(boolean sprinting) {
        return MovementState.of(0.0, 0.0, sprinting, true,
                new LocomotionIntent(false, sprinting, false, false, false, false, false, true, 1.0));
    }

    /** The walking gait, the running sequence, the jumping ladder and the landing reactions. */
    private static MovementLayer jumpLayer() {
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, ramp(MovementLayer.WALK_FORWARD, 60.0, 10.0F, 610.0F));
        clips.put(MovementLayer.RUN_START, constant(MovementLayer.RUN_START, 0.2, 3.0F));
        clips.put(MovementLayer.RUN, ramp(MovementLayer.RUN, 60.0, 800.0F, 1400.0F));
        clips.put(MovementLayer.RUN_END, constant(MovementLayer.RUN_END, 0.2, 0.0F));
        clips.put(MovementLayer.JUMP_START, ramp(MovementLayer.JUMP_START, 0.4, 0.0F, 2.0F));
        clips.put(MovementLayer.JUMP_LOOP, ramp(MovementLayer.JUMP_LOOP, 60.0, 100.0F, 700.0F));
        clips.put(MovementLayer.SPRINT_JUMP_START, ramp(MovementLayer.SPRINT_JUMP_START, 0.4, 300.0F, 302.0F));
        clips.put(MovementLayer.SPRINT_JUMP_LOOP, ramp(MovementLayer.SPRINT_JUMP_LOOP, 60.0, 400.0F, 1000.0F));
        clips.put(MovementLayer.LAND, ramp(MovementLayer.LAND, 0.6, 200.0F, 320.0F));
        clips.put(MovementLayer.SPRINT_LAND, ramp(MovementLayer.SPRINT_LAND, 0.6, 500.0F, 620.0F));
        return bind(clips);
    }

    /** A pack written before jumping clips existed: the single {@code off_ground} pose and no landing. */
    private static MovementLayer legacyLayer() {
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, ramp(MovementLayer.WALK_FORWARD, 60.0, 10.0F, 610.0F));
        clips.put(MovementLayer.OFF_GROUND, constant(MovementLayer.OFF_GROUND, 0.3333, 700.0F));
        return bind(clips);
    }

    /** Both a legacy airborne clip and the jumping ladder, to pin which one wins. */
    private static MovementLayer jumpAndLegacyLayer() {
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, ramp(MovementLayer.WALK_FORWARD, 60.0, 10.0F, 610.0F));
        clips.put(MovementLayer.OFF_GROUND, constant(MovementLayer.OFF_GROUND, 0.3333, 700.0F));
        clips.put(MovementLayer.JUMP_START, ramp(MovementLayer.JUMP_START, 0.2, 0.0F, 2.0F));
        clips.put(MovementLayer.JUMP_LOOP, ramp(MovementLayer.JUMP_LOOP, 60.0, 100.0F, 700.0F));
        return bind(clips);
    }

    /** Only the airborne loop is authored, so the loop has to start with the jump. */
    private static MovementLayer loopOnlyLayer() {
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, ramp(MovementLayer.WALK_FORWARD, 60.0, 10.0F, 610.0F));
        clips.put(MovementLayer.JUMP_LOOP, ramp(MovementLayer.JUMP_LOOP, 60.0, 100.0F, 700.0F));
        return bind(clips);
    }

    private static MovementLayer bind(Map<String, BedrockAnimation> clips) {
        MovementLayer layer = new MovementLayer();
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips));
        return layer;
    }

    private static BedrockAnimation ramp(String suffix, double length, float startY, float endY) {
        return ramp(suffix, length, startY, endY, LoopMode.LOOP);
    }

    private static BedrockAnimation ramp(String suffix, double length, float startY, float endY, LoopMode loopMode) {
        return new BedrockAnimation("anim.model." + suffix, length, loopMode,
                Map.of("root", new BoneAnimation("root", null, new AnimationChannel.Keyframed(List.of(
                        Keyframe.plain(0.0, new Vec3(0.0F, startY, 0.0F)),
                        Keyframe.plain(length, new Vec3(0.0F, endY, 0.0F)))), null)));
    }

    private static BedrockAnimation constant(String suffix, double length, float y) {
        return new BedrockAnimation("anim.model." + suffix, length, LoopMode.ONCE,
                Map.of("root", new BoneAnimation("root", null,
                        AnimationChannel.Constant.ofVector(new Vec3(0.0F, y, 0.0F)), null)));
    }
}
