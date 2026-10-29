package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Blends two first-person weapon placements, which is how aim-down-sights moves the weapon onto its sights.
 *
 * <h2>Why two placements rather than an offset</h2>
 * <p>A placement is the rigid transform that puts a weapon into camera space: scale, the Bedrock handedness mirror, the
 * inverse of the anchor bone's frame and the anchor pivot. {@link ViewBoneTransform#placement} derives it from geometry,
 * including every parent bone and authored pivot, so the sight alignment of {@code aiming_view} comes from where the author
 * put that bone - no weapon-specific numbers exist anywhere in this class.</p>
 *
 * <p>Aiming is therefore simply: hip placement at progress {@code 0}, {@code aiming_view} placement at progress {@code 1},
 * interpolated in between. Nothing is applied twice, because the blended matrix <em>replaces</em> the single placement the
 * renderer would otherwise use.</p>
 *
 * <h2>The mirror has to be factored out before interpolating</h2>
 * <p>A placement contains {@code scale(-1, 1, 1)}, which makes it an improper transform: its determinant is negative, so it
 * has no single rotation quaternion and a naive decomposition would produce nonsense. Every placement shares that mirror,
 * and it commutes with the uniform model scale, so this class removes it - {@code P' = M * P} - blends the two proper
 * transforms, and puts the mirror back on the left. The result is exact at both ends: progress {@code 0} reproduces the hip
 * matrix and progress {@code 1} reproduces the aiming matrix.</p>
 *
 * <h2>Rotation</h2>
 * <p>The two rotations are interpolated with a quaternion slerp along the shortest arc, so a placement whose anchor is
 * rotated by more than 180 degrees still blends the short way instead of spinning through a full turn. Translation and
 * scale are interpolated linearly, which is exact for a uniform scale.</p>
 */
public final class ViewPlacement {

    /** The Bedrock to Minecraft X handedness mirror every placement contains. */
    private static final Matrix4f MIRROR = new Matrix4f().scaling(-1.0F, 1.0F, 1.0F);

    private ViewPlacement() {
    }

    /**
     * @param model        the model being drawn
     * @param viewBoneName the anchor bone
     * @return the placement of that anchor, exactly as the renderer applies it
     */
    public static Matrix4f placement(WeaponModel model, String viewBoneName) {
        return ViewBoneTransform.placement(model, viewBoneName);
    }

    /**
     * Interpolates between the ordinary and the aiming placement.
     *
     * @param hip        the ordinary {@code default_view} placement
     * @param aiming     the {@code aiming_view} placement
     * @param progress   eased progress, {@code 0} for hip fire and {@code 1} for full aim; clamped and guarded
     * @return the blended placement
     */
    public static Matrix4f blend(Matrix4f hip, Matrix4f aiming, float progress) {
        if (hip == null) {
            return aiming == null ? new Matrix4f() : new Matrix4f(aiming);
        }
        if (aiming == null) {
            return new Matrix4f(hip);
        }
        float t = Float.isFinite(progress) ? Math.max(0.0F, Math.min(1.0F, progress)) : 0.0F;
        if (t <= 0.0F) {
            return new Matrix4f(hip);
        }
        if (t >= 1.0F) {
            return new Matrix4f(aiming);
        }

        Vector3f hipTranslation = new Vector3f();
        Vector3f hipScale = new Vector3f();
        Quaternionf hipRotation = new Quaternionf();
        Vector3f aimingTranslation = new Vector3f();
        Vector3f aimingScale = new Vector3f();
        Quaternionf aimingRotation = new Quaternionf();

        decompose(hip, hipTranslation, hipRotation, hipScale);
        decompose(aiming, aimingTranslation, aimingRotation, aimingScale);

        Vector3f translation = new Vector3f(hipTranslation).lerp(aimingTranslation, t);
        Vector3f scale = new Vector3f(hipScale).lerp(aimingScale, t);
        Quaternionf rotation = new Quaternionf(hipRotation).slerp(aimingRotation, t);

        Matrix4f blended = new Matrix4f().translation(translation).rotate(rotation).scale(scale);
        return MIRROR.mul(blended, blended);
    }

    /**
     * Splits a placement into the proper transform it contains.
     *
     * @param placement   the placement, mirror included
     * @param translation receives the translation
     * @param rotation    receives the rotation
     * @param scale       receives the scale
     */
    private static void decompose(Matrix4f placement, Vector3f translation, Quaternionf rotation, Vector3f scale) {
        Matrix4f proper = MIRROR.mul(placement, new Matrix4f());
        proper.getTranslation(translation);
        proper.getScale(scale);
        proper.getUnnormalizedRotation(rotation);
        rotation.normalize();
    }
}
