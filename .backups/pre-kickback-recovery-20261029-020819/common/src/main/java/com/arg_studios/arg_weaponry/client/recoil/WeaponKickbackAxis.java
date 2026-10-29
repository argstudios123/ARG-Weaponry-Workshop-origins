package com.arg_studios.arg_weaponry.client.recoil;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * The weapon's longitudinal axis in camera space, derived from the placement the renderer actually uses.
 *
 * <h2>Why this class exists</h2>
 * <p>Kickback must move the weapon <em>backwards along its own barrel</em>, not along a guessed world axis. The direction
 * therefore cannot be a constant: it is whatever the model's backward axis becomes after the view anchor's placement has
 * been applied. A model whose {@code default_view} (or {@code aiming_view}) is rotated would need a different camera-space
 * direction than one whose anchor is axis-aligned, and hard-coding "Z" would be right for the shipped M100 DMR and wrong
 * for the next weapon.</p>
 *
 * <h2>What the numbers are for the shipped model</h2>
 * <p>Measured from the real geometry and the real placement ({@code ViewBoneTransform.placement(model, "default_view")}),
 * not assumed:</p>
 * <ul>
 *   <li>the placement's linear part is the Bedrock X mirror with no rotation ({@code diag(-1,1,1)} times the model
 *   scale), so the model's axes reach camera space unrotated;</li>
 *   <li>the geometry's longest extent is Z (50.4 units against 16.0 in Y and 7.7 in X), i.e. Z is the barrel axis;</li>
 *   <li>the {@code default_view} pivot sits 5.4 units from the {@code +Z} end and 45.0 units from the {@code -Z} end, so
 *   the eye is at the back and the muzzle is towards {@code -Z}.</li>
 * </ul>
 * <p>Forward is therefore {@code -Z} and <b>backwards, towards the player, is {@code +Z}</b> - for this model and for any
 * model whose anchor is not rotated. That matches the reference: Point Blank applies its recoil controller with
 * {@code poseStack.translate(posX, posY, posZ)} and feeds {@code sustainedPosZIncrement} as a positive value described as
 * the weapon moving backwards.</p>
 *
 * <h2>Where the translation is applied</h2>
 * <p>The kickback translation is applied to the camera-space pose stack <em>before</em> the placement is multiplied in, so
 * it must be expressed in camera space - which is exactly what this class returns. Applying it after the placement would
 * require the model-space axis instead, and would also let an ADS blend transform the kick into a different direction.</p>
 */
public final class WeaponKickbackAxis {

    /** The model-space direction that points from the muzzle back towards the player. */
    public static final Vector3f MODEL_BACKWARD = new Vector3f(0.0F, 0.0F, 1.0F);

    private WeaponKickbackAxis() {
    }

    /**
     * @param placement the placement that will be applied to the weapon, may be {@code null} for the identity
     * @return the unit vector, in camera space, that points backwards along the weapon's own axis; never {@code null}
     */
    public static Vector3f backward(Matrix4fc placement) {
        Vector3f axis = new Vector3f(MODEL_BACKWARD);
        if (placement == null) {
            return axis;
        }
        placement.transformDirection(axis);
        if (axis.lengthSquared() < 1.0E-9F) {
            // A degenerate placement cannot define an axis; falling back to the model axis keeps the weapon still sane
            // instead of producing NaN in the pose stack.
            return new Vector3f(MODEL_BACKWARD);
        }
        return axis.normalize();
    }

    /**
     * @param placement the placement that will be applied to the weapon, may be {@code null}
     * @param blocks    how far the weapon has to move backwards, in blocks
     * @return the camera-space translation to apply for that displacement
     */
    public static Vector3f offset(Matrix4fc placement, float blocks) {
        return backward(placement).mul(blocks);
    }
}
