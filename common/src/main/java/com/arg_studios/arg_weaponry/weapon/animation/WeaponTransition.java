package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * The cross-fade between two animation states, ported from TaCZ's animation runner.
 *
 * <h2>Where this comes from</h2>
 * <p>TaCZ (Refabricated 26.3) implements every animation change in {@code ObjectAnimationRunner}:</p>
 * <ul>
 *   <li>{@code AnimationController#run(track, name, playType, transitionSeconds)} creates the new runner, calls {@code run()}
 *   on it and then hands it to the runner that is currently playing:
 *   {@code oldRunner.transition(newRunner, transitionSeconds * 1e9)};</li>
 *   <li>{@code transition(...)} walks the <em>outgoing</em> animation's channels, finds the matching channel of the incoming
 *   animation per bone and channel type, records the outgoing channel's value at the moment of the switch
 *   ({@code valueFrom}) and pairs the two channels. Channels the incoming animation does not have are collected as
 *   <em>recover</em> channels;</li>
 *   <li>{@code update(...)} advances the transition clock and calls {@code updateTransition(easeOutCubic(p), soundOnly)},
 *   which computes the incoming channel's <strong>live</strong> value (the incoming runner keeps playing), interpolates
 *   <em>from</em> the outgoing value <em>to</em> that live value, using {@code lerp} for translation and scale and
 *   {@code slerp} for rotation, and fades every recover channel towards its identity value;</li>
 *   <li>the outgoing runner stops advancing its own clip ({@code running = false}), so the pose it fades from is frozen at
 *   the switch, while the incoming animation is already playing when the fade starts - there is no restart and no second
 *   jump at the end of the fade;</li>
 *   <li>the easing is {@code easeOutCubic(p) = 1 - (1 - p)^4}: most of the change happens early and the tail is long, which
 *   is what makes a short fade read as "settling" rather than as a linear slide.</li>
 * </ul>
 *
 * <h2>How it is adapted</h2>
 * <p>Argos already has the value algebra in {@link WeaponPoseBlend}: it blends per bone and channel, slerps rotations on the
 * shortest arc, and fades a channel that exists on one side only against the identity delta. That is the same rule TaCZ's
 * transition applies, so this class owns the <em>state and timing</em> (what TaCZ's runner owns) and delegates the algebra.
 * The incoming pose passed to {@link #apply(WeaponPose, double)} is the live sample of the clip that is playing now, so the
 * incoming animation keeps advancing during the fade exactly as it does in TaCZ.</p>
 *
 * <p>Unlike TaCZ's per-channel runner, Argos has no per-channel listeners - a pose is a value - so a channel the incoming
 * clip does not animate is recovered by {@link WeaponPoseBlend} rather than by a separate channel list. The observable
 * behaviour is the same: a bone the new state does not drive returns smoothly to its authored transform.</p>
 *
 * <p>The class is pure (no clock, no Minecraft, no renderer), so the timing and the easing are unit tested.</p>
 */
public final class WeaponTransition {

    /** The longest fade the controller asks for; a longer request is clamped, never rejected. */
    public static final double MAX_SECONDS = 0.5;

    /**
     * The shortest fade for which TaCZ's ease-out curve is used.
     *
     * <p>TaCZ's {@code easeOutCubic} is {@code 1 - (1 - p)^4}: it moves most of the change in its first frames. With TaCZ's
     * own transition times (a fifth to half a second) that is exactly the "settles quickly, keeps easing" feel the curve is
     * chosen for. At the short fades this controller uses for actions it is not usable: a tenth of a second is two client
     * ticks, so the curve would move 94% of the change in the <em>first</em> one and the transition would read as a cut - the
     * one-frame jump this work is meant to remove (measured by {@code WeaponViewControllerEquipTest}, which rejects any step
     * above 30 degrees of a 90 degree change). Short fades therefore use a curve with zero velocity at both ends, and the
     * ported curve is used where the fade is long enough for it: the return to idle.</p>
     */
    public static final double TA_CZ_CURVE_MIN_SECONDS = 0.25;

    private WeaponPose from;
    private double elapsedSeconds;
    private double durationSeconds;

    /**
     * Starts a transition from the pose that is on screen right now.
     *
     * @param from            the outgoing pose, may be {@code null} or empty (nothing to fade from)
     * @param durationSeconds the fade length; {@code 0} or less disables the fade
     */
    public void start(WeaponPose from, double durationSeconds) {
        clear();
        if (from == null || from.isEmpty() || !(durationSeconds > 0.0) || !Double.isFinite(durationSeconds)) {
            return;
        }
        this.from = from;
        this.durationSeconds = Math.min(durationSeconds, MAX_SECONDS);
        this.elapsedSeconds = 0.0;
    }

    /**
     * Advances the fade by one tick.
     *
     * @param deltaSeconds the tick's duration; non-finite and negative values are ignored
     * @return {@code true} while the fade is still running
     */
    public boolean advance(double deltaSeconds) {
        if (!isActive()) {
            return false;
        }
        if (Double.isFinite(deltaSeconds) && deltaSeconds > 0.0) {
            elapsedSeconds += Math.min(deltaSeconds, MAX_SECONDS);
        }
        if (elapsedSeconds >= durationSeconds) {
            clear();
            return false;
        }
        return true;
    }

    /**
     * Applies the fade to the live pose of the clip that is playing.
     *
     * @param sampled       the pose the current clip evaluates to, may be {@code null}
     * @param offsetSeconds the sub-tick offset this frame is sampled at
     * @return the outgoing pose while the fade is at its start, the live pose once it has finished, and the blend in
     *         between; {@code sampled} itself when no fade is running
     */
    public WeaponPose apply(WeaponPose sampled, double offsetSeconds) {
        if (!isActive()) {
            return sampled;
        }
        float weight = weight(offsetSeconds);
        WeaponPose blended = WeaponPoseBlend.blend(from, sampled, weight);
        return blended.isEmpty() ? null : blended;
    }

    /**
     * The eased position of the fade, TaCZ's {@code easeOutCubic}.
     *
     * @param offsetSeconds the sub-tick offset this frame is sampled at
     * @return the blend weight in {@code [0, 1]}: {@code 0} is the outgoing pose, {@code 1} the live one
     */
    public float weight(double offsetSeconds) {
        if (!isActive()) {
            return 1.0F;
        }
        double offset = Double.isFinite(offsetSeconds) ? Math.max(0.0, offsetSeconds) : 0.0;
        double position = Math.min(1.0, Math.max(0.0, (elapsedSeconds + offset) / durationSeconds));
        return durationSeconds >= TA_CZ_CURVE_MIN_SECONDS ? easeOutCubic(position) : smoothstep(position);
    }

    /**
     * A zero-velocity-at-both-ends curve, used for fades too short for TaCZ's ease-out.
     *
     * @param progress the linear progress in {@code [0, 1]}
     * @return the eased weight in {@code [0, 1]}
     */
    public static float smoothstep(double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        return (float) (clamped * clamped * (3.0 - 2.0 * clamped));
    }

    /**
     * TaCZ's easing, exactly: {@code 1 - (1 - p)^4}.
     *
     * <p>It starts with the highest velocity of the curve and decelerates to zero at the end, which is what hides a pose
     * discontinuity without making the weapon feel sluggish. It is deliberately not a smoothstep: TaCZ's transitions use
     * this curve, and matching it is what the port is for.</p>
     *
     * @param progress the linear progress in {@code [0, 1]}
     * @return the eased weight in {@code [0, 1]}
     */
    public static float easeOutCubic(double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        double remaining = 1.0 - clamped;
        return (float) (1.0 - remaining * remaining * remaining * remaining);
    }

    /** @return whether a fade is running. */
    public boolean isActive() {
        return from != null && durationSeconds > 0.0;
    }

    /** @return the pose the current fade started from, or {@code null}. */
    public WeaponPose from() {
        return from;
    }

    /** @return the fade's length in seconds, {@code 0} when none is running. */
    public double durationSeconds() {
        return durationSeconds;
    }

    /** Drops the fade; the live pose is used from the next sample on. */
    public void clear() {
        from = null;
        elapsedSeconds = 0.0;
        durationSeconds = 0.0;
    }
}
