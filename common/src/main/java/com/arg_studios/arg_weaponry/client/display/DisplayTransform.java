package com.arg_studios.arg_weaponry.client.display;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * One display transform as authored in the display configuration.
 *
 * <p>The values are kept exactly as written; nothing is clamped or reordered. The rotation is in
 * degrees and is applied as Z, then Y, then X when a context needs it, matching the convention the
 * model format uses.</p>
 *
 * @param translation the translation in 1/16 block units, as authored
 * @param rotation    the rotation in degrees, as authored
 * @param scale       the scale, as authored
 */
public record DisplayTransform(Vec3 translation, Vec3 rotation, Vec3 scale) {

    /** The transform used when a context declares nothing: no offset, no rotation, unit scale. */
    public static final DisplayTransform IDENTITY =
            new DisplayTransform(Vec3.ZERO, Vec3.ZERO, new Vec3(1.0F, 1.0F, 1.0F));

    public DisplayTransform {
        if (translation == null) {
            translation = Vec3.ZERO;
        }
        if (rotation == null) {
            rotation = Vec3.ZERO;
        }
        if (scale == null) {
            scale = new Vec3(1.0F, 1.0F, 1.0F);
        }
    }

    /** @return {@code true} when the transform would not change anything. */
    public boolean isIdentity() {
        return translation.isZero() && rotation.isZero()
                && scale.x() == 1.0F && scale.y() == 1.0F && scale.z() == 1.0F;
    }
}
