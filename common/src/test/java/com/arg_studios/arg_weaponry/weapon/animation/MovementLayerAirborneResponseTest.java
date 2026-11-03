package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The airborne vertical response: the part of the jump fix that stops a weapon from rising or floating without a
 * corresponding player movement.
 *
 * <h2>The bug these tests pin down</h2>
 * <p>The airborne clips carry their own vertical translation. The shipped {@code jump_loop} holds its root about one unit
 * above the idle pose, so playing it for the whole airborne period lifted the weapon on take-off and kept it there at the
 * apex and while falling - the "the weapon floats up by itself" report. The layer now maps the player's real vertical
 * velocity to a {@code 0..1} response: the authored translation is shown while the player is genuinely rising and is
 * re-based onto the held gait at the apex and during the fall. The clips' rotations are always kept, so the jump still
 * looks like a jump.</p>
 *
 * <p>Every assertion is on the rendered root position, not on an internal flag, so a regression is visible as a value the
 * player would see.</p>
 */
class MovementLayerAirborneResponseTest {

    /** One client tick, the value the layer is advanced with in game. */
    private static final double TICK = 0.05;

    @Test
    void aRisingJumpAppliesTheAuthoredVerticalOffset() {
        MovementLayer layer = jumpingLayer();
        feed(layer, new MovementState(1.0, 0.0, false), 16);

        feed(layer, new MovementState(1.0, 0.0, false, true, LocomotionIntent.NONE, 0.42), 2);

        double risen = rootY(layer);
        assertTrue(risen > 0.5,
                "a real upward velocity must still reproduce the authored take-off motion; got " + risen);
        assertTrue(risen < 2.1,
                "and it must not exceed the authored amplitude; got " + risen);
    }

    @Test
    void atTheApexTheWeaponSettlesBackOntoItsGait() {
        MovementLayer layer = jumpingLayer();
        feed(layer, new MovementState(1.0, 0.0, false), 16);
        feed(layer, new MovementState(1.0, 0.0, false, true, LocomotionIntent.NONE, 0.42), 2);
        assertTrue(rootY(layer) > 0.5, "the take-off reaction is visible first");

        // The apex: off the ground, but no vertical movement. The authored hold must not keep the weapon lifted.
        feed(layer, new MovementState(1.0, 0.0, false, true, LocomotionIntent.NONE, 0.0), 6);

        assertEquals(LocomotionState.OFF_GROUND, layer.state());
        assertEquals(0.0, rootY(layer), 0.1,
                "at the apex the weapon returns to the gait it jumped with instead of holding the authored lift");
        assertEquals(0.0, layer.verticalResponse(), 0.02,
                "the vertical response has released");
    }

    @Test
    void fallingDoesNotLiftTheWeaponOnItsOwn() {
        MovementLayer layer = jumpingLayer();
        feed(layer, new MovementState(1.0, 0.0, false), 16);
        feed(layer, new MovementState(1.0, 0.0, false, true, LocomotionIntent.NONE, 0.42), 2);

        feed(layer, new MovementState(1.0, 0.0, false, true, LocomotionIntent.NONE, -0.6), 8);

        assertEquals(LocomotionState.OFF_GROUND, layer.state());
        assertEquals(0.0, rootY(layer), 0.1,
                "a falling player has no upward response, so the jump loop cannot hold the weapon up");
    }

    @Test
    void theLandingReactionIsEasedInAndItsVerticalKickIsScaled() {
        MovementLayer layer = jumpingLayer();
        feed(layer, new MovementState(1.0, 0.0, false), 16);
        feed(layer, new MovementState(1.0, 0.0, false, true, LocomotionIntent.NONE, 0.42), 2);
        feed(layer, new MovementState(1.0, 0.0, false, true, LocomotionIntent.NONE, 0.0), 6);

        // Touch down. The authored land clip opens at +2 units (it was written to continue the airborne hold); the first
        // rendered frame must not step to that value from the settled gait.
        layer.update(new MovementState(1.0, 0.0, false), TICK);

        double firstLandingFrame = rootY(layer);
        assertTrue(Math.abs(firstLandingFrame) < 1.0,
                "the landing clip is blended in from the pose the jump ended on; got " + firstLandingFrame);
    }

    @Test
    void touchingDownAlwaysStopsTheAirborneClock() {
        MovementLayer layer = jumpingLayer();
        feed(layer, new MovementState(1.0, 0.0, false), 16);
        feed(layer, new MovementState(1.0, 0.0, false, true, LocomotionIntent.NONE, 0.42), 2);

        // Sprint intent with only a lateral input: every walking rule refuses a sprint intent and the prepare-run rule
        // needs a forward input, so before the touch-down safety net the machine stayed in OFF_GROUND on the ground.
        MovementState landed = new MovementState(0.0, 1.0, true, false, LocomotionIntent.NONE, 0.0);
        layer.update(landed, TICK);

        assertFalse(layer.state().isOffGround(),
                "a touch-down leaves the airborne state even when no reference guard matches");
        double airTime = layer.airTime();
        for (int tick = 0; tick < 5; tick++) {
            layer.update(landed, TICK);
            assertFalse(layer.state().isOffGround(), "and it stays on a ground state");
        }
        assertEquals(airTime, layer.airTime(), 1.0E-9,
                "the airborne clock is frozen on the ground, so a jump loop cannot keep advancing");
    }

    private static double rootY(MovementLayer layer) {
        WeaponPose pose = layer.pose(0.0);
        assertTrue(pose != null && pose.bone("root") != null, "the layer must render a root pose");
        return pose.bone("root").position().y();
    }

    /** Feeds the layer a number of ticks of one movement state. */
    private static void feed(MovementLayer layer, MovementState state, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            layer.update(state, TICK);
        }
    }

    /**
     * A layer whose airborne clips translate the root vertically by two units, so the authored displacement is easy to see
     * in the assertions. The walking clip stays at the origin, which is the gait the weapon must settle onto.
     */
    private static MovementLayer jumpingLayer() {
        Map<String, BedrockAnimation> clips = new LinkedHashMap<>();
        clips.put(MovementLayer.WALK_FORWARD, constant("anim.model.walk_forward", 0.8, new Vec3(0.0F, 0.0F, 0.0F)));
        clips.put(MovementLayer.JUMP_START, takeOff("anim.model.jump_start", 0.4));
        clips.put(MovementLayer.JUMP_LOOP, constant("anim.model.jump_loop", 0.4, new Vec3(0.0F, 2.0F, 0.0F)));
        clips.put(MovementLayer.LAND, landing("anim.model.land", 0.4));
        MovementLayer layer = new MovementLayer();
        layer.bind(new AnimationFile(new ResourceId("arg_pack", "movement"), "", clips));
        return layer;
    }

    private static BedrockAnimation constant(String name, double length, Vec3 position) {
        return new BedrockAnimation(name, length, LoopMode.LOOP, Map.of("root",
                new BoneAnimation("root", null, AnimationChannel.Constant.ofVector(position), null)));
    }

    /** A take-off that rises to two units over the first half of the clip and holds them. */
    private static BedrockAnimation takeOff(String name, double length) {
        AnimationChannel.Keyframed position = new AnimationChannel.Keyframed(List.of(
                Keyframe.plain(0.0, new Vec3(0.0F, 0.0F, 0.0F)),
                Keyframe.plain(length * 0.5, new Vec3(0.0F, 2.0F, 0.0F)),
                Keyframe.plain(length, new Vec3(0.0F, 2.0F, 0.0F))));
        return new BedrockAnimation(name, length, null, Map.of("root",
                new BoneAnimation("root", null, position, null)));
    }

    /** The authored landing shape: starts high, dips below the gait, then returns to it. */
    private static BedrockAnimation landing(String name, double length) {
        AnimationChannel.Keyframed position = new AnimationChannel.Keyframed(List.of(
                Keyframe.plain(0.0, new Vec3(0.0F, 2.0F, 0.0F)),
                Keyframe.plain(length * 0.5, new Vec3(0.0F, -2.0F, 0.0F)),
                Keyframe.plain(length, new Vec3(0.0F, 0.0F, 0.0F))));
        return new BedrockAnimation(name, length, null, Map.of("root",
                new BoneAnimation("root", null, position, null)));
    }
}
