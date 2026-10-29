package com.arg_studios.arg_weaponry.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

/**
 * The camera path the recoil rides on: the authored camera-bone orientation is composed against the live camera angles
 * every frame (the camera-jitter fix) and the authored roll survives. Firing recoil is applied to the player's own angles,
 * so nothing in this path adds a per-shot rotation of its own.
 */
class CameraCompositionTest {

    private static final float TOLERANCE = 1.0E-4F;

    @Test
    void noTransformKeepsTheLiveCameraAngles() {
        CameraComposition.ComposedCamera composed = CameraComposition.compose(37.0F, -12.0F, null);

        assertEquals(37.0F, composed.yaw(), TOLERANCE);
        assertEquals(-12.0F, composed.pitch(), TOLERANCE);
        assertEquals(0.0F, composed.roll(), TOLERANCE);
    }

    @Test
    void theCompositionFollowsTheLiveBaseEveryFrame() {
        CameraTransform authored = rolledCameraSpace(15.0F);

        float firstYaw = CameraComposition.compose(10.0F, 4.0F, authored).yaw();
        float secondYaw = CameraComposition.compose(20.0F, 4.0F, authored).yaw();

        // The regression the hotfix fixed: the hook must answer with the base it was handed, not with a value sampled on
        // the last client tick, so a mouse move between two frames really turns the camera.
        assertEquals(10.0F, firstYaw, TOLERANCE);
        assertEquals(20.0F, secondYaw, TOLERANCE);
        assertEquals(10.0F, secondYaw - firstYaw, TOLERANCE, "mouse movement between frames must survive");
    }

    @Test
    void theAuthoredCameraRollIsPreserved() {
        CameraComposition.ComposedCamera composed = CameraComposition.compose(30.0F, 10.0F, rolledCameraSpace(12.0F));

        assertEquals(12.0F, composed.roll(), TOLERANCE, "the authored camera-bone roll must survive composition");
        assertEquals(30.0F, composed.yaw(), TOLERANCE);
        assertEquals(10.0F, composed.pitch(), TOLERANCE);
    }

    @Test
    void theSameFrameComposedTwiceGivesTheSameOrientation() {
        CameraTransform authored = rolledCameraSpace(12.0F);

        // Rendering the same frame twice - or a mod drawing the weapon pass twice - must not advance or accumulate
        // anything in the camera path.
        for (int repeat = 0; repeat < 50; repeat++) {
            CameraComposition.ComposedCamera composed =
                    CameraComposition.compose(30.0F, 10.0F, rolledCameraSpace(12.0F));
            assertEquals(30.0F, composed.yaw(), TOLERANCE);
            assertEquals(10.0F, composed.pitch(), TOLERANCE);
            assertEquals(12.0F, composed.roll(), TOLERANCE);
            assertEquals(12.0F, CameraComposition.compose(30.0F, 10.0F, authored).roll(), TOLERANCE);
        }
    }

    @Test
    void theAuthoredRollIsTheOnlyRollInTheCameraPath() {
        // No camera effect adds roll of its own any more: with an animation that carries none, the composed roll is
        // exactly zero and the camera keeps vanilla's rotation.
        CameraComposition.ComposedCamera flat = CameraComposition.compose(30.0F, 10.0F,
                new CameraTransform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F));

        assertEquals(30.0F, flat.yaw(), TOLERANCE);
        assertEquals(10.0F, flat.pitch(), TOLERANCE);
        assertEquals(0.0F, flat.roll(), TOLERANCE, "nothing may add a roll the animation does not author");
        assertTrue(flat.isUnchangedFrom(30.0F, 10.0F));
    }

    /**
     * @return a camera-space transform that asks for exactly that roll, the way a camera-bone animation does
     */
    private static CameraTransform rolledCameraSpace(float rollDegrees) {
        // CameraTransform carries the inverse of the rotation the camera applies, hence the negated angle.
        Quaternionf cameraSpace = new Quaternionf().rotationZ((float) Math.toRadians(-rollDegrees));
        return new CameraTransform(0.0F, 0.0F, rollDegrees, cameraSpace.x, cameraSpace.y, cameraSpace.z,
                cameraSpace.w, 0.0F, 0.0F, 0.0F);
    }
}
