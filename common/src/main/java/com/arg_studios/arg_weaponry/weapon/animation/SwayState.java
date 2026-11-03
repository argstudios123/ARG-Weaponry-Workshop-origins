package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The weapon-sway spring: a small, pure integrator that turns mouse movement into a weapon offset.
 *
 * <h2>What it is</h2>
 * <p>Sway is the weapon lagging behind the camera. Moving the mouse rotates the camera instantly; the weapon in the
 * player's hands follows a moment later and keeps a little of the movement, then settles back to rest. That is what this
 * class computes: two damped values - one for the horizontal and one for the vertical axis - updated from the look delta of
 * the frame, and read back as a rotation and a small translation.</p>
 *
 * <h2>Why it is separate from the pose</h2>
 * <p>Nothing here knows about clips, bones or the animation controller. Sway is an <em>additive</em> transform applied on top
 * of whatever pose is drawn, exactly like the recoil kickback, so it composes with idle, walking, running, aiming and every
 * action clip without any of them having to be aware of it - and it can never overwrite an authored pose.</p>
 *
 * <h2>The settings, and what each one really does</h2>
 * <ul>
 *   <li>{@code weapon_sway_type} selects a {@link WeaponSwayStyle}: the gain, the rotation ceiling, the roll share, the
 *   position scale, the damping ratio and whether the axis integrates a velocity.</li>
 *   <li>{@code sway_sensitivity} is the <strong>input gain</strong>. The target rotation is derived from the camera's turn
 *   <em>rate</em> ({@code degrees per second}) multiplied by this value and the style's response, then clamped to the
 *   style's ceiling. A higher sensitivity therefore reaches the ceiling with a slower movement, so it changes the shape of
 *   the response rather than only its size.</li>
 *   <li>{@code intensity} scales the <strong>output</strong>: the rotation and the translation handed to the renderer. It
 *   changes how far the weapon visibly moves at any given response, and {@code 0} disables the effect.</li>
 *   <li>{@code smoothing} is the rate at which the weapon follows a new mouse movement, in inverse seconds.</li>
 *   <li>{@code return_speed} is the rate at which it settles once the mouse stops.</li>
 *   <li>{@code aim_multiplier} scales everything while aiming, so aiming can be steadier than hip fire ({@code < 1}) or
 *   deliberately heavier ({@code > 1}).</li>
 * </ul>
 *
 * <p>The {@code vanilla} style is a first-order follow: it cannot overshoot and it stops as soon as the camera does. The
 * {@code rotatory} and {@code type_1} styles integrate a velocity with an underdamped spring, which produces rotational
 * inertia: the weapon keeps turning for a moment after the camera stops, then settles. All rates are applied with a
 * frame-rate independent step (an exact exponential for the first-order style, fixed sub-steps for the springs).</p>
 */
public final class SwayState {

    /** Absolute hard cap on the displayed rotation, in degrees, whatever the style and intensity say. */
    public static final double MAX_ROTATION_DEGREES = 20.0;

    /** Below this camera movement the mouse counts as still, so the spring returns instead of chasing noise. */
    private static final double STILL_RADIANS = 1.0E-4;

    /** Longest step one update may integrate, so a stall cannot overshoot the spring. */
    private static final double MAX_STEP_SECONDS = 0.1;

    /** Fixed integration step of the inertial springs, so a long frame cannot destabilise them. */
    private static final double MAX_SUBSTEP_SECONDS = 0.0025;

    /** Largest share of a style's rotation ceiling one frame's raw mouse impulse may add. */
    private static final double MAX_INPUT_IMPULSE_FRACTION = 0.25;

    /**
     * A mouse movement faster than this is a deliberate hard flick rather than ordinary tracking. The immediate impulse
     * is skipped there so the style's own inertia still produces the visible follow-through; the smoothed spring keeps
     * responding normally.
     */
    private static final double MAX_INPUT_IMPULSE_RATE = 360.0;

    /** How far the displayed rotation may exceed the style's own ceiling ({@code intensity} above 1). */
    private static final double MAX_DISPLAY_FACTOR = 2.5;

    /** Largest accepted aim scale, so a broken configuration cannot throw the weapon away. */
    private static final double MAX_AIM_SCALE = 10.0;

    /** How deep the internal spring may go before it is considered settled. */
    private static final double REST_EPSILON = 1.0E-4;

    private double yaw;
    private double pitch;
    private double yawVelocity;
    private double pitchVelocity;
    /** Immediate mouse-response offsets, separate from the smoothed spring above. */
    private double yawImpulse;
    private double pitchImpulse;
    private double displayedYaw;
    private double displayedPitch;
    private WeaponSwayStyle style = WeaponSwayStyle.VANILLA;
    private boolean resting = true;

    /**
     * Advances the spring by one frame.
     *
     * @param yawDeltaRadians   how far the camera turned horizontally since the previous frame, in radians
     * @param pitchDeltaRadians how far the camera turned vertically since the previous frame, in radians
     * @param deltaSeconds      the frame time, in seconds
     * @param settings          the weapon's sway configuration
     * @param aimScale          the multiplier for the current aiming state, {@code 1} when not aiming
     */
    public void update(double yawDeltaRadians, double pitchDeltaRadians, double deltaSeconds,
                       WeaponDefinition.Sway settings, double aimScale) {
        if (settings == null || !settings.active()) {
            reset();
            return;
        }
        style = settings.style() == null ? WeaponSwayStyle.VANILLA : settings.style();
        double delta = Double.isFinite(deltaSeconds)
                ? Math.max(0.0, Math.min(MAX_STEP_SECONDS, deltaSeconds)) : 0.0;
        if (delta <= 0.0) {
            return;
        }
        double yawDegrees = Double.isFinite(yawDeltaRadians) ? Math.toDegrees(yawDeltaRadians) : 0.0;
        double pitchDegrees = Double.isFinite(pitchDeltaRadians) ? Math.toDegrees(pitchDeltaRadians) : 0.0;
        boolean still = Math.abs(yawDegrees) < Math.toDegrees(STILL_RADIANS)
                && Math.abs(pitchDegrees) < Math.toDegrees(STILL_RADIANS);

        double gain = settings.sensitivity() * style.response();
        double ceiling = style.maxRotationDegrees();
        // Turning right (positive yaw) swings the weapon to the left of the crosshair, which is the lag the player sees;
        // looking up (negative pitch) lifts the muzzle. Both are expressed in degrees of weapon rotation.
        double targetYaw = still ? 0.0 : clamp(-(yawDegrees / delta) * gain, ceiling);
        double targetPitch = still ? 0.0 : clamp(-(pitchDegrees / delta) * gain, ceiling);
        double rate = Math.max(WeaponDefinition.Sway.MIN_RATE, still ? settings.returnSpeed() : settings.smoothing());

        // Immediate response. The spring above is deliberately smoothed, but a mouse that moves must move the weapon on
        // the same frame: a share of the frame's angular movement is added to a separate impulse that decays at
        // return_speed. This is frame-rate independent because the impulse is proportional to the total angle moved, not
        // to the frame duration; splitting one flick across more frames adds the same total.
        double impulseRate = Math.max(WeaponDefinition.Sway.MIN_RATE,
                still ? settings.returnSpeed() : settings.smoothing());
        double impulseDecay = Math.exp(-impulseRate * delta);
        yawImpulse *= impulseDecay;
        pitchImpulse *= impulseDecay;
        if (!still) {
            double impulseGain = settings.sensitivity() * style.inputResponse();
            double impulseLimit = ceiling * MAX_INPUT_IMPULSE_FRACTION;
            double yawRate = Math.abs(yawDegrees / delta);
            double pitchRate = Math.abs(pitchDegrees / delta);
            if (yawRate <= MAX_INPUT_IMPULSE_RATE) {
                yawImpulse = clamp(yawImpulse + clamp(-yawDegrees * impulseGain, impulseLimit), ceiling);
            }
            if (pitchRate <= MAX_INPUT_IMPULSE_RATE) {
                pitchImpulse = clamp(pitchImpulse + clamp(-pitchDegrees * impulseGain, impulseLimit), ceiling);
            }
        }

        if (style.inertia()) {
            integrateSpring(rate, targetYaw, targetPitch, delta, ceiling);
        } else {
            double alpha = 1.0 - Math.exp(-rate * delta);
            yaw += (targetYaw - yaw) * alpha;
            pitch += (targetPitch - pitch) * alpha;
            yawVelocity = 0.0;
            pitchVelocity = 0.0;
        }
        yaw = clamp(yaw, ceiling);
        pitch = clamp(pitch, ceiling);

        double scale = settings.intensity() * clampAimScale(aimScale);
        double displayCeiling = Math.min(MAX_ROTATION_DEGREES, ceiling * Math.max(1.0, MAX_DISPLAY_FACTOR));
        double totalYaw = clamp(yaw + yawImpulse, ceiling);
        double totalPitch = clamp(pitch + pitchImpulse, ceiling);
        displayedYaw = clamp(totalYaw * scale, displayCeiling);
        displayedPitch = clamp(totalPitch * scale, displayCeiling);

        resting = still && Math.abs(yaw) < REST_EPSILON && Math.abs(pitch) < REST_EPSILON
                && Math.abs(yawVelocity) < REST_EPSILON && Math.abs(pitchVelocity) < REST_EPSILON
                && Math.abs(yawImpulse) < REST_EPSILON && Math.abs(pitchImpulse) < REST_EPSILON;
        if (resting) {
            // Settle exactly, so an idle weapon is bit-for-bit at rest instead of asymptotically approaching it.
            yaw = 0.0;
            pitch = 0.0;
            yawVelocity = 0.0;
            pitchVelocity = 0.0;
            yawImpulse = 0.0;
            pitchImpulse = 0.0;
            displayedYaw = 0.0;
            displayedPitch = 0.0;
        }
    }

    /**
     * Integrates one of the inertial styles with a damped spring.
     *
     * <p>The spring is stepped in fixed short sub-steps, so a long frame is integrated the same way several short frames
     * would be instead of exploding. That is what makes the inertia frame-rate independent and stable at every configured
     * rate.</p>
     *
     * @param rate        the style's rate, in inverse seconds
     * @param targetYaw   the horizontal target, in degrees
     * @param targetPitch the vertical target, in degrees
     * @param delta       the clamped frame time
     * @param ceiling     the style's rotation ceiling, in degrees
     */
    private void integrateSpring(double rate, double targetYaw, double targetPitch, double delta, double ceiling) {
        double omega = Math.max(0.1, rate) * style.stiffnessScale();
        double damping = 2.0 * style.dampingRatio() * omega;
        double stiffness = omega * omega;
        double remaining = delta;
        while (remaining > 1.0E-9) {
            double step = Math.min(remaining, MAX_SUBSTEP_SECONDS);
            yawVelocity += (stiffness * (targetYaw - yaw) - damping * yawVelocity) * step;
            pitchVelocity += (stiffness * (targetPitch - pitch) - damping * pitchVelocity) * step;
            yaw += yawVelocity * step;
            pitch += pitchVelocity * step;
            clampSpring(ceiling);
            remaining -= step;
        }
    }

    /**
     * Keeps the spring inside its ceiling without leaving a velocity that keeps pushing through it.
     *
     * @param ceiling the style's rotation ceiling, in degrees
     */
    private void clampSpring(double ceiling) {
        if (yaw > ceiling) {
            yaw = ceiling;
            yawVelocity = Math.min(0.0, yawVelocity);
        } else if (yaw < -ceiling) {
            yaw = -ceiling;
            yawVelocity = Math.max(0.0, yawVelocity);
        }
        if (pitch > ceiling) {
            pitch = ceiling;
            pitchVelocity = Math.min(0.0, pitchVelocity);
        } else if (pitch < -ceiling) {
            pitch = -ceiling;
            pitchVelocity = Math.max(0.0, pitchVelocity);
        }
    }

    /** @return the horizontal sway in degrees, negative when the weapon lags to the left. */
    public double rotationY() {
        return displayedYaw;
    }

    /** @return the vertical sway in degrees. */
    public double rotationX() {
        return displayedPitch;
    }

    /** @return the roll in degrees, derived from the horizontal sway by the style's roll share. */
    public double rotationZ() {
        return -displayedYaw * style.rollShare();
    }

    /** @return the horizontal translation, in model units. */
    public double positionX() {
        return -displayedYaw * style.positionPerDegree();
    }

    /** @return the vertical translation, in model units. */
    public double positionY() {
        return -displayedPitch * style.positionPerDegree();
    }

    /** @return the depth translation, in model units; the weapon moves back slightly while it is off-centre. */
    public double positionZ() {
        return (Math.abs(displayedYaw) + Math.abs(displayedPitch)) * style.positionPerDegree() * 0.5;
    }

    /** @return the style the last update used, for diagnostics and tests. */
    public WeaponSwayStyle style() {
        return style;
    }

    /** @return whether the spring is exactly at rest. */
    public boolean atRest() {
        return resting && displayedYaw == 0.0 && displayedPitch == 0.0;
    }

    /** Puts the spring back at rest. */
    public void reset() {
        yaw = 0.0;
        pitch = 0.0;
        yawVelocity = 0.0;
        pitchVelocity = 0.0;
        yawImpulse = 0.0;
        pitchImpulse = 0.0;
        displayedYaw = 0.0;
        displayedPitch = 0.0;
        resting = true;
    }

    private static double clampAimScale(double aimScale) {
        if (!Double.isFinite(aimScale)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(MAX_AIM_SCALE, aimScale));
    }

    private static double clamp(double degrees, double ceiling) {
        return Math.max(-ceiling, Math.min(ceiling, degrees));
    }
}
