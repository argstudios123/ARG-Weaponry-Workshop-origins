package com.arg_studios.arg_weaponry.weapon.aiming;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The additional ADS zoom applied while a reload is running.
 *
 * <h2>Meaning of {@code zoom_amount}</h2>
 * <p>The reference implementation multiplies the <em>aiming zoom multiplier</em> by {@code 1 - amount * progress}. The
 * value is therefore a fraction of the current zoom distance, not an absolute field of view: {@code 0.06} makes a 0.8
 * aiming zoom become {@code 0.8 * 0.94 = 0.752} at full effect. This class reproduces that meaning; it never turns the
 * amount into an absolute FOV and never changes the weapon's configured {@code aiming_zoom}.</p>
 *
 * <h2>Envelope</h2>
 * <p>The reference ramps the effect in over 200 ms and drops it immediately when the reload ends. Dropping it immediately
 * is a visible camera snap, so this framework uses the same 200 ms transition in both directions: a reload that starts or
 * ends, or an aim that is released, fades the extra zoom in or out instead of stepping. The effect's own runtime condition
 * is <em>reloading and aiming is requested</em>; it deliberately does not read the accepted ADS progress, so
 * {@code no_aiming_on_reload} cannot silently disable it. A reload without an aim request has no effect.</p>
 *
 * <p>This object is owned by the client aim controller and is not thread safe; sampling is a pure read of its two
 * interpolated progress values.</p>
 */
public final class AdsReloadZoomControl {

    /** How long the effect takes to fade in or out, in seconds: the reference's 200 ms. */
    public static final double TRANSITION_SECONDS = 0.20;

    private double progress;
    private double previousProgress;
    private boolean active;

    /**
     * Advances the envelope by one client tick.
     *
     * @param enabled      whether the feature is enabled for the held weapon
     * @param reloading    whether the held weapon is reloading
     * @param aimRequested whether the player is asking to aim (toggle latch or held button), independent of whether normal
     *                     aiming is currently allowed
     * @param deltaSeconds the tick length; invalid or non-positive values only refresh the interpolation snapshot
     */
    public void update(boolean enabled, boolean reloading, boolean aimRequested, double deltaSeconds) {
        previousProgress = progress;
        double target = enabled && reloading && aimRequested ? 1.0 : 0.0;
        active = target > 0.0;
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            return;
        }
        double step = Math.min(deltaSeconds, 0.25) / TRANSITION_SECONDS;
        progress = target > progress ? Math.min(target, progress + step) : Math.max(target, progress - step);
    }

    /**
     * @param partialTick the frame's partial tick
     * @return the eased-in-time progress in {@code [0, 1]}, interpolated between the last two ticks
     */
    public float progress(float partialTick) {
        float fraction = Float.isFinite(partialTick) ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
        return (float) (previousProgress + (progress - previousProgress) * fraction);
    }

    /**
     * @param partialTick the frame's partial tick
     * @param config      the weapon's {@code ads_reload_zoom_control} block, may be {@code null}
     * @return the additional FOV multiplier: {@code 1 - amount * curve(progress)}, or {@code 1} when inactive
     */
    public float multiplier(float partialTick, WeaponDefinition.AdsReloadZoom config) {
        if (config == null || !config.hasEffect()) {
            return 1.0F;
        }
        return multiplier(partialTick, config.curve(), config.zoomAmount());
    }

    /**
     * @param partialTick the frame's partial tick
     * @param curve       the easing curve, may be {@code null} for linear
     * @param amount      the fraction of zoom removed at full effect
     * @return the additional FOV multiplier
     */
    public float multiplier(float partialTick, ZoomCurve curve, double amount) {
        double raw = progress(partialTick);
        if (raw <= 0.0 || !Double.isFinite(amount) || amount <= 0.0) {
            return 1.0F;
        }
        double clampedAmount = Math.min(1.0, amount);
        double eased = curve == null ? raw : curve.evaluate(raw);
        return (float) (1.0 - clampedAmount * eased);
    }

    /** @return whether the effect is being asked for this tick. */
    public boolean active() {
        return active;
    }

    /** @return the unsampled current progress, for diagnostics and tests. */
    public double currentProgress() {
        return progress;
    }

    /** Forgets the envelope, e.g. when the player leaves the world or switches weapons. */
    public void reset() {
        progress = 0.0;
        previousProgress = 0.0;
        active = false;
    }
}
