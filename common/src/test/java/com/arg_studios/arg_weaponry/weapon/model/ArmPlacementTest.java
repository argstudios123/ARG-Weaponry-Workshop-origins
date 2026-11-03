package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.client.render.ArmPlacement;
import com.arg_studios.arg_weaponry.weapon.model.BoneMesh;

/**
 * The mapping from a native player arm onto an arm rig bone's reference cube.
 *
 * <p>The arm is drawn inside the bone frame, so its <em>box</em> has to land exactly on the box of the authored
 * {@code right_hand}/{@code left_hand} cubes: same centre, same size, and the same orientation rule (the arm's shoulder is the
 * end away from the pivot, so the hand sits at the wrist). These cases pin that mapping without a running game.</p>
 */
class ArmPlacementTest {

    /** The shipped M100 DMR's {@code right_hand} cube: origin (-4.0015, -0.6913, 8.14486), size 4 x 12 x 4. */
    private static final Vec3 RIGHT_CUBE_ORIGIN = new Vec3(-4.0015F, -0.6913F, 8.14486F);
    private static final Vec3 RIGHT_CUBE_SIZE = new Vec3(4.0F, 12.0F, 4.0F);

    /** A classic player's right arm as Minecraft reports it: 4 x 12 x 4 pixels at x in [-8, -4], y in [0, 12]. */
    private static final ArmPlacement.Box NATIVE_ARM = new ArmPlacement.Box(
            new Vec3(-0.5F, 0.0F, -0.125F), new Vec3(-0.25F, 0.75F, 0.125F));

    @Test
    void theArmBoxLandsExactlyOnTheReferenceCube() {
        ArmPlacement.Box reference = cubeBox();
        ArmPlacement.Placement placement = ArmPlacement.solve(reference, NATIVE_ARM);

        assertNotNull(placement);
        ArmPlacement.Box mapped = placement.map(NATIVE_ARM);
        assertBoxEquals(reference, mapped);
    }

    @Test
    void theHandOfTheArmSitsAtTheWristEndOfTheCube() {
        ArmPlacement.Placement placement = ArmPlacement.solve(cubeBox(), NATIVE_ARM);

        // The native arm's hand is its +Y end (Minecraft's model has Y pointing down from the shoulder), and the reference
        // cube's pivot sits near its -Y end, which is where the author rotates the hand from.
        Vec3 shoulder = placement.map(new Vec3(-0.375F, 0.0F, 0.0F));
        Vec3 hand = placement.map(new Vec3(-0.375F, 0.75F, 0.0F));

        assertEquals(-2.0015F, shoulder.x(), 0.01F, "the arm's centre line is the cube's centre line");
        assertEquals(-2.0015F, hand.x(), 0.01F);
        assertEquals(11.3087F, shoulder.y(), 0.01F, "the shoulder is the arm's far end");
        assertEquals(-0.6913F, hand.y(), 0.01F, "the hand is at the cube end the bone pivots from");
    }

    @Test
    void aSlimArmStaysCentredInsteadOfBeingStretched() {
        ArmPlacement.Box slim = new ArmPlacement.Box(new Vec3(-0.5F, 0.0F, -0.125F),
                new Vec3(-0.3125F, 0.75F, 0.125F));
        ArmPlacement.Box reference = cubeBox();
        ArmPlacement.Placement placement = ArmPlacement.solve(reference, slim);

        assertNotNull(placement);
        ArmPlacement.Box mapped = placement.map(slim);
        assertEquals(reference.centre().y(), mapped.centre().y(), 0.01F);
        assertEquals(3.0F, mapped.size().x(), 0.01F, "a slim arm is one pixel narrower, never widened to four");
        assertEquals(12.0F, mapped.size().y(), 0.01F);
    }

    @Test
    void aBoneWithoutCubeGeometryHasNoReferenceBox() {
        ModelBone anchorOnly = new ModelBone("RH", null, Vec3.ZERO, Vec3.ZERO, List.of(), List.of());

        assertNull(ArmPlacement.referenceBox(anchorOnly));
        assertNull(ArmPlacement.solve(null, NATIVE_ARM));
    }

    private static ArmPlacement.Box cubeBox() {
        return new ArmPlacement.Box(RIGHT_CUBE_ORIGIN,
                new Vec3(RIGHT_CUBE_ORIGIN.x() + RIGHT_CUBE_SIZE.x(),
                        RIGHT_CUBE_ORIGIN.y() + RIGHT_CUBE_SIZE.y(),
                        RIGHT_CUBE_ORIGIN.z() + RIGHT_CUBE_SIZE.z()));
    }

    private static void assertBoxEquals(ArmPlacement.Box expected, ArmPlacement.Box actual) {
        assertEquals(expected.min().x(), actual.min().x(), 0.01F);
        assertEquals(expected.min().y(), actual.min().y(), 0.01F);
        assertEquals(expected.min().z(), actual.min().z(), 0.01F);
        assertEquals(expected.max().x(), actual.max().x(), 0.01F);
        assertEquals(expected.max().y(), actual.max().y(), 0.01F);
        assertEquals(expected.max().z(), actual.max().z(), 0.01F);
    }
}
