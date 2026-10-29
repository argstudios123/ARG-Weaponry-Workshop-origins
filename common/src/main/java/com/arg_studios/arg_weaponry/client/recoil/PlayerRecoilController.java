package com.arg_studios.arg_weaponry.client.recoil;

/**
 * Controller B: the player/view-angle recoil - one accumulated offset that each accepted shot raises and that returns to
 * neutral over a well-defined interval once firing stops, applied to the player's live view angles as an
 * <em>incremental delta</em>.
 *
 * <p>Pure state, no Minecraft types, so the whole behaviour - one impulse per shot, accumulation, saturation, recovery
 * to zero, mouse preservation, frame-rate independence - can be verified headlessly.</p>
 *
 * <h2>Model: one accumulating offset, not one bump per shot</h2>
 * <p>The controller keeps a single recoil offset per axis, in degrees, expressed <em>relative to whatever the player is
 * aiming at</em>. An accepted shot adds its impulse ({@link ViewRecoilResponse}) to that offset, and the offset is
 * normalised back to exactly zero over {@link #RECOVERY_SECONDS} counted from the most recent shot. That gives the
 * behaviours an FPS needs, with no decorative oscillator anywhere:</p>
 * <ul>
 *   <li><strong>a shot moves the aim</strong> - the offset changes, and {@link #tick(double)} hands the change to the
 *   caller, which adds it to the player's own angles. Nothing is drawn that the player is not actually looking at;</li>
 *   <li><strong>a burst climbs</strong> - a second shot arriving before the first has recovered adds to what is still
 *   there instead of starting an independent bump, so the aim keeps rising while the trigger is held;</li>
 *   <li><strong>release recovers</strong> - the remaining offset glides back to zero over one interval, because the
 *   normalisation clock is anchored at the last shot;</li>
 *   <li><strong>the offset is bounded</strong> - the addition is saturated against {@link #MAX_TOTAL_DEGREES}, so a
 *   pathological fire rate approaches a ceiling instead of summing angles forever.</li>
 * </ul>
 *
 * <h2>Recovery: {@link #RECOVERY_SECONDS}</h2>
 * <p>S.T.A.L.K.E.R. 2 is the behavioural reference for this milestone. Public modding references describe a
 * {@code RadiusNormalizationInterval} in {@code WeaponGeneralSetupPrototypes.cfg} that controls recoil recovery, with a
 * reported default of about 0.8 seconds and an Aim Recovery upgrade that modifies it. That is a reference point for the
 * <em>time scale</em>, not a specification of the original algorithm, so this controller does not claim to reproduce it:
 * it uses {@code 0.8 s} as the internal recovery interval and documents the shape it puts on that interval.</p>
 * <p>The outstanding share follows {@code 1 - smoothstep(t / T)}: it is {@code 1} at the shot, still near {@code 1} a
 * fifth of the way in, passes through {@code 0.5} at the midpoint and reaches exactly {@code 0} at {@code T} with zero
 * slope at both ends. That is what makes the return read as the weapon settling instead of the view snapping back: no
 * kink when the recovery starts, no abrupt stop when it finishes, no overshoot and no oscillation.</p>
 * <p>The interval is {@link #RECOVERY_SECONDS} divided by the weapon's {@code recovery_speed}: {@code 1.0} keeps the
 * baseline {@code 0.8 s}, smaller values stretch it and larger values shorten it. A weapon that does not declare the
 * property therefore keeps exactly the duration its shots had before the property existed, and content packs keep
 * configuring {@code vertical_recoil} and the two horizontal directions as before.</p>
 *
 * <h2>Smoothing: {@link #SMOOTHING_REFERENCE_SECONDS}</h2>
 * <p>{@code smoothing} decides how much of the difference between the accumulated offset and what has already been handed
 * to the player is applied on a tick: {@code 0.5} applies 50 % of the remaining difference per 50 ms reference interval,
 * {@code 1.0} applies all of it at once. The share is scaled to the step that is actually supplied with
 * {@code 1 - (1 - smoothing)^(step / 0.05)} ({@link #smoothingShare(double, double)}), so the same elapsed time always
 * produces the same result whatever the tick or frame rate is. Because the smoothed value only ever moves towards the
 * target it cannot overshoot, jitter or oscillate, and once the difference is at or below
 * {@link #SMOOTHING_SETTLE_DEGREES} it snaps onto the target - so the response ends exactly on the accumulated offset, and
 * therefore exactly on zero when the recovery has finished, with no residual rotation left behind. The default
 * {@code 1.0} reproduces the pre-tuning behaviour exactly.</p>
 *
 * <h2>Delta, not absolute</h2>
 * <p>{@link #tick(double)} returns the difference between the offset the shots currently describe and what has already
 * been handed to the caller. The caller adds that difference to the player's <em>live</em> angles
 * ({@link ViewAngles}), which has three consequences:</p>
 * <ul>
 *   <li>mouse movement between two ticks is preserved, because it is part of the value the delta is added to - the
 *   recoil never restores a captured absolute angle;</li>
 *   <li>the whole accumulated offset is never re-applied: only the difference since the previous tick is, so sampling
 *   more often cannot make the recoil stronger;</li>
 *   <li>calling {@code tick} with no elapsed time returns a zero delta and writes nothing, so a frame, a reload or a
 *   repeated sample cannot advance or duplicate the response.</li>
 * </ul>
 *
 * <h2>Bounds</h2>
 * <p>The accumulated offset per axis is capped at {@link #MAX_TOTAL_DEGREES} and one tick's delta at
 * {@link #MAX_DELTA_PER_TICK_DEGREES}, so neither a pathological configuration nor a long stall (which advances the
 * recovery by a lot in one step) can fling the view. The bookkeeping records what was <em>actually</em> handed to the
 * caller, so a clamped step is made up over the following ticks instead of being silently forgotten.</p>
 */
public final class PlayerRecoilController {

    /**
     * Largest recoil offset one axis may accumulate, in degrees.
     *
     * <p>Reached only asymptotically, because each impulse is added at the share of its size that is still free (see
     * {@link ViewRecoilResponse#scaled(double)}), so a burst saturates instead of running away.</p>
     */
    public static final double MAX_TOTAL_DEGREES = 25.0;

    /** Largest offset a single tick may add, in degrees. */
    public static final double MAX_DELTA_PER_TICK_DEGREES = 8.0;

    /**
     * Baseline seconds over which the accumulated offset returns to neutral after the last shot.
     *
     * <p>{@code 0.8} is the reported default of S.T.A.L.K.E.R. 2's {@code RadiusNormalizationInterval}, used here as a
     * documented starting point for the recovery time scale - see the class documentation for what that does and does
     * not claim. It is the duration at {@code recovery_speed = 1.0}; a weapon's actual duration is
     * {@code RECOVERY_SECONDS / recovery_speed} (see {@link ViewRecoilResponse#recoverySeconds()}).</p>
     */
    public static final double RECOVERY_SECONDS = 0.8;

    /** Shortest recovery interval {@code recovery_speed} may produce, in seconds (i.e. {@code recovery_speed = 10}). */
    public static final double MIN_RECOVERY_SECONDS = 0.08;

    /** Longest recovery interval {@code recovery_speed} may produce, in seconds (i.e. {@code recovery_speed = 0.1}). */
    public static final double MAX_RECOVERY_SECONDS = 8.0;

    /**
     * Default {@code smoothing}: the whole remaining difference is applied on the next tick.
     *
     * <p>{@code 1.0} is the value that reproduces the pre-tuning behaviour exactly, which is why it is the default for a
     * weapon that does not declare the property.</p>
     */
    public static final double DEFAULT_SMOOTHING = 1.0;

    /** Smallest accepted {@code smoothing}: 1 % of the remaining difference per reference interval. */
    public static final double MIN_SMOOTHING = 0.01;

    /** Largest accepted {@code smoothing}: the difference is applied at once. */
    public static final double MAX_SMOOTHING = 1.0;

    /**
     * The interval {@code smoothing} is defined against, in seconds.
     *
     * <p>{@code smoothing = 0.5} means "apply 50 % of the remaining difference in 50 ms". The controller scales that to
     * whatever step it is given (see {@link #smoothingShare(double, double)}), so the result does not change with the
     * frame or tick rate.</p>
     */
    public static final double SMOOTHING_REFERENCE_SECONDS = 0.05;

    /**
     * Difference at or below which the smoothed state is snapped onto its target, in degrees.
     *
     * <p>This is what makes the smoothed response end exactly on the target - and therefore exactly on zero - instead of
     * decaying towards it forever. {@code 0.001} degrees is about one sixtieth of a pixel at a 70 degree field of view, so
     * the snap cannot be seen, but it removes the last residual rotation.</p>
     */
    public static final double SMOOTHING_SETTLE_DEGREES = 1.0E-3;

    /** Largest time step one tick may advance, in seconds, so a long stall cannot skip the whole recovery. */
    public static final double MAX_STEP_SECONDS = 0.25;

    /** Offsets below this magnitude are reported as exactly zero, so the neutral state is exact. */
    private static final double EPSILON_DEGREES = 1.0E-9;

    /** The recoil offset currently owed to the player, in degrees; positive pitch raises the aim. */
    private double pitchOffset;

    /** The recoil offset currently owed to the player, in degrees; positive yaw turns the aim right. */
    private double yawOffset;

    /** What has already been handed to the caller, in degrees, so only the difference is applied again. */
    private double appliedPitch;

    /** What has already been handed to the caller, in degrees. */
    private double appliedYaw;

    /** Seconds since the last shot, i.e. the position on the recovery curve, clamped at {@link #recoverySeconds}. */
    private double recoveryAge;

    /** The pitch offset at the instant the recovery clock was last anchored, in degrees. */
    private double recoveryStartPitch;

    /** The yaw offset at the instant the recovery clock was last anchored, in degrees. */
    private double recoveryStartYaw;

    /** {@code smoothing} of the most recent shot; see {@link #smoothingShare(double, double)}. */
    private double smoothing = DEFAULT_SMOOTHING;

    /** Recovery interval of the most recent shot, in seconds; {@link #RECOVERY_SECONDS} at {@code recovery_speed = 1}. */
    private double recoverySeconds = RECOVERY_SECONDS;

    /**
     * Applies one accepted shot.
     *
     * <p>The impulse is added to whatever offset is still outstanding and the recovery clock is re-anchored at the new
     * total - the offset that had already recovered stays recovered, and the offset that had not stays in place, so a
     * shot arriving mid-recovery never rewinds or discards accumulated recoil.</p>
     *
     * <p>The shot also decides the two timing settings used until the next shot: its {@code smoothing} and its
     * {@code recovery_speed}-derived {@link ViewRecoilResponse#recoverySeconds() recovery interval}. Taking them from the
     * response keeps this controller free of any knowledge of the weapon profile, exactly as it was before the tuning
     * properties existed; a weapon that does not declare them sends the documented defaults, so its behaviour is
     * unchanged.</p>
     *
     * @param response the shot's impulse, may be {@code null} or zero
     */
    public void onShot(ViewRecoilResponse response) {
        if (response == null || response.isZero()) {
            return;
        }
        smoothing = response.smoothing();
        recoverySeconds = response.recoverySeconds();
        pitchOffset = accumulate(pitchOffset, response.pitchDegrees());
        yawOffset = accumulate(yawOffset, response.yawDegrees());
        recoveryAge = 0.0;
        recoveryStartPitch = pitchOffset;
        recoveryStartYaw = yawOffset;
    }

    /**
     * Advances the recovery by one client tick and returns the change the caller must add to the player's angles.
     *
     * @param deltaSeconds the time since the previous call; non-finite or non-positive values advance nothing and
     *                     return {@link Delta#NONE}
     * @return the delta to apply, never {@code null}
     */
    public Delta tick(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            // A repeated sample of the same instant must not advance, accumulate or rotate anything.
            return Delta.NONE;
        }
        double step = Math.min(deltaSeconds, MAX_STEP_SECONDS);
        recover(step);

        // `smoothing` decides how much of the remaining difference is applied this tick. At the default 1.0 that is the
        // whole difference - exactly the pre-tuning behaviour - and below it the applied offset simply lags the target,
        // so it can never overshoot it, oscillate around it or need state of its own beyond `applied`.
        double share = smoothingShare(smoothing, step);
        double nextPitch = approach(appliedPitch, pitchOffset, share);
        double nextYaw = approach(appliedYaw, yawOffset, share);

        // The delta is clamped for safety, and the bookkeeping records what was *actually* handed to the caller, so a
        // clamped step is made up over the following ticks instead of being silently forgotten.
        double deltaPitch = clamp(nextPitch - appliedPitch, MAX_DELTA_PER_TICK_DEGREES);
        double deltaYaw = clamp(nextYaw - appliedYaw, MAX_DELTA_PER_TICK_DEGREES);
        appliedPitch += deltaPitch;
        appliedYaw += deltaYaw;
        settle();
        return new Delta(deltaPitch, deltaYaw);
    }

    /**
     * Moves the accumulated offset along its recovery curve.
     *
     * <p>The curve is evaluated from {@link #recoveryAge}, never integrated per tick, so the same elapsed time gives the
     * same offset whatever the tick rate was. Its duration is the shot's
     * {@link ViewRecoilResponse#recoverySeconds()}, i.e. {@link #RECOVERY_SECONDS} divided by {@code recovery_speed}, so
     * the shape of the recovery is the existing one and only its time scale changes. Once the age reaches that duration
     * the offset is exactly zero and the anchor is dropped: nothing keeps recovering after the offset has reached
     * neutral.</p>
     *
     * @param step the tick length, already clamped to {@link #MAX_STEP_SECONDS}
     */
    private void recover(double step) {
        if (recoveryStartPitch == 0.0 && recoveryStartYaw == 0.0) {
            return;
        }
        recoveryAge = Math.min(recoverySeconds, recoveryAge + step);
        if (recoveryAge >= recoverySeconds) {
            pitchOffset = 0.0;
            yawOffset = 0.0;
            recoveryStartPitch = 0.0;
            recoveryStartYaw = 0.0;
            return;
        }
        double remaining = remainingShare(recoveryAge / recoverySeconds);
        pitchOffset = normalizeZero(recoveryStartPitch * remaining);
        yawOffset = normalizeZero(recoveryStartYaw * remaining);
    }

    /**
     * Adds one impulse to one axis, saturating as the offset approaches {@link #MAX_TOTAL_DEGREES}.
     *
     * <p>The share of the impulse that is applied shrinks linearly with how much of the cap is already used, so the
     * accumulated offset approaches the ceiling smoothly instead of being cut off by a hard clamp: a burst that reaches
     * the cap stops climbing gradually, and its shots still feel like shots.</p>
     *
     * @param current the offset before the shot, in degrees
     * @param impulse the shot's impulse on this axis, in degrees
     * @return the new offset, in degrees, never beyond the cap
     */
    private static double accumulate(double current, double impulse) {
        if (!Double.isFinite(impulse) || impulse == 0.0) {
            return current;
        }
        double used = Math.min(1.0, Math.abs(current) / MAX_TOTAL_DEGREES);
        return clamp(current + impulse * (1.0 - used), MAX_TOTAL_DEGREES);
    }

    /**
     * @param progress how far through the recovery interval this sample is, in {@code [0, 1]}
     * @return the share of the anchored offset that is still outstanding: {@code 1} at the anchor, {@code 0} at the end
     */
    public static double remainingShare(double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        return 1.0 - clamped * clamped * (3.0 - 2.0 * clamped);
    }

    /**
     * The share of the remaining difference {@code smoothing} asks for in one step of {@code stepSeconds}.
     *
     * <p>{@code smoothing} is defined against {@link #SMOOTHING_REFERENCE_SECONDS} (50 ms) and scaled to the step that is
     * actually supplied with {@code 1 - (1 - smoothing)^(step / reference)}, so two half steps apply exactly the share of
     * one full step. That composition is what makes the smoothing independent of the tick or frame rate the controller
     * happens to be driven at.</p>
     *
     * @param smoothing   the configured share per reference interval
     * @param stepSeconds the tick length, in seconds
     * @return the share of the remaining difference to apply this step, {@code 0} for a non-positive step
     */
    public static double smoothingShare(double smoothing, double stepSeconds) {
        if (!Double.isFinite(stepSeconds) || stepSeconds <= 0.0) {
            return 0.0;
        }
        double configured = Double.isFinite(smoothing)
                ? Math.max(MIN_SMOOTHING, Math.min(MAX_SMOOTHING, smoothing))
                : DEFAULT_SMOOTHING;
        if (configured >= MAX_SMOOTHING) {
            return 1.0;
        }
        return 1.0 - Math.pow(1.0 - configured, stepSeconds / SMOOTHING_REFERENCE_SECONDS);
    }

    /**
     * Moves one applied channel towards its target.
     *
     * <p>With {@code share = 1} the target is reached exactly, which is the pre-tuning behaviour. With a smaller share the
     * value moves part of the way, and once the difference is at or below {@link #SMOOTHING_SETTLE_DEGREES} it snaps onto
     * the target: the response therefore ends on the target exactly - and on zero once the offset has recovered - instead
     * of approaching it asymptotically forever. The snap can never move the aim by more than that threshold.</p>
     *
     * @param applied the value handed to the player so far, in degrees
     * @param target  the value the accumulated offsets currently describe, in degrees
     * @param share   the share of the remaining difference to apply
     * @return the value to hand over this step, in degrees
     */
    private static double approach(double applied, double target, double share) {
        if (!Double.isFinite(share) || share <= 0.0) {
            return applied;
        }
        if (share >= 1.0) {
            return target;
        }
        double next = applied + (target - applied) * share;
        return Math.abs(target - next) <= SMOOTHING_SETTLE_DEGREES ? target : next;
    }

    /** Pins the bookkeeping at exact zero once nothing is owed and nothing is outstanding. */
    private void settle() {
        if (Math.abs(pitchOffset) < EPSILON_DEGREES) {
            pitchOffset = 0.0;
        }
        if (Math.abs(yawOffset) < EPSILON_DEGREES) {
            yawOffset = 0.0;
        }
        if (pitchOffset == 0.0 && Math.abs(appliedPitch) < EPSILON_DEGREES) {
            appliedPitch = 0.0;
        }
        if (yawOffset == 0.0 && Math.abs(appliedYaw) < EPSILON_DEGREES) {
            appliedYaw = 0.0;
        }
    }

    /** @return the recoil offset currently owed to the player, in degrees of pitch; positive raises the aim. */
    public double pitchOffset() {
        return pitchOffset;
    }

    /** @return the recoil offset currently owed to the player, in degrees of yaw; positive turns the aim right. */
    public double yawOffset() {
        return yawOffset;
    }

    /** @return the total pitch offset currently applied to the player's aim, in degrees. */
    public double appliedPitch() {
        return appliedPitch;
    }

    /** @return the total yaw offset currently applied to the player's aim, in degrees. */
    public double appliedYaw() {
        return appliedYaw;
    }

    /** @return {@code smoothing} of the most recent shot, {@link #DEFAULT_SMOOTHING} before the first one. */
    public double smoothing() {
        return smoothing;
    }

    /** @return the recovery interval of the most recent shot, in seconds; {@link #RECOVERY_SECONDS} by default. */
    public double recoverySeconds() {
        return recoverySeconds;
    }

    /** @return whether anything is still owed to the player or still outstanding on their aim. */
    public boolean isActive() {
        return pitchOffset != 0.0 || yawOffset != 0.0 || appliedPitch != 0.0 || appliedYaw != 0.0;
    }

    /** Drops the offset, the recovery clock, the applied bookkeeping and the tuning of the last shot. */
    public void reset() {
        pitchOffset = 0.0;
        yawOffset = 0.0;
        appliedPitch = 0.0;
        appliedYaw = 0.0;
        recoveryAge = 0.0;
        recoveryStartPitch = 0.0;
        recoveryStartYaw = 0.0;
        smoothing = DEFAULT_SMOOTHING;
        recoverySeconds = RECOVERY_SECONDS;
    }

    private static double clamp(double value, double limit) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(-limit, Math.min(limit, value));
    }

    /**
     * Collapses {@code -0.0} onto {@code 0.0}, so "recovered" is exactly zero and not a signed zero.
     *
     * @param value the sampled offset
     * @return the same value, with a signed zero normalised
     */
    private static double normalizeZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }

    /**
     * The change one tick asks the caller to add to the player's angles.
     *
     * @param pitchDegrees the pitch change; positive means the aim rises (Minecraft's pitch decreases)
     * @param yawDegrees   the yaw change; positive means the aim turns right
     */
    public record Delta(double pitchDegrees, double yawDegrees) {

        /** No change. */
        public static final Delta NONE = new Delta(0.0, 0.0);

        /** @return whether this delta would change the aim at all. */
        public boolean isZero() {
            return pitchDegrees == 0.0 && yawDegrees == 0.0;
        }
    }
}
