package com.arg_studios.arg_weaponry.client.recoil;

import java.util.Random;

/**
 * The time-shape of one shot's camera-only screen shake, as a signed yaw offset and a signed roll offset.
 *
 * <p>This is the screen-shake half of the pair the recoil package is built from: {@link ViewRecoilResponse} describes the
 * player's own view impulse, this record describes the transient camera flutter that never moves the player's aim. Both are
 * pure functions of elapsed time, and both are owned by a controller that only advances a clock
 * ({@link PlayerRecoilController}, {@link ViewShakeController}).</p>
 *
 * <h2>The reference behaviour, as verified from the Point Blank JAR</h2>
 * <p>{@code com.vicmatskiv.pointblank.client.controller.ViewShakeAnimationController} (Point Blank 2.2.0,
 * {@code pointblank-neoforge-26.1.2-2.2.0.jar}) samples</p>
 * <pre>
 *   progress = clamp((System.nanoTime() - startTime) / currentNanoDuration, 0, 1)   // getProgress
 *   yaw      = -yawAmplitude  * Math.sin(progress * currentSpeed * Math.PI)         // updateYaw
 *   roll     = -rollAmplitude * Math.sin(progress * currentSpeed * Math.PI)         // updateRoll
 * </pre>
 * <p>with both amplitudes drawn afresh for every shot in {@code reset}: {@code rollAmplitude = ±A·(1 + random())},
 * {@code yawAmplitude = ±A·(1 + random())}, each sign chosen by {@code random() > 0.5}. A shot's magnitude therefore lies
 * in {@code [A, 2A)} on each channel, each channel picks its own sign, and both channels share one sine phase. Pitch is
 * never touched: {@code updatePitch} is inherited unchanged from {@code PryAnimationController}. While the shake runs,
 * {@code onUpdateState} multiplies both amplitudes by {@code (1 - progress)^0.1} once per state update, and
 * {@code GunItem$11#onStartFiring} re-arms the controller - through {@code FireModeFeature#getViewShakeDescriptor} and
 * {@code FireModeInstance$ViewShakeDescriptor} - for every shot, so a new shot <em>replaces</em> the running shake instead
 * of accumulating on it. {@code ClientEventHandler} finally adds the sampled yaw and roll to the camera's own yaw and roll,
 * scaling yaw by {@code 0.1} and roll by {@code 1.5} while aiming.</p>
 *
 * <h2>What this record adopts, and what it deliberately replaces</h2>
 * <ul>
 *   <li><strong>Adopted:</strong> the oscillation law {@code sin(π · speed · progress)}. {@code speed} is an
 *   <em>oscillation rate</em> - the number of half periods across the whole shake, so {@code 8} is four full swings - and is
 *   the same quantity Point Blank's {@code shakeRecoilSpeed} describes. It is not a decay constant.</li>
 *   <li><strong>Adopted:</strong> the per-shot {@code ±[A, 2A)} magnitude draw, independently signed on each channel, so
 *   successive shots do not look identical while the intensity stays predictable.</li>
 *   <li><strong>Adopted:</strong> yaw and roll only. There is no pitch channel, because ordinary vertical player recoil is a
 *   separate controller and a second pitch impulse here would double it.</li>
 *   <li><strong>Replaced:</strong> the reference's per-update {@code (1 - progress)^0.1} multiplication. That law is
 *   frame-rate dependent - over a 350 ms shake at 20 Hz it is applied seven times, at 40 Hz fourteen times, which collapses
 *   the shake about twice as fast - and it cannot satisfy "the same elapsed time always produces the same offset". The
 *   closed-form envelope {@code 1 - p³} keeps the shape it produces (full strength at the shot, 0.875 at the midpoint,
 *   collapsing at the end) while being a pure function that reaches exactly zero at {@code p = 1}.</li>
 * </ul>
 *
 * @param yawAmplitudeDegrees  the signed peak yaw of this shot, in degrees; positive turns the view right
 * @param rollAmplitudeDegrees the signed peak roll of this shot, in degrees, about the view axis
 * @param speed                the oscillation rate, as half periods across the whole shake - Point Blank's
 *                             {@code shakeRecoilSpeed}
 * @param durationSeconds      how long the shake lasts, in seconds
 */
public record ViewShakeResponse(double yawAmplitudeDegrees, double rollAmplitudeDegrees, double speed,
                                double durationSeconds) {

    /**
     * The default shake duration, in seconds.
     *
     * <p>Point Blank's per-fire-mode AN-94 configuration authors {@code shakeRecoilDuration: 350}, and that mod reads the
     * value in milliseconds. ARG Weaponry has no per-weapon duration property and this task must not add one, so this
     * constant is the internal duration of every shot's shake.</p>
     */
    public static final double DEFAULT_DURATION_SECONDS = 0.35;

    /** Shortest shake accepted, in seconds; anything shorter is indistinguishable from "disabled". */
    public static final double MIN_DURATION_SECONDS = 0.001;

    /** Longest shake accepted, in seconds, so a bad value cannot wobble forever. */
    public static final double MAX_DURATION_SECONDS = 5.0;

    /**
     * Largest signed amplitude either channel may carry, in degrees.
     *
     * <p>The per-shot draw reaches twice the configured amplitude and {@link RecoilProfile#MAX_SHAKE_DEGREES} caps that
     * configured amplitude, so this is the absolute ceiling of the response.</p>
     */
    public static final double MAX_AMPLITUDE_DEGREES = RecoilProfile.MAX_SHAKE_DEGREES * 2.0;

    /** Largest oscillation rate accepted, in half periods per shake; the profile applies the same cap. */
    public static final double MAX_SPEED = RecoilProfile.MAX_SHAKE_SPEED;

    /** A response that does nothing. */
    public static final ViewShakeResponse NONE = new ViewShakeResponse(0.0, 0.0, 0.0, DEFAULT_DURATION_SECONDS);

    public ViewShakeResponse {
        yawAmplitudeDegrees = clampAmplitude(yawAmplitudeDegrees);
        rollAmplitudeDegrees = clampAmplitude(rollAmplitudeDegrees);
        speed = Double.isFinite(speed) ? Math.max(0.0, Math.min(speed, MAX_SPEED)) : 0.0;
        durationSeconds = Double.isFinite(durationSeconds) && durationSeconds > 0.0
                ? Math.max(MIN_DURATION_SECONDS, Math.min(durationSeconds, MAX_DURATION_SECONDS))
                : DEFAULT_DURATION_SECONDS;
    }

    /**
     * Builds one shot's shake from a weapon's configured amplitude and speed, drawing the per-shot direction and magnitude
     * the way the reference controller does, with the reference's AN-94 duration.
     *
     * @param amplitudeDegrees the configured {@code screen_shake_Recoil_value}; zero or negative disables the shake
     * @param speed            the configured {@code screen_shake_recoil_speed}
     * @param random           the shot's random source, may be {@code null} for "no variation"
     * @return the response, or {@link #NONE} when the configured amplitude does not ask for one
     */
    public static ViewShakeResponse forShot(double amplitudeDegrees, double speed, Random random) {
        return forShot(amplitudeDegrees, speed, DEFAULT_DURATION_SECONDS, random);
    }

    /**
     * Builds one shot's shake with an explicit duration.
     *
     * <p>Roll is drawn before yaw, as in the reference's {@code reset}. Each magnitude is {@code A · (1 + random())}, so it
     * is distributed over {@code [A, 2A)}, and each sign is drawn independently - which is why two shots never look the same
     * while the intensity stays bounded.</p>
     *
     * @param amplitudeDegrees the configured amplitude, in degrees
     * @param speed            the configured oscillation rate
     * @param durationSeconds  how long the shake lasts, in seconds
     * @param random           the shot's random source, may be {@code null} for "no variation"
     * @return the response, or {@link #NONE} when the configured amplitude does not ask for one
     */
    public static ViewShakeResponse forShot(double amplitudeDegrees, double speed, double durationSeconds,
                                            Random random) {
        if (!Double.isFinite(amplitudeDegrees) || amplitudeDegrees <= 0.0) {
            return NONE;
        }
        double roll = signedMagnitude(amplitudeDegrees, random);
        double yaw = signedMagnitude(amplitudeDegrees, random);
        return new ViewShakeResponse(yaw, roll, speed, durationSeconds);
    }

    /** @return whether this response would change the camera at all. */
    public boolean isZero() {
        return yawAmplitudeDegrees == 0.0 && rollAmplitudeDegrees == 0.0;
    }

    /**
     * @param elapsedSeconds seconds since the shot
     * @return how far through the shake that time is, clamped to {@code [0, 1]}; a non-finite time reports {@code 1}, i.e.
     *         "finished", so it can never resurrect the effect
     */
    public double progress(double elapsedSeconds) {
        if (Double.isNaN(elapsedSeconds)) {
            return 1.0;
        }
        if (elapsedSeconds <= 0.0) {
            return 0.0;
        }
        if (elapsedSeconds >= durationSeconds) {
            return 1.0;
        }
        return elapsedSeconds / durationSeconds;
    }

    /**
     * The amplitude envelope of the shake.
     *
     * <p>{@code 1 - p³}: exactly {@code 1} at the shot with a flat slope, so the flutter starts at full strength the way the
     * reference's does; {@code 0.875} halfway through; and exactly {@code 0} at {@code p = 1}, which is what makes the effect
     * end cleanly instead of leaving a residual roll or yaw behind.</p>
     *
     * @param progress how far through the shake this sample is, clamped to {@code [0, 1]}
     * @return the share of the shot's amplitude at that progress, in {@code [0, 1]}
     */
    public double envelope(double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        return 1.0 - clamped * clamped * clamped;
    }

    /**
     * @param elapsedSeconds seconds since the shot
     * @return the yaw offset at that time, in degrees; positive turns the view right
     */
    public double yawAt(double elapsedSeconds) {
        return normalizeZero(yawAmplitudeDegrees * sample(elapsedSeconds));
    }

    /**
     * @param elapsedSeconds seconds since the shot
     * @return the roll offset at that time, in degrees, about the view axis
     */
    public double rollAt(double elapsedSeconds) {
        return normalizeZero(rollAmplitudeDegrees * sample(elapsedSeconds));
    }

    /**
     * @param elapsedSeconds seconds since the shot
     * @return the largest offset this response can contribute at that time, in degrees; for diagnostics only
     */
    public double peakMagnitudeAt(double elapsedSeconds) {
        if (isZero()) {
            return 0.0;
        }
        return normalizeZero(Math.max(Math.abs(yawAmplitudeDegrees), Math.abs(rollAmplitudeDegrees))
                * sample(elapsedSeconds));
    }

    /**
     * @param elapsedSeconds seconds since the shot
     * @return whether the response has finished and must contribute exactly nothing
     */
    public boolean isFinished(double elapsedSeconds) {
        return progress(elapsedSeconds) >= 1.0;
    }

    /**
     * The shared shape of both channels: the envelope times the reference's sine of {@code π · speed · progress}.
     *
     * @param elapsedSeconds seconds since the shot
     * @return a number in {@code [-1, 1]}
     */
    private double sample(double elapsedSeconds) {
        double progress = progress(elapsedSeconds);
        if (progress >= 1.0) {
            return 0.0;
        }
        return envelope(progress) * Math.sin(Math.PI * speed * progress);
    }

    private static double signedMagnitude(double amplitudeDegrees, Random random) {
        if (random == null) {
            return amplitudeDegrees;
        }
        double magnitude = amplitudeDegrees * (1.0 + random.nextDouble());
        return random.nextBoolean() ? magnitude : -magnitude;
    }

    private static double clampAmplitude(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(-MAX_AMPLITUDE_DEGREES, Math.min(MAX_AMPLITUDE_DEGREES, value));
    }

    /**
     * Collapses {@code -0.0} onto {@code 0.0}.
     *
     * <p>A negative amplitude multiplied by a zero sine produces {@code -0.0}, which is numerically zero but prints as
     * "-0.0" in the diagnostics and is not {@code equals} to {@code 0.0}. The shake ends at exactly zero, so it must report
     * exactly zero.</p>
     *
     * @param value the sampled angle
     * @return the same angle, with a signed zero normalised
     */
    private static double normalizeZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }
}
