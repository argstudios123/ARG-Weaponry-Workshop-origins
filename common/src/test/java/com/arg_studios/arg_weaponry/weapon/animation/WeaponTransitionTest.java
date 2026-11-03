package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The ported TaCZ transition: timing, easing and the per-channel blend.
 *
 * <p>These cases pin the behaviour the animation system relies on: a fade starts on the pose that was on screen, the incoming
 * clip keeps playing underneath it, the blend is smooth at every frame of a short fade, a channel the incoming clip does not
 * animate returns to its authored value, and an interruption starts from the pose that is visible at that instant.</p>
 */
class WeaponTransitionTest {

    private static final double TICK = 0.05;

    @Test
    void aFadeStartsOnTheOutgoingPoseAndEndsOnTheLivePose() {
        WeaponPose from = pose("outgoing", 90.0);
        WeaponPose live = pose("incoming", -90.0);
        WeaponTransition transition = new WeaponTransition();

        transition.start(from, 0.1);
        assertTrue(transition.isActive());
        assertEquals(90.0, yaw(transition.apply(live, 0.0)), 1.0E-4,
                "the frame the clip changes still shows the outgoing pose");

        transition.advance(TICK);
        double halfway = yaw(transition.apply(live, 0.0));
        assertTrue(halfway < 90.0 && halfway > -90.0, "the fade passes through intermediate poses, was " + halfway);
        assertTrue(Math.abs(halfway) < 30.0, "a short fade must not spend most of its change in one tick, was " + halfway);

        transition.advance(TICK);
        transition.advance(TICK);
        assertFalse(transition.isActive());
        assertEquals(-90.0, yaw(transition.apply(live, 0.0)), 1.0E-4, "and it ends exactly on the live pose");
    }

    @Test
    void aShortFadeUsesTheCurveThatDoesNotSnap() {
        WeaponTransition transition = new WeaponTransition();
        transition.start(pose("outgoing", 90.0), 0.1);
        // Half of a 100 ms fade: TaCZ's own ease-out curve would already be at 93.75% here, i.e. a visible cut.
        assertEquals(WeaponTransition.smoothstep(0.5), transition.weight(0.05), 1.0E-6);
        assertTrue(transition.weight(0.05) < 0.6);
    }

    @Test
    void aLongFadeUsesThePortedTaCzCurve() {
        WeaponTransition transition = new WeaponTransition();
        transition.start(pose("outgoing", 90.0), 0.4);

        assertEquals(WeaponTransition.easeOutCubic(0.5), transition.weight(0.2), 1.0E-6);
        assertEquals(0.9375, transition.weight(0.2), 1.0E-6, "TaCZ's easeOutCubic, exactly");
    }

    @Test
    void aChannelOnlyTheOutgoingPoseAnimatesReturnsToTheAuthoredValue() {
        WeaponPose from = pose("outgoing", 90.0);
        WeaponPose live = WeaponPose.NONE;
        WeaponTransition transition = new WeaponTransition();
        transition.start(from, 0.1);

        transition.advance(TICK);
        double halfway = yaw(transition.apply(live, 0.0));
        assertTrue(halfway < 90.0 && halfway > 0.0,
                "a channel the incoming clip does not animate is recovered towards zero, was " + halfway);

        transition.advance(TICK);
        transition.advance(TICK);
        WeaponPose settled = transition.apply(live, 0.0);
        assertTrue(settled == null || settled.isEmpty(), "once the fade is over nothing is left of the outgoing pose");
    }

    @Test
    void anInterruptionStartsFromThePoseThatIsVisibleNow() {
        WeaponTransition transition = new WeaponTransition();
        transition.start(pose("first", 90.0), 0.1);
        transition.advance(TICK);
        WeaponPose visible = transition.apply(pose("second", -90.0), 0.0);

        // The controller captures exactly this pose before it changes the clip again.
        transition.start(visible, 0.1);
        assertEquals(yaw(visible), yaw(transition.apply(pose("third", 0.0), 0.0)), 1.0E-4,
                "the second fade starts where the first one was, so an interruption cannot jump");
    }

    @Test
    void aZeroLengthFadeIsIgnored() {
        WeaponTransition transition = new WeaponTransition();
        transition.start(pose("outgoing", 90.0), 0.0);

        assertFalse(transition.isActive());
        assertNull(transition.from());
        assertEquals(-90.0, yaw(transition.apply(pose("live", -90.0), 0.0)), 1.0E-6);
    }

    @Test
    void theSubTickOffsetAdvancesTheBlendInsideOneTick() {
        WeaponTransition transition = new WeaponTransition();
        transition.start(pose("outgoing", 90.0), 0.1);

        float atStart = transition.weight(0.0);
        float atHalfTick = transition.weight(0.025);
        float atEnd = transition.weight(0.05);
        assertTrue(atStart < atHalfTick && atHalfTick < atEnd,
                "the weight is a continuous function of time, not a per-tick step");
    }

    private static WeaponPose pose(String name, double yawDegrees) {
        Map<String, SampledBone> bones = new LinkedHashMap<>();
        bones.put("root", new SampledBone("root", new Vec3(0.0F, (float) yawDegrees, 0.0F), Vec3.ZERO,
                new Vec3(1.0F, 1.0F, 1.0F)));
        return new WeaponPose(name, 0.0, bones, java.util.List.of());
    }

    private static double yaw(WeaponPose pose) {
        if (pose == null) {
            return 0.0;
        }
        SampledBone bone = pose.bone("root");
        return bone == null || bone.rotation() == null ? 0.0 : bone.rotation().y();
    }
}
