package com.arg_studios.arg_weaponry.client.recoil;

import net.minecraft.util.RandomSource;

/**
 * Controller C: the screen shake - a bounded, camera-only response with no permanent effect.
 *
 * <p>Point Blank keeps a view-shake controller apart from its player-recoil controller, and ARG Weaponry does the same:
 * this layer owns a temporary offset added to the rendered camera only. It never writes the player's pitch or yaw, so
 * it cannot fight the mouse, and it never touches the authored camera-bone animation - the camera hook composes it
 * <em>after</em> that animation, about the camera's own axes.</p>
 *
 * <h2>Time-based, not frame-based</h2>
 * <p>The amplitude decays exponentially with the configured {@code screen_shake_recoil_speed} ({@code e}-fold every
 * {@code 1 / speed} seconds) and the angles are a closed-form function of the interpolated elapsed time and amplitude.
 * The same elapsed time always produces the same offset, so a frame that renders twice cannot make the shake wobble
 * faster. A zero {@code screen_shake_Recoil_value} produces no shake at all.</p>
 */
public final class ViewShakeController {

    /** Angular frequency of the shake, in radians per second. */
    public static final double FREQUENCY_RADIANS_PER_SECOND = 26.0;

    /** How much of the pitch amplitude the yaw offset uses. */
    public static final double YAW_FRACTION = 0.6;

    /** How much of the pitch amplitude the roll offset uses. */
    public static final double ROLL_FRACTION = 0.4;

    /** Hard upper bound of a shake's lifetime, in seconds, so nothing can wobble forever. */
    public static final double MAX_DURATION_SECONDS = 1.5;

    private double amplitude;
    private double previousAmplitude;
    private double elapsed;
    private double previousElapsed;
    private double speed = RecoilProfile.DEFAULT_SHAKE_SPEED;
    private double phasePitch;
    private double phaseYaw;
    private double phaseRoll;

    /**
     * Applies one accepted shot.
     *
     * @param profile the weapon's profile, may be {@code null}
     * @param random  the gameplay random source; advanced once per shot for the phases, never per frame
     */
    public void shot(RecoilProfile profile, RandomSource random) {
        if (profile == null || !profile.hasShake()) {
            return;
        }
        speed = profile.shakeSpeed();
        amplitude = Math.min(RecoilProfile.MAX_SHAKE_DEGREES, amplitude + profile.shakeDegrees());
        // A shot is an instantaneous impulse, so both interpolation samples jump together instead of ramping in.
        previousAmplitude = amplitude;
        elapsed = 0.0;
        previousElapsed = 0.0;
        phasePitch = random == null ? 0.0 : random.nextDouble() * Math.PI * 2.0;
        phaseYaw = random == null ? 0.0 : random.nextDouble() * Math.PI * 2.0;
        phaseRoll = random == null ? 0.0 : random.nextDouble() * Math.PI * 2.0;
    }

    /**
     * Advances the layer by one client tick.
     *
     * @param deltaSeconds the elapsed time; non-finite or negative values advance nothing
     */
    public void tick(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            return;
        }
        double delta = Math.min(deltaSeconds, 0.25);
        previousAmplitude = amplitude;
        previousElapsed = elapsed;
        if (amplitude <= 0.0) {
            return;
        }
        elapsed += delta;
        amplitude *= Math.exp(-delta * speed);
        if (amplitude < 1.0E-4 || elapsed >= MAX_DURATION_SECONDS) {
            amplitude = 0.0;
            previousAmplitude = 0.0;
        }
    }

    /**
     * @param partialTick the frame's partial tick, clamped to {@code [0, 1]}
     * @return the camera-only shake of this frame; {@link ShakeAngles#NONE} when no shake is active
     */
    public ShakeAngles sample(float partialTick) {
        if (amplitude <= 0.0 && previousAmplitude <= 0.0) {
            return ShakeAngles.NONE;
        }
        float t = Float.isFinite(partialTick) ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
        double currentAmplitude = previousAmplitude + (amplitude - previousAmplitude) * t;
        double currentElapsed = previousElapsed + (elapsed - previousElapsed) * t;
        if (currentAmplitude <= 0.0) {
            return ShakeAngles.NONE;
        }
        double phase = currentElapsed * FREQUENCY_RADIANS_PER_SECOND;
        return new ShakeAngles(
                currentAmplitude * Math.sin(phase + phasePitch),
                currentAmplitude * YAW_FRACTION * Math.sin(phase * 0.83 + phaseYaw),
                currentAmplitude * ROLL_FRACTION * Math.sin(phase * 1.21 + phaseRoll));
    }

    /** @return the shake amplitude of the last completed tick, in degrees. */
    public double amplitude() {
        return amplitude;
    }

    /** @return whether the layer still has anything to contribute. */
    public boolean isActive() {
        return amplitude > 0.0;
    }

    /** Drops the shake. */
    public void reset() {
        amplitude = 0.0;
        previousAmplitude = 0.0;
        elapsed = 0.0;
        previousElapsed = 0.0;
        phasePitch = 0.0;
        phaseYaw = 0.0;
        phaseRoll = 0.0;
    }

    /**
     * The camera-only shake of one frame, in degrees, about the camera's own axes.
     *
     * @param pitchDegrees pitch offset, positive means the view drops
     * @param yawDegrees   yaw offset, positive means the view turns right
     * @param rollDegrees  roll offset about the view axis
     */
    public record ShakeAngles(double pitchDegrees, double yawDegrees, double rollDegrees) {

        /** No shake. */
        public static final ShakeAngles NONE = new ShakeAngles(0.0, 0.0, 0.0);

        /** @return whether this offset would change the camera at all. */
        public boolean isZero() {
            return pitchDegrees == 0.0 && yawDegrees == 0.0 && rollDegrees == 0.0;
        }
    }
}

