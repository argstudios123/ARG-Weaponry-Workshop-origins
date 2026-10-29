package com.arg_studios.arg_weaponry.weapon.model;

/**
 * An immutable three-component vector used by the model data structures.
 *
 * <p>Deliberately Minecraft-free (no JOML), so the parser and model data can be tested without a
 * running game.</p>
 *
 * @param x the x component
 * @param y the y component
 * @param z the z component
 */
public record Vec3(float x, float y, float z) {

    public static final Vec3 ZERO = new Vec3(0.0F, 0.0F, 0.0F);

    public Vec3 {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("Non-finite vector component: (" + x + ", " + y + ", " + z + ")");
        }
    }

    public boolean isZero() {
        return x == 0.0F && y == 0.0F && z == 0.0F;
    }
}
