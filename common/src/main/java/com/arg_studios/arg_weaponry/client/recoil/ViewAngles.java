package com.arg_studios.arg_weaponry.client.recoil;

/**
 * The player's two live view angles, and the only operation the recoil layer is allowed to perform on them.
 *
 * <h2>Why this exists</h2>
 * <p>Every camera-jitter bug this project has had came from the same mistake: capturing an absolute orientation, keeping
 * it for a while and writing it back over the player's current view. The fix is not "remember to be careful" - it is to
 * make the restoring operation impossible to express. This record has one method, {@link #plus(PlayerRecoilController.Delta)},
 * and it adds a <em>delta</em> to the angles it is given; there is no field, no setter and no constructor in the recoil
 * package that can hold an old orientation.</p>
 *
 * <p>It is also the one piece of the application step that can be tested without Minecraft: {@code plus} is a pure
 * function of (live angles, delta), so a test can move the "mouse" between two calls and assert that the movement
 * survives, which is exactly the property that the previous absolute-restore implementation broke.</p>
 *
 * <h2>Angle conventions</h2>
 * <p>Minecraft's own, unchanged: pitch is positive looking down and clamped to {@code [-90, 90]}, yaw is measured in
 * degrees and kept inside {@code (-180, 180]} the way {@code Mth.wrapDegrees} keeps it. The limits are applied here for
 * exactly the same reason vanilla applies them - no recoil configuration may push the camera past straight up or down,
 * because the mouse could not recover from that state.</p>
 *
 * @param pitchDegrees the live pitch, in degrees; positive looks down
 * @param yawDegrees   the live yaw, in degrees
 */
public record ViewAngles(float pitchDegrees, float yawDegrees) {

    /** Vanilla's lower pitch limit. */
    public static final float MIN_PITCH_DEGREES = -90.0F;

    /** Vanilla's upper pitch limit. */
    public static final float MAX_PITCH_DEGREES = 90.0F;

    /**
     * Adds one tick's recoil delta.
     *
     * <p>A positive delta pitch means "the aim rises", which is a <em>decrease</em> of Minecraft's pitch, and a positive
     * delta yaw means "the aim turns right", which is an increase of the yaw. Both are added to the values this record
     * was constructed with, i.e. to whatever the player's view was at the moment of the call, so any mouse movement
     * that happened since the previous tick is part of the result.</p>
     *
     * @param delta the delta to apply, may be {@code null}
     * @return the angles after the delta, never {@code null}
     */
    public ViewAngles plus(PlayerRecoilController.Delta delta) {
        if (delta == null || delta.isZero()) {
            return this;
        }
        float pitch = (float) (pitchDegrees - delta.pitchDegrees());
        float yaw = (float) (yawDegrees + delta.yawDegrees());
        if (!Float.isFinite(pitch) || !Float.isFinite(yaw)) {
            return this;
        }
        return new ViewAngles(clampPitch(pitch), wrapDegrees(yaw));
    }

    /**
     * @param pitch a pitch in degrees
     * @return the same pitch clamped to vanilla's {@code [-90, 90]} range
     */
    public static float clampPitch(float pitch) {
        return Math.max(MIN_PITCH_DEGREES, Math.min(MAX_PITCH_DEGREES, pitch));
    }

    /**
     * The same wrapping {@code Mth.wrapDegrees} performs, reimplemented here so the recoil package stays free of
     * Minecraft types and can be tested headlessly.
     *
     * @param degrees an angle in degrees
     * @return the same angle inside {@code (-180, 180]}
     */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }
}
