package com.arg_studios.arg_weaponry.client.camera;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.arg_studios.arg_weaponry.client.recoil.ViewShakeController;

/**
 * Combines the live camera orientation with a camera-bone transform, every frame, into the orientation the camera must have.
 *
 * <h2>Why this is a separate step</h2>
 * <p>Milestone 10S evaluated the camera-bone transform on the client tick and stored an <em>absolute</em> yaw/pitch in
 * {@link CameraTransform}. The camera hook then applied that absolute orientation every frame, which froze the view rotation
 * at the value sampled at the last tick: between two ticks the mouse could move as much as it liked, and the camera kept
 * answering with the angle of the tick that had already passed. That is the jitter "/ it feels like 5 FPS" report - the
 * world view advanced at 20 Hz while the frame rate stayed high, and it only happened while a clip that animates the
 * {@code camera} bone was playing, which is exactly firing, reloading and inspecting.</p>
 *
 * <p>The fix is to keep the two halves apart and combine them where the live value exists:</p>
 * <ul>
 *   <li>{@link CameraBoneAnimation#compute} turns the <em>animation</em> into a camera-space rotation and a translation. That
 *   is a pure function of the evaluated pose, so it can be sampled once per frame (or per tick) with no drift.</li>
 *   <li>{@link #compose} takes the camera's <em>own</em> current yaw and pitch - which vanilla has just interpolated from the
 *   player's angles with the frame's partial tick, mouse movement included - and returns the orientation with the animation
 *   on top. It is therefore a pure function of (live base, transform): applying it twice with the same inputs gives the same
 *   result, so nothing can accumulate, and when the transform is the identity the base comes back unchanged.</li>
 * </ul>
 *
 * <h2>The maths</h2>
 * <p>With {@code D} the bone's orientation as seen from the camera (see {@link CameraBoneAnimation}), the camera's new world
 * orientation is {@code R1 = R0 * D}, where {@code R0 = Ry(pi - yaw) * Rx(-pitch)} is what Minecraft stores for the live
 * angles. {@link CameraTransform} carries {@code R_cam = D^-1} because that is what the view model needs, so this class
 * inverts it back before composing. The result is decomposed into yaw, pitch and a roll about the view axis.</p>
 */
public final class CameraComposition {

    private CameraComposition() {
    }

    /**
     * @param yaw   the yaw the camera must have, in degrees
     * @param pitch the pitch the camera must have, in degrees
     * @param roll  the roll about the view axis, in degrees
     */
    public record ComposedCamera(float yaw, float pitch, float roll) {

        /**
         * @param baseYaw   the camera's own yaw
         * @param basePitch the camera's own pitch
         * @return whether this composition leaves that orientation exactly as it was
         */
        public boolean isUnchangedFrom(float baseYaw, float basePitch) {
            return Math.abs(wrap(yaw - baseYaw)) < 1.0E-4F && Math.abs(pitch - basePitch) < 1.0E-4F
                    && Math.abs(roll) < 1.0E-4F;
        }
    }

    /**
     * Applies a camera-space transform on top of a live camera orientation.
     *
     * @param baseYaw   the camera's current yaw in degrees, as vanilla computed it this frame
     * @param basePitch the camera's current pitch in degrees
     * @param transform the animation transform, or {@code null} for "no animation"
     * @return the orientation to set, which equals the base when there is nothing to apply
     */
    public static ComposedCamera compose(float baseYaw, float basePitch, CameraTransform transform) {
        if (transform == null || !transform.hasRotation()) {
            // Nothing to rotate: report the live angles so a frame with no rotation can never leave an older one in place.
            return new ComposedCamera(baseYaw, basePitch, 0.0F);
        }

        // R_cam -> D
        Quaternionf boneOrientation = viewModelRotation(transform).conjugate(new Quaternionf());
        Quaternionf base = new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(baseYaw),
                -(float) Math.toRadians(basePitch), 0.0F);
        Quaternionf wanted = base.mul(boneOrientation, new Quaternionf());
        Vector3f euler = wanted.getEulerAnglesYXZ(new Vector3f());
        return new ComposedCamera(wrap((float) Math.toDegrees(Math.PI - euler.y)),
                (float) -Math.toDegrees(euler.x), (float) Math.toDegrees(euler.z));
    }

    /**
     * @param transform a camera transform
     * @return its camera-space rotation quaternion, i.e. what the view model applies
     */
    public static Quaternionf viewModelRotation(CameraTransform transform) {
        return new Quaternionf(transform.rotationX(), transform.rotationY(), transform.rotationZ(),
                transform.rotationW());
    }

    /**
     * Builds the rotation a bounded screen shake contributes, <em>about the camera's own axes</em>.
     *
     * <h4>Why it is a rotation and not an angle edit</h4>
     * <p>The previous implementation re-set the camera from {@code yaw + shakeYaw} and {@code pitch + shakePitch}.
     * That has two defects which are visible exactly while a camera-bone clip is playing - i.e. while firing:</p>
     * <ul>
     *   <li>{@code setRotation} rebuilds the camera quaternion from two angles, so it <strong>discarded the authored
     *   camera-bone roll</strong> for every frame the shake was active and re-created it for every frame it was not,
     *   which reads as the roll flickering;</li>
     *   <li>adding yaw in Euler space is only a turn about the camera's own up axis when the pitch is zero; at a
     *   non-zero pitch it mixes into roll, so the authored rotation appeared to change shape while the shake ran.</li>
     * </ul>
     * <p>Composing the shake as a quaternion <em>after</em> the composed orientation is both correct and additive: no
     * component overwrites another, and the shake can never leave a permanent change behind because the next frame
     * starts again from vanilla's own orientation.</p>
     *
     * <p>The signs follow Minecraft's angle conventions: a positive shake pitch looks down and a positive shake yaw
     * turns right, so the composition uses {@code Ry(-yaw) * Rx(-pitch) * Rz(roll)} - the same relationship vanilla's
     * own {@code setRotation} uses for the base orientation.</p>
     *
     * @param shake the frame's shake, may be {@code null}
     * @return the rotation to multiply into the camera, identity when there is no shake
     */
    public static Quaternionf shakeRotation(ViewShakeController.ShakeAngles shake) {
        if (shake == null || shake.isZero()) {
            return new Quaternionf();
        }
        float yaw = (float) Math.toRadians(shake.yawDegrees());
        float pitch = (float) Math.toRadians(shake.pitchDegrees());
        float roll = (float) Math.toRadians(shake.rollDegrees());
        return new Quaternionf().rotationYXZ(-yaw, -pitch, roll);
    }

    /**
     * @param degrees an angle in degrees
     * @return the same angle wrapped into {@code (-180, 180]}
     */
    public static float wrap(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        } else if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }
}
