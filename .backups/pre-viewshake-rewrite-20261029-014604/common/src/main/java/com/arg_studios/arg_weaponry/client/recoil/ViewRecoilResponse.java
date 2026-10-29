package com.arg_studios.arg_weaponry.client.recoil;

/**
 * The time-shape of one shot's player/view recoil, as a cumulative offset in degrees.
 *
 * <p>This is the piece both reference implementations share: Point Blank's {@code PlayerRecoilController} keeps a recoil
 * response that progresses over time and applies the <em>difference between successive samples</em> to the player's live
 * angles, and TaCZ's {@code CameraSetupEvent} samples a pitch/yaw curve at the elapsed time and applies the difference
 * between the current and the previous sample. Neither adds a fixed value per frame, and neither re-applies the whole
 * accumulated curve; both let a function of elapsed time drive the view.</p>
 *
 * <h2>Shape</h2>
 * <pre>
 *  fraction
 *   1 |      /\
 *     |     /  \___
 *     |    /       \___
 *   0 +---|-----------|------ elapsed
 *         rise      recovery end
 * </pre>
 * <p>A shot snaps the aim up over {@link #DEFAULT_RISE_SECONDS}, then the response eases back to exactly zero over
 * {@link #DEFAULT_RECOVERY_SECONDS}. {@link #fraction(double)} is a pure function of elapsed time, so the same
 * timestamp always produces the same number and the response can be sampled as often as wanted.</p>
 *
 * <h2>Why the response returns to zero</h2>
 * <p>The controller applies <em>deltas</em> to the live angles, never an absolute orientation, so a response that ends
 * at zero leaves the view exactly where the player's own mouse movements put it. A permanent offset would either fight
 * the mouse or have to be pulled back later, and both are exactly the instability the hotfix removed. The aim is still
 * raised while the response is active, which is what makes the following shots of a burst climb.</p>
 *
 * @param peakPitchDegrees the offset at full response, positive meaning the aim rises
 * @param peakYawDegrees   the sideways offset at full response, positive meaning the aim turns right
 */
public record ViewRecoilResponse(double peakPitchDegrees, double peakYawDegrees) {

    /** Seconds from the shot to the peak of the response. */
    public static final double DEFAULT_RISE_SECONDS = 0.045;

    /** Seconds from the peak back to the neutral offset. */
    public static final double DEFAULT_RECOVERY_SECONDS = 0.30;

    /** A response that does nothing. */
    public static final ViewRecoilResponse NONE = new ViewRecoilResponse(0.0, 0.0);

    public ViewRecoilResponse {
        peakPitchDegrees = finite(peakPitchDegrees);
        peakYawDegrees = finite(peakYawDegrees);
    }

    /** @return whether this response would move the aim at all. */
    public boolean isZero() {
        return peakPitchDegrees == 0.0 && peakYawDegrees == 0.0;
    }

    /**
     * @param elapsedSeconds seconds since the shot
     * @return the share of the peak, {@code 1} at the peak and {@code 0} before it starts or after it ends
     */
    public double fraction(double elapsedSeconds) {
        if (!Double.isFinite(elapsedSeconds) || elapsedSeconds <= 0.0) {
            return 0.0;
        }
        if (elapsedSeconds < DEFAULT_RISE_SECONDS) {
            return elapsedSeconds / DEFAULT_RISE_SECONDS;
        }
        double recovery = (elapsedSeconds - DEFAULT_RISE_SECONDS) / DEFAULT_RECOVERY_SECONDS;
        if (recovery >= 1.0) {
            return 0.0;
        }
        // Smoothstep back to zero, so the recovery has no kink at either end.
        double remaining = 1.0 - recovery;
        return remaining * remaining * (3.0 - 2.0 * remaining);
    }

    /**
     * @param elapsedSeconds seconds since the shot
     * @return the cumulative pitch offset at that time, in degrees; positive rises
     */
    public double pitchAt(double elapsedSeconds) {
        return peakPitchDegrees * fraction(elapsedSeconds);
    }

    /**
     * @param elapsedSeconds seconds since the shot
     * @return the cumulative yaw offset at that time, in degrees; positive turns right
     */
    public double yawAt(double elapsedSeconds) {
        return peakYawDegrees * fraction(elapsedSeconds);
    }

    /** @return seconds after which this response is guaranteed to contribute nothing. */
    public static double totalDurationSeconds() {
        return DEFAULT_RISE_SECONDS + DEFAULT_RECOVERY_SECONDS;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
