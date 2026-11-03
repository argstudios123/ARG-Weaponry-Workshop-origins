package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The locomotion layer: clip discovery, the reference's running sequence, the phase continuity across airborne states and the
 * running-end carryover.
 *
 * <p>The clips are built here instead of being read from a content pack, so the layer's rules are verified without a game.
 * Every test states what the <em>rendered</em> pose does, not just what an internal flag says.</p>
 */
class MovementLayerTest {

    /** One client tick, the value the layer is advanced with in game. */
    private static final double TICK = 0.05;

    @Test
    void clipsAreDiscoveredByTheirDocumentedSuffix() {
        MovementLayer layer = new MovementLayer();

        assertTrue(layer.bind(document("anim.model.walk_forward", 0.8, new Vec3(0.0F, 1.0F, 0.0F))));
        assertTrue(layer.has(MovementLayer.WALK_FORWARD),
                "a prefixed clip name resolves through its suffix, which is what the shipped content uses");
        assertFalse(layer.has(MovementLayer.RUN));
    }

    @Test
    void aDocumentWithoutLocomotionClipsLeavesTheLayerInactive() {
        MovementLayer layer = new MovementLayer();

        assertFalse(layer.bind(document("anim.model.idle", 2.0, new Vec3(0.0F, 1.0F, 0.0F))));
        layer.update(new MovementState(1.0, 0.0, false), 1.0);
        assertNull(layer.pose(0.0), "a pack that declares no locomotion clip gets no overlay");
        assertFalse(layer.active());
    }

    @Test
    void standingProducesNoOverlayAtAll() {
        MovementLayer layer = walkLayer();
        feed(layer, MovementState.STILL);

        assertEquals(LocomotionState.STANDING, layer.state());
        assertNull(layer.pose(0.0), "the idle base clip owns the standing pose");
    }

    @Test
    void walkingForwardUsesTheForwardClipAndADiagonalBlendsBothAxes() {
        MovementLayer layer = walkLayer();

        feed(layer, new MovementState(1.0, 0.0, false));
        assertEquals(LocomotionState.WALKING, layer.state());
        assertEquals(1.0, (double) layer.pose(0.0).bone("root").position().y(), 1.0E-6);

        feed(layer, MovementState.of(1.0, 1.0, false));
        WeaponPose pose = layer.pose(0.0);
        assertEquals(0.5, (double) pose.bone("root").position().y(), 1.0E-3, "half of the forward clip");
        assertEquals(0.5, (double) pose.bone("root").position().x(), 1.0E-3, "half of the right clip");
    }

    @Test
    void aMovementStateNormalisesADiagonalAndAppliesADeadZone() {
        MovementState diagonal = MovementState.of(1.0, 1.0, false);

        assertEquals(1.0, diagonal.magnitude(), 1.0E-6, "a diagonal is not faster than a straight line");
        assertFalse(MovementState.of(0.01, 0.0, false).moving(), "a leftover analogue value is not a walk");
        assertTrue(MovementState.of(0.0, 0.0, true).running(), "the sprint flag survives a standing player");
        assertEquals(LocomotionIntent.NONE, MovementState.of(1.0, 0.0, false).intent(),
                "a caller without an intent is neutral, never blocked");
    }

    @Test
    void theRunningSequenceFollowsTheReferencesStages() {
        MovementLayer layer = runLayer();
        layer.update(new MovementState(1.0, 0.0, false), TICK);

        layer.update(new MovementState(1.0, 0.0, true), TICK);
        assertEquals(LocomotionState.PREPARE_RUNNING, layer.state(), "a sprint begins with run_start");

        // run_start is 0.2 s long in this document, so it owns the state for four ticks.
        for (int tick = 0; tick < 3; tick++) {
            layer.update(new MovementState(1.0, 0.0, true), TICK);
        }
        assertEquals(LocomotionState.PREPARE_RUNNING, layer.state(), "the prepare clip is played to its end");
        layer.update(new MovementState(1.0, 0.0, true), TICK);
        assertEquals(LocomotionState.RUNNING, layer.state(), "then run loops");

        layer.update(new MovementState(1.0, 0.0, false), TICK);
        assertEquals(LocomotionState.COMPLETE_RUNNING, layer.state(), "releasing sprint plays run_end");

        for (int tick = 0; tick < 9; tick++) {
            layer.update(new MovementState(1.0, 0.0, false), TICK);
        }
        assertEquals(LocomotionState.WALKING, layer.state(), "and then walking continues in one step");
    }

    @Test
    void aWalkPhaseSurvivesAJumpAndLanding() {
        MovementLayer layer = walkLayer();
        feed(layer, new MovementState(1.0, 0.0, false));
        double phaseBefore = layer.walkPhase();
        assertTrue(phaseBefore > 0.3, "the walk cycle is running");

        MovementState jumping = new MovementState(1.0, 0.0, false, true);
        layer.update(jumping, TICK);
        assertEquals(LocomotionState.OFF_GROUND, layer.state());

        for (int tick = 0; tick < 4; tick++) {
            layer.update(jumping, TICK);
        }
        assertEquals(phaseBefore, layer.walkPhase(), 1.0E-9, "the walk phase is frozen in the air, not reset");

        layer.update(new MovementState(1.0, 0.0, false), TICK);
        assertEquals(LocomotionState.WALKING, layer.state());
        assertEquals(phaseBefore + TICK, layer.walkPhase(), 1.0E-9,
                "and landing resumes exactly that phase, advanced by the tick it resumed on");
    }

    @Test
    void theRunClockSurvivesASprintJump() {
        MovementLayer layer = runLayer();
        MovementState sprinting = new MovementState(1.0, 0.0, true);
        for (int tick = 0; tick < 6; tick++) {
            layer.update(sprinting, TICK);
        }
        assertEquals(LocomotionState.RUNNING, layer.state());
        double runningTime = layer.runTime();

        MovementState airborne = new MovementState(0.0, 0.0, true, true);
        for (int tick = 0; tick < 6; tick++) {
            layer.update(airborne, TICK);
            assertEquals(LocomotionState.OFF_GROUND_SPRINTING, layer.state(),
                    "a sprint-jump keeps the sprinting branch");
            assertEquals(runningTime, layer.runTime(), 0.0, "and the running clock is frozen, not restarted");
        }

        layer.update(sprinting, TICK);
        assertEquals(LocomotionState.RUNNING, layer.state(), "landing returns to run");
        assertEquals(runningTime + TICK, layer.runTime(), 1.0E-9,
                "the cycle resumes at the phase it was frozen at, advanced by the landing tick");
    }

    @Test
    void releasingSprintDuringPrepareStartsRunEndAtTheMirroredPhase() {
        MovementLayer layer = runLayer();

        layer.update(new MovementState(1.0, 0.0, true), TICK);
        layer.update(new MovementState(1.0, 0.0, true), TICK);
        double prepareReached = layer.prepareTime();
        double prepareLength = 0.2;
        assertTrue(prepareReached > 0.0 && prepareReached < prepareLength);

        WeaponPose before = layer.pose(0.0);

        layer.update(new MovementState(1.0, 0.0, false), TICK);

        assertEquals(LocomotionState.COMPLETE_RUNNING, layer.state());
        WeaponPose after = layer.pose(0.0);
        double beforeY = before.bone("root").position().y();
        double afterY = after.bone("root").position().y();
        assertTrue(Math.abs(afterY - beforeY) < 1.0,
                "run_end starts at the mirrored phase, so the pose continues instead of snapping to the run pose "
                        + "(" + beforeY + " -> " + afterY + ")");
    }

    @Test
    void aScaleChannelInAMovementClipNeverReachesThePose() {
        MovementLayer layer = new MovementLayer();
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "movement"), "", Map.of(
                MovementLayer.WALK_FORWARD, new BedrockAnimation(MovementLayer.WALK_FORWARD, 0.8, LoopMode.LOOP,
                        Map.of("root", new BoneAnimation("root", null, null,
                                AnimationChannel.Constant.ofVector(new Vec3(3.0F, 3.0F, 3.0F))))))));
        feed(layer, new MovementState(1.0, 0.0, false));

        assertNull(layer.pose(0.0), "a walk clip that only scales is dropped instead of rescaling the weapon");
    }

    // ---------------------------------------------------------------- helpers

    @Test
    void aimingSuppressesTheSprintAnimationEvenWhenTheMovementStateSaysSprinting() {
        MovementLayer layer = runLayer();
        MovementState sprinting = new MovementState(1.0, 0.0, true);

        for (int tick = 0; tick < 20; tick++) {
            layer.update(sprinting, TICK, true, true, false);
        }

        assertEquals(LocomotionState.WALKING_AIMING, layer.state(),
                "the sprint facts are ignored while aiming");
        assertFalse(layer.state().isRunningSequence());
    }

    @Test
    void enteringAdsDuringARunLeavesTheSprintSequenceImmediately() {
        MovementLayer layer = runLayer();
        MovementState sprinting = new MovementState(1.0, 0.0, true);
        for (int tick = 0; tick < 20; tick++) {
            layer.update(sprinting, TICK, true, false, false);
        }
        assertTrue(layer.state() == LocomotionState.RUNNING || layer.state() == LocomotionState.PREPARE_RUNNING);

        layer.update(sprinting, TICK, true, true, false);

        assertEquals(LocomotionState.WALKING_AIMING, layer.state(),
                "no run-end transition is allowed to remain visible after ADS starts");
    }

    @Test
    void anAimedSprintJumpUsesThePlainAirborneState() {
        MovementLayer layer = runLayer();
        MovementState sprinting = new MovementState(1.0, 0.0, true);
        for (int tick = 0; tick < 20; tick++) {
            layer.update(sprinting, TICK, true, false, false);
        }

        layer.update(new MovementState(1.0, 0.0, true, true), TICK, true, true, false);

        assertEquals(LocomotionState.OFF_GROUND, layer.state());
    }

    @Test
    void releasingAimRestoresSprintSelection() {
        MovementLayer layer = runLayer();
        MovementState sprinting = new MovementState(1.0, 0.0, true);
        for (int tick = 0; tick < 3; tick++) {
            layer.update(sprinting, TICK, true, true, false);
        }
        assertEquals(LocomotionState.WALKING_AIMING, layer.state());

        for (int tick = 0; tick < 20; tick++) {
            layer.update(sprinting, TICK, true, false, false);
        }

        assertTrue(layer.state().isRunningSequence() || layer.state() == LocomotionState.WALKING,
                "the normal sprint selection comes back after ADS ends");
    }

    private static MovementLayer walkLayer() {
        MovementLayer layer = new MovementLayer();
        layer.bind(document(
                "anim.model.walk_forward", new Vec3(0.0F, 1.0F, 0.0F),
                "anim.model.walk_backward", new Vec3(0.0F, -1.0F, 0.0F),
                "anim.model.walk_left", new Vec3(-1.0F, 0.0F, 0.0F),
                "anim.model.walk_right", new Vec3(1.0F, 0.0F, 0.0F)));
        return layer;
    }

    private static MovementLayer runLayer() {
        MovementLayer layer = new MovementLayer();
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, clip("anim.model.walk_forward", 0.8, new Vec3(0.0F, 1.0F, 0.0F)));
        clips.put(MovementLayer.RUN_START, clip("anim.model.run_start", 0.2, new Vec3(0.0F, 2.0F, 0.0F)));
        clips.put(MovementLayer.RUN, clip("anim.model.run", 0.6, new Vec3(0.0F, 3.0F, 0.0F)));
        clips.put(MovementLayer.RUN_HOLD, clip("anim.model.run_hold", 0.04, new Vec3(0.0F, 3.0F, 0.0F)));
        clips.put(MovementLayer.RUN_END, clip("anim.model.run_end", 0.2, new Vec3(0.0F, 0.0F, 0.0F)));
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips));
        return layer;
    }

    /** Feeds the layer enough ticks to reach its full weight. */
    private static void feed(MovementLayer layer, MovementState state) {
        for (int tick = 0; tick < 16; tick++) {
            layer.update(state, TICK);
        }
    }

    private static AnimationFile document(String name, double length, Vec3 position) {
        return new AnimationFile(new ResourceId("arg_pack", "movement"), "",
                Map.of(name, clip(name, length, position)));
    }

    private static AnimationFile document(String firstName, Vec3 firstPosition,
                                          String secondName, Vec3 secondPosition,
                                          String thirdName, Vec3 thirdPosition,
                                          String fourthName, Vec3 fourthPosition) {
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(firstName, clip(firstName, 0.8, firstPosition));
        clips.put(secondName, clip(secondName, 0.8, secondPosition));
        clips.put(thirdName, clip(thirdName, 0.8, thirdPosition));
        clips.put(fourthName, clip(fourthName, 0.8, fourthPosition));
        return new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips);
    }

    private static BedrockAnimation clip(String name, double length, Vec3 position) {
        return new BedrockAnimation(name, length, LoopMode.LOOP, Map.of("root",
                new BoneAnimation("root", null, AnimationChannel.Constant.ofVector(position), null)));
    }
}

