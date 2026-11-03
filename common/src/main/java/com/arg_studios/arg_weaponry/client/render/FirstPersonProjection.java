package com.arg_studios.arg_weaponry.client.render;

/**
 * The near plane used by Minecraft's first-person hand/screen pass.
 *
 * <p>Vanilla sets up the hand projection with a near plane of {@code 0.05} blocks. A first-person weapon is authored in
 * the same camera space as the hand, and a stock, magazine or optic can legitimately sit closer to the eye than five
 * centimetres. Geometry between the camera and that near plane is clipped, which is what looks like a hole or a missing
 * part of the gun.</p>
 *
 * <p>The fix is deliberately local to the hand pass: the world projection keeps its own near plane, the depth test stays
 * enabled, and no model is scaled or pushed away from the camera. Tightening the hand pass's near plane lets the authored
 * geometry reach the camera without being cut off, while the world still culls and clips exactly as before.</p>
 */
public final class FirstPersonProjection {

    /**
     * The tightened near plane, in blocks. One millimetre is far below any authored first-person weapon surface and still
     * leaves ample depth precision in the short hand pass.
     */
    public static final float NEAR_PLANE = 0.001F;

    private FirstPersonProjection() {
    }

    /**
     * @param vanillaNearPlane the near plane Minecraft asked for
     * @return the tighter near plane, or the argument unchanged when it is already tighter, non-finite or non-positive
     */
    public static float nearPlane(float vanillaNearPlane) {
        if (!Float.isFinite(vanillaNearPlane) || vanillaNearPlane <= 0.0F) {
            return vanillaNearPlane;
        }
        return Math.min(vanillaNearPlane, NEAR_PLANE);
    }
}
