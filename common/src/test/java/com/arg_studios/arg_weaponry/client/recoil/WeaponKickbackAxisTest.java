package com.arg_studios.arg_weaponry.client.recoil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The kickback axis: the displacement follows the weapon's own barrel, so it never gains an upward or sideways component,
 * and it follows whatever placement the renderer is using - including the blended aim-down-sights placement.
 */
class WeaponKickbackAxisTest {

    private static final float TOLERANCE = 1.0E-5F;

    @Test
    void anUnrotatedPlacementMovesTheWeaponStraightBackAlongItsOwnAxis() {
        Matrix4f placement = new Matrix4f().scale(0.0625F, 0.0625F, 0.0625F);

        Vector3f offset = WeaponKickbackAxis.offset(placement, 0.02F);

        assertEquals(0.0F, offset.x, TOLERANCE, "kickback must not move the weapon sideways");
        assertEquals(0.0F, offset.y, TOLERANCE, "kickback must not move the weapon upwards");
        assertEquals(0.02F, offset.z, TOLERANCE, "kickback moves the weapon towards the player, along +Z");
        assertEquals(0.02F, offset.length(), TOLERANCE, "the displacement is exactly the requested distance");
    }

    @Test
    void aNullPlacementFallsBackToTheModelAxis() {
        Vector3f offset = WeaponKickbackAxis.offset(null, 0.05F);

        assertEquals(0.0F, offset.x, TOLERANCE);
        assertEquals(0.0F, offset.y, TOLERANCE);
        assertEquals(0.05F, offset.z, TOLERANCE);
    }

    @Test
    void theDirectionFollowsThePlacementTheRendererUses() {
        // A rotated anchor, as a weapon whose default_view is turned would have.
        Matrix4f placement = new Matrix4f().rotationY((float) Math.toRadians(35.0))
                .scale(0.0625F, 0.0625F, 0.0625F);

        Vector3f offset = WeaponKickbackAxis.offset(placement, 0.02F);

        Vector3f expected = new Matrix4f(placement).transformDirection(new Vector3f(0.0F, 0.0F, 1.0F))
                .normalize().mul(0.02F);
        assertEquals(expected.x, offset.x, TOLERANCE);
        assertEquals(expected.y, offset.y, TOLERANCE);
        assertEquals(expected.z, offset.z, TOLERANCE);
        assertTrue(Math.abs(offset.x) > 1.0E-3F, "the axis must really follow the placement, not ignore it");
        assertEquals(0.02F, offset.length(), TOLERANCE, "rotation must not change how far the weapon moves");
    }

    @Test
    void theKickStaysAlongTheBarrelWhenAimingRotatesTheWeapon() {
        Matrix4f aiming = new Matrix4f().rotationX((float) Math.toRadians(-8.0))
                .rotateY((float) Math.toRadians(12.0))
                .scale(0.0625F, 0.0625F, 0.0625F);

        Vector3f cameraSpace = WeaponKickbackAxis.offset(aiming, 0.02F);

        assertEquals(0.02F, cameraSpace.length(), TOLERANCE, "the distance is the requested amplitude");
        assertTrue(Math.abs(cameraSpace.x) > 1.0E-4F || Math.abs(cameraSpace.y) > 1.0E-4F,
                "an aiming rotation must really change the camera-space direction");

        // Undo the placement: the displacement must be the pure model-space barrel axis, i.e. backwards and nothing else.
        Vector3f modelSpace = new Matrix4f(aiming).invert().transformDirection(new Vector3f(cameraSpace)).normalize();
        assertEquals(0.0F, modelSpace.x, TOLERANCE, "no sideways component in model space");
        assertEquals(0.0F, modelSpace.y, TOLERANCE, "no upward component in model space");
        assertEquals(1.0F, modelSpace.z, TOLERANCE, "the model-space direction is the barrel");
    }
}
