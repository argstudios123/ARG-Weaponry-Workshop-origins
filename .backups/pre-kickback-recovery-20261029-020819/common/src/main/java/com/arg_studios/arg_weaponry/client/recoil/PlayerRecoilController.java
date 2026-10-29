package com.arg_studios.arg_weaponry.client.recoil;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller B: the player/view-angle recoil - one time-shaped response per accepted shot, applied as an
 * <em>incremental delta</em> to the player's live view angles.
 *
 * <p>Pure state, no Minecraft types, so the whole behaviour - one impulse per shot, idempotence, recovery to zero,
 * mouse preservation, frame-rate independence - can be verified headlessly.</p>
 *
 * <h2>Delta, not absolute</h2>
 * <p>{@link #tick(double)} returns the difference between the response the shots currently describe and what has
 * already been handed to the caller. The caller adds that difference to the player's <em>live</em> angles, which is the
 * same incremental scheme TaCZ's {@code CameraSetupEvent} uses (sample the curve, apply the difference between the
 * current and the previous sample) and the same {@code accumulatedPitchOffset} bookkeeping Point Blank's
 * {@code PlayerRecoilController} performs:</p>
 * <ul>
 *   <li>mouse movement between two ticks is preserved, because it is part of the value the delta is added to - the
 *   recoil never restores a captured absolute angle;</li>
 *   <li>the whole accumulated curve is never re-applied: only the difference since the previous sample is, so sampling
 *   more often cannot make the recoil stronger;</li>
 *   <li>calling {@code tick} again with no elapsed time returns a zero delta, so a frame that runs twice cannot apply
 *   the response twice;</li>
 *   <li>the total offset handed to the player is always the response of the shots in flight, so when they have all
 *   decayed the aim is exactly where the player put it - the return removes precisely what the recoil added, and
 *   nothing else.</li>
 * </ul>
 *
 * <h2>Bounds</h2>
 * <p>The summed response is clamped to {@link #MAX_TOTAL_DEGREES} and one tick's delta to
 * {@link #MAX_DELTA_PER_TICK_DEGREES}, so neither a pathological configuration nor a long stall (which advances the
 * response by a lot in one step) can fling the view. At most {@link #MAX_EPISODES} shots contribute at once; beyond
 * that the oldest is dropped, which keeps the cost and the response bounded during sustained fire.</p>
 */
public final class PlayerRecoilController {

    /** Largest total view offset the layer will apply, in degrees. */
    public static final double MAX_TOTAL_DEGREES = 25.0;

    /** Largest offset a single tick may add, in degrees. */
    public static final double MAX_DELTA_PER_TICK_DEGREES = 8.0;

    /** Largest number of shots whose responses may contribute at the same time. */
    public static final int MAX_EPISODES = 16;

    private final List<Episode> episodes = new ArrayList<>();
    private double appliedPitch;
    private double appliedYaw;

    /**
     * Adds one shot's response.
     *
     * @param response the response, may be {@code null} or zero
     */
    public void onShot(ViewRecoilResponse response) {
        if (response == null || response.isZero()) {
            return;
        }
        episodes.add(new Episode(response));
        while (episodes.size() > MAX_EPISODES) {
            episodes.remove(0);
        }
    }

    /**
     * Advances every response and returns the change the caller must add to the player's angles.
     *
     * @param deltaSeconds the time since the previous call; non-finite or zero values advance nothing
     * @return the delta to apply, never {@code null}
     */
    public Delta tick(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            // No time, no change: a repeated sample of the same instant is a no-op.
            return Delta.NONE;
        }
        double step = Math.min(deltaSeconds, 0.25);
        double desiredPitch = 0.0;
        double desiredYaw = 0.0;
        for (int index = episodes.size() - 1; index >= 0; index--) {
            Episode episode = episodes.get(index);
            episode.elapsed += step;
            double fraction = episode.response.fraction(episode.elapsed);
            if (fraction == 0.0) {
                episodes.remove(index);
                continue;
            }
            desiredPitch += episode.response.peakPitchDegrees() * fraction;
            desiredYaw += episode.response.peakYawDegrees() * fraction;
        }
        desiredPitch = clamp(desiredPitch, MAX_TOTAL_DEGREES);
        desiredYaw = clamp(desiredYaw, MAX_TOTAL_DEGREES);

        // The delta is clamped for safety, and the bookkeeping records what was *actually* handed to the caller, so a
        // clamped step is made up over the following ticks instead of being silently forgotten.
        double deltaPitch = clamp(desiredPitch - appliedPitch, MAX_DELTA_PER_TICK_DEGREES);
        double deltaYaw = clamp(desiredYaw - appliedYaw, MAX_DELTA_PER_TICK_DEGREES);
        appliedPitch += deltaPitch;
        appliedYaw += deltaYaw;
        if (episodes.isEmpty() && Math.abs(appliedPitch) < 1.0E-9 && Math.abs(appliedYaw) < 1.0E-9) {
            // Nothing is in flight and nothing is owed: pin the bookkeeping so a later shot starts from a clean base.
            appliedPitch = 0.0;
            appliedYaw = 0.0;
        }
        return new Delta(deltaPitch, deltaYaw);
    }

    /** @return the total pitch offset currently applied to the player's aim, in degrees. */
    public double appliedPitch() {
        return appliedPitch;
    }

    /** @return the total yaw offset currently applied to the player's aim, in degrees. */
    public double appliedYaw() {
        return appliedYaw;
    }

    /** @return whether any response is still in flight. */
    public boolean isActive() {
        return !episodes.isEmpty();
    }

    /** @return how many responses are in flight, for tests and diagnostics. */
    public int activeCount() {
        return episodes.size();
    }

    /** Drops every response and the applied bookkeeping. */
    public void reset() {
        episodes.clear();
        appliedPitch = 0.0;
        appliedYaw = 0.0;
    }

    private static double clamp(double value, double limit) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(-limit, Math.min(limit, value));
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

    /** One shot's response and how long it has been running. */
    private static final class Episode {

        private final ViewRecoilResponse response;
        private double elapsed;

        private Episode(ViewRecoilResponse response) {
            this.response = response;
        }
    }
}
