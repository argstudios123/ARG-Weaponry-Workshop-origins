package com.arg_studios.arg_weaponry.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.client.recoil.ViewShakeController;

/**
 * The camera path the screen shake rides on: the authored camera-bone orientation is composed against the live camera
 * angles every frame (the camera-jitter fix), the authored roll survives, and the shake is multiplied on top instead of
 * rebuilding the orientation from yaw and pitch.
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
    void theShakeRotationIsIdentityWithoutShake() {
        assertQuaternionEquals(new Quaternionf(), CameraComposition.shakeRotation(null));
        assertQuaternionEquals(new Quaternionf(),
                CameraComposition.shakeRotation(ViewShakeController.ShakeAngles.NONE));
    }

    @Test
    void theShakeRotationTurnsRightAndRollsAboutTheViewAxis() {
        Quaternionf yaw = CameraComposition.shakeRotation(new ViewShakeController.ShakeAngles(0.0, 5.0, 0.0));
        assertQuaternionEquals(new Quaternionf().rotationYXZ((float) -Math.toRadians(5.0), 0.0F, 0.0F), yaw);

        Quaternionf roll = CameraComposition.shakeRotation(new ViewShakeController.ShakeAngles(0.0, 0.0, 5.0));
        assertQuaternionEquals(new Quaternionf().rotationZ((float) Math.toRadians(5.0)), roll);
    }

    @Test
    void theShakeIsComposedOnTopOfTheAuthoredOrientation() {
        // An authored transform that carries a roll, as an aiming pose does.
        CameraComposition.ComposedCamera composed = CameraComposition.compose(30.0F, 10.0F, rolledCameraSpace(12.0F));
        Quaternionf authored = cameraQuaternion(composed);

        ViewShakeController.ShakeAngles shake = new ViewShakeController.ShakeAngles(0.0, 1.2, -0.8);
        Quaternionf shaken = new Quaternionf(authored).mul(CameraComposition.shakeRotation(shake));

        assertFalse(authored.equals(shaken, TOLERANCE), "the shake must actually change the camera");

        // Post-multiplying keeps the authored orientation intact: undoing the shake gives it back exactly, which is what
        // "the shake must not discard the authored roll" means in practice.
        Quaternionf restored = shaken.mul(new Quaternionf(CameraComposition.shakeRotation(shake)).conjugate());
        assertQuaternionEquals(authored, restored);
    }

    /**
     * @param rollDegrees the roll the camera must end up with
     * @return a camera-space transform that asks for exactly that roll, the way a camera-bone animation does
     */
    private static CameraTransform rolledCameraSpace(float rollDegrees) {
        // CameraTransform carries the inverse of the rotation the camera applies, hence the negated angle.
        Quaternionf cameraSpace = new Quaternionf().rotationZ((float) Math.toRadians(-rollDegrees));
        return new CameraTransform(0.0F, 0.0F, rollDegrees, cameraSpace.x, cameraSpace.y, cameraSpace.z,
                cameraSpace.w, 0.0F, 0.0F, 0.0F);
    }

    /**
     * @param composed the composed orientation
     * @return the camera rotation the hook builds from it, in the same order the mixin uses
     */
    private static Quaternionf cameraQuaternion(CameraComposition.ComposedCamera composed) {
        return new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(composed.yaw()),
                (float) -Math.toRadians(composed.pitch()), 0.0F)
                .mul(new Quaternionf().rotationZ((float) Math.toRadians(composed.roll())));
    }

    private static void assertQuaternionEquals(Quaternionf expected, Quaternionf actual) {
        assertTrue(expected.equals(actual, TOLERANCE), "expected " + expected + " but was " + actual);
    }
}
