package com.arg_studios.arg_weaponry.client.recoil;

/**
 * Controller C: the screen shake - a bounded, camera-only response with no permanent effect.
 *
 * <p>Point Blank keeps a view-shake controller apart from its player-recoil controller, and ARG Weaponry does the same: this
 * layer owns a temporary offset added to the rendered camera only. It never writes the player's pitch or yaw, so it cannot
 * fight the mouse, and it never touches the authored camera-bone animation - the camera hook composes it <em>after</em> that
 * animation, about the camera's own axes.</p>
 *
 * <h2>What changed in this controller, and why</h2>
 * <p>The previous version drove the shake from an exponentially decaying mutable amplitude whose decay constant was the
 * configured {@code screen_shake_recoil_speed} ({@code e}-fold every {@code 1 / speed} seconds) and oscillated at a fixed
 * 26 rad/s, with pitch as the main channel. That is not what the reference does: Point Blank's
 * {@code ViewShakeAnimationController} oscillates at {@code sin(π · speed · progress)} - the configured speed <em>is</em> the
 * oscillation rate - fades over a fixed duration, and never contributes pitch. Because the configured speed was read as a
 * decay constant, raising it made the shake vanish sooner and lower made it linger, which is why no value of amplitude and
 * speed could reproduce the reference's brief flutter.</p>
 *
 * <p>This controller now owns nothing but a clock and one {@link ViewShakeResponse} per shot:</p>
 * <ul>
 *   <li>a shot <em>replaces</em> the running shake, exactly as {@code GunItem$11#onStartFiring} re-arms the reference's
 *   controller, so sustained fire cannot accumulate displacement;</li>
 *   <li>{@link #tick(double)} only advances elapsed time, and clamps at the response's duration;</li>
 *   <li>{@link #sample(float)} is a pure function of (response, interpolated elapsed time): the same sample request always
 *   returns the same angles, and rendering a frame twice cannot advance or amplify anything;</li>
 *   <li>the angles come from {@link ViewShakeResponse}, which is closed-form, so the effect reaches exactly zero at the end
 *   of the duration and leaves no residual yaw or roll behind.</li>
 * </ul>
 *
 * <h2>No pitch channel</h2>
 * <p>{@link ShakeAngles} still carries a pitch component, because it is the shared value type of the camera-composition
 * helper, but this controller always reports it as zero. A vertical impulse belongs to {@link PlayerRecoilController}, the
 * player's own view recoil, and duplicating it here would double the kick.</p>
 */
public final class ViewShakeController {

    /** Largest time step one tick may advance the shake, in seconds, so a long stall cannot skip the whole effect. */
    public static final double MAX_STEP_SECONDS = 0.25;

    private ViewShakeResponse response = ViewShakeResponse.NONE;
    private double elapsed;
    private double previousElapsed;

    /**
     * Applies one accepted shot.
     *
     * <p>The response replaces whatever shake was running, as the reference's {@code onStartFiring} does for every shot, so
     * automatic fire produces one brief flutter per round instead of a growing wobble. A shot that asks for no shake is
     * ignored rather than cancelling an active one, so a weapon without {@code screen_shake_Recoil_value} simply never
     * produces screen shake.</p>
     *
     * @param shot the shot's shake, may be {@code null} or {@link ViewShakeResponse#NONE}
     */
    public void onShot(ViewShakeResponse shot) {
        if (shot == null || shot.isZero()) {
            return;
        }
        response = shot;
        elapsed = 0.0;
        previousElapsed = 0.0;
    }

    /**
     * Advances the layer by one client tick.
     *
     * <p>Only the clock moves. Elapsed time is clamped to the response's duration, so the shake can never outlive it, and the
     * finished response is dropped on the following tick - sampling before then still renders the tail that belongs to the
     * interpolation interval the frame is in.</p>
     *
     * @param deltaSeconds the elapsed time; non-finite or non-positive values advance nothing
     */
    public void tick(double deltaSeconds) {
        if (response.isZero()) {
            return;
        }
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            return;
        }
        if (elapsed >= response.durationSeconds()) {
            reset();
            return;
        }
        previousElapsed = elapsed;
        elapsed = Math.min(response.durationSeconds(), elapsed + Math.min(deltaSeconds, MAX_STEP_SECONDS));
    }

    /**
     * Samples the shake of one rendered frame.
     *
     * <p>The elapsed time is interpolated with the frame's partial tick, so the flutter is smooth at any frame rate, and the
     * result is a pure function of the stored state: no field is written, so a frame that is rendered twice produces the same
     * angles twice and nothing accumulates.</p>
     *
     * @param partialTick the frame's partial tick, clamped to {@code [0, 1]}
     * @return the camera-only shake of this frame; {@link ShakeAngles#NONE} when no shake is active
     */
    public ShakeAngles sample(float partialTick) {
        if (response.isZero()) {
            return ShakeAngles.NONE;
        }
        float tick = Float.isFinite(partialTick) ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
        double sampleTime = previousElapsed + (elapsed - previousElapsed) * tick;
        if (response.isFinished(sampleTime)) {
            // The shake has run its full duration: report exactly nothing, not a rounded-down remnant.
            return ShakeAngles.NONE;
        }
        double yaw = response.yawAt(sampleTime);
        double roll = response.rollAt(sampleTime);
        if (yaw == 0.0 && roll == 0.0) {
            return ShakeAngles.NONE;
        }
        return new ShakeAngles(0.0, yaw, roll);
    }

    /**
     * @return the amplitude of the shake at the last completed tick, in degrees, for diagnostics only
     */
    public double amplitude() {
        if (response.isZero()) {
            return 0.0;
        }
        return response.peakMagnitudeAt(Math.min(elapsed, response.durationSeconds()));
    }

    /** @return whether the layer still has anything to contribute. */
    public boolean isActive() {
        return !response.isZero() && elapsed < response.durationSeconds();
    }

    /** @return the response currently being sampled, for diagnostics and tests; {@link ViewShakeResponse#NONE} when idle. */
    public ViewShakeResponse response() {
        return response;
    }

    /** Drops the shake. */
    public void reset() {
        response = ViewShakeResponse.NONE;
        elapsed = 0.0;
        previousElapsed = 0.0;
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

