package com.arg_studios.arg_weaponry.client.camera;

/**
 * The first-person camera change one animation frame asks for, expressed in Minecraft's own units.
 *
 * <p>Milestone 10S stored <em>absolute</em> angles here, sampled on the client tick, and the camera hook applied them every
 * frame - which is what froze the view to 20 Hz during any clip that animates the {@code camera} bone. Since the hotfix this
 * record contains only pose-derived data: the yaw/pitch/roll are the rotation measured against a zero base (a diagnostic and
 * convenience form), and the final orientation is produced per frame by {@link CameraComposition#compose} from the live
 * camera angles.</p>
 *
 * <p>A value object with no Minecraft types in it, so the whole conversion can be verified headlessly. It carries the
 * three things a camera animation needs:</p>
 * <ul>
 *   <li>the yaw, pitch and roll of the transform relative to a zero base orientation,</li>
 *   <li>the <strong>camera-space rotation</strong> as a quaternion - the same rotation the level camera receives, which the
 *   first-person view model applies to stay visually locked to the world,</li>
 *   <li>the <strong>camera-space translation</strong> in blocks, applied to the camera position and with the opposite sign to
 *   the view model.</li>
 * </ul>
 *
 * @param yaw            the transform's yaw in degrees, relative to a zero base orientation
 * @param pitch          the transform's pitch in degrees, relative to a zero base orientation
 * @param roll           the transform's roll in degrees, applied about the view axis after yaw and pitch
 * @param rotationX      the X component of the camera-space rotation quaternion
 * @param rotationY      the Y component of the camera-space rotation quaternion
 * @param rotationZ      the Z component of the camera-space rotation quaternion
 * @param rotationW      the W component of the camera-space rotation quaternion
 * @param translationX   the camera-space X offset in blocks
 * @param translationY   the camera-space Y offset in blocks
 * @param translationZ   the camera-space Z offset in blocks
 */
public record CameraTransform(float yaw, float pitch, float roll, float rotationX, float rotationY, float rotationZ,
                              float rotationW, float translationX, float translationY, float translationZ) {

    public CameraTransform {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(roll)
                || !Float.isFinite(rotationX) || !Float.isFinite(rotationY) || !Float.isFinite(rotationZ)
                || !Float.isFinite(rotationW) || !Float.isFinite(translationX) || !Float.isFinite(translationY)
                || !Float.isFinite(translationZ)) {
            throw new IllegalArgumentException("A camera transform must be finite");
        }
    }

    /** @return whether the rotation part actually changes anything. */
    public boolean hasRotation() {
        return rotationX != 0.0F || rotationY != 0.0F || rotationZ != 0.0F || rotationW != 1.0F;
    }

    /** @return whether the position part actually changes anything. */
    public boolean hasTranslation() {
        return translationX != 0.0F || translationY != 0.0F || translationZ != 0.0F;
    }

    /** @return whether this transform would change the camera at all. */
    public boolean isIdentity() {
        return !hasRotation() && !hasTranslation();
    }
}
