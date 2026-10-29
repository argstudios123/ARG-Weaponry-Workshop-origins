package com.arg_studios.arg_weaponry.client.recoil;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller A: the weapon's kickback - a single backwards displacement of the weapon model along its own axis.
 *
 * <p>The ARG Weaponry counterpart of Point Blank's {@code GunRecoilAnimationController}
 * ({@code com.vicmatskiv.pointblank.client.controller}), which keeps the weapon's procedural movement in its own
 * controller, separates it entirely from the player-recoil controller, and applies it to the first-person pose stack as a
 * translation ({@code poseStack.translate(posX, posY, posZ)}) with {@code posZ} positive meaning "towards the player".
 * This rewrite keeps that separation and that axis, and drops the parts of the reference that are not wanted here: no
 * lateral drift, no wobble, no rotation, no oscillation.</p>
 *
 * <h2>The response</h2>
 * <p>One shot adds one impulse. An impulse is a <strong>monotone</strong> function of its age:</p>
 * <pre>
 *   f(t) = (t / T) * e^(1 - t / T)      0 &lt;= t &lt; 8T,  otherwise 0
 * </pre>
 * <p>{@code f} is zero at {@code t = 0}, rises monotonically to exactly {@code 1} at {@code t = T}, then falls
 * monotonically back to zero, and is clamped to exactly zero at {@code t = 8T}. There is no sign change anywhere, so the
 * weapon cannot bounce, wobble or overshoot; there is no discontinuity, so it cannot snap. With
 * {@code T = }{@link #IMPULSE_RISE_SECONDS} the peak lands about 55 ms after the shot - a hair over one client tick - and
 * the weapon is back to its exact original transform by {@link #IMPULSE_DURATION_SECONDS}.</p>
 *
 * <h2>Combining shots</h2>
 * <p>Several impulses may be in flight at once (automatic fire); their displacements <em>add</em>, each still evaluated
 * from its own age, so the behaviour is a predictable function of the shot times rather than a stateful drift. The sum is
 * clamped to {@link #MAX_BACKWARD_BLOCKS} and at most {@link #MAX_CONCURRENT_IMPULSES} impulses contribute, so continuous
 * fire cannot push the weapon away permanently: the moment the last impulse ends, the displacement is exactly zero.</p>
 *
 * <h2>Sampling and determinism</h2>
 * <p>{@link #tick(double)} is the only method that advances time (once per client tick) and it recomputes the current and
 * previous displacement from the impulse list. {@link #sample(float)} only interpolates between those two samples, so
 * drawing a frame cannot advance recoil, and drawing the same frame twice returns the same value.</p>
 *
 * <h2>Enablement</h2>
 * <p>The controller is <strong>inert unless the weapon explicitly declares {@code kickback_recoil_amplitude}</strong>:
 * {@link RecoilProfile#hasKickback()} is the only gate, {@link #shot(RecoilProfile)} returns immediately otherwise, and no
 * other recoil setting can start it. A weapon that omits the property is exactly as if this class did not exist.</p>
 */
public final class WeaponKickbackController {

    /** Seconds from the shot to the peak of the backward displacement. */
    public static final double IMPULSE_RISE_SECONDS = 0.055;

    /** The impulse is exactly zero again after this many seconds. */
    public static final double IMPULSE_DURATION_SECONDS = 8.0 * IMPULSE_RISE_SECONDS;

    /** Largest displacement any number of shots may accumulate, in blocks. */
    public static final double MAX_BACKWARD_BLOCKS = 1.0;

    /** Largest number of impulses that may contribute at the same time. */
    public static final int MAX_CONCURRENT_IMPULSES = 8;

    /** Time constant of the sustained term's decay after firing stops, in seconds. */
    public static final double SUSTAINED_DECAY_SECONDS = 0.40;

    /** Values below this fraction of a block are treated as zero, so the neutral transform is exact. */
    private static final double EPSILON_BLOCKS = 1.0E-6;

    private final List<Impulse> impulses = new ArrayList<>();

    private double previousBackward;
    private double backward;
    private double sustainedBackward;
    private double previousSustainedBackward;

    /**
     * Applies one accepted shot to the weapon-model layer.
     *
     * <p>Does nothing unless the weapon explicitly enables kickback. In particular the
     * {@code sustained_firing_Recoil} block alone never starts it.</p>
     *
     * @param profile the weapon's profile, may be {@code null}
     */
    public void shot(RecoilProfile profile) {
        if (profile == null || !profile.hasKickback()) {
            return;
        }
        double amplitude = profile.kickbackBlocks();
        if (!(amplitude > 0.0) || !Double.isFinite(amplitude)) {
            return;
        }
        impulses.add(new Impulse(amplitude));
        while (impulses.size() > MAX_CONCURRENT_IMPULSES) {
            impulses.remove(0);
        }
        // A shot is instantaneous, so both interpolation samples jump together instead of ramping in from the previous
        // tick's value.
        sustainedBackward = clampSustained(sustainedBackward + sustainedIncrement(profile),
                sustainedCap(profile));
        previousSustainedBackward = sustainedBackward;
        recompute();
        previousBackward = backward;
    }

    /**
     * Advances the layer by one client tick.
     *
     * @param deltaSeconds the elapsed time; non-finite or non-positive values advance nothing
     */
    public void tick(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            return;
        }
        double delta = Math.min(deltaSeconds, 0.25);
        previousBackward = backward;
        previousSustainedBackward = sustainedBackward;

        for (int index = impulses.size() - 1; index >= 0; index--) {
            Impulse impulse = impulses.get(index);
            impulse.age += delta;
            if (impulse.age >= IMPULSE_DURATION_SECONDS) {
                impulses.remove(index);
            }
        }

        if (sustainedBackward != 0.0) {
            sustainedBackward *= Math.exp(-delta / SUSTAINED_DECAY_SECONDS);
            if (sustainedBackward < EPSILON_BLOCKS) {
                sustainedBackward = 0.0;
            }
        }
        recompute();
    }

    /**
     * @param partialTick the frame's partial tick, clamped to {@code [0, 1]}
     * @return the backwards displacement to draw this frame; {@link Kickback#NONE} when nothing is active
     */
    public Kickback sample(float partialTick) {
        if (backward <= 0.0 && previousBackward <= 0.0) {
            return Kickback.NONE;
        }
        float t = Float.isFinite(partialTick) ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
        double drawn = previousBackward + (backward - previousBackward) * t;
        double sustainedDrawn = previousSustainedBackward + (sustainedBackward - previousSustainedBackward) * t;
        double total = Math.min(MAX_BACKWARD_BLOCKS, Math.max(0.0, drawn + sustainedDrawn));
        if (total < EPSILON_BLOCKS) {
            return Kickback.NONE;
        }
        return new Kickback((float) total);
    }

    /** @return the backwards displacement of the last completed tick, in blocks; for diagnostics and tests. */
    public double backwardBlocks() {
        return Math.min(MAX_BACKWARD_BLOCKS, Math.max(0.0, backward + sustainedBackward));
    }

    /** @return the part of the displacement that comes from the sustained term, in blocks; for tests. */
    public double sustainedBackwardBlocks() {
        return sustainedBackward;
    }

    /** @return how many shots currently contribute, for tests and diagnostics. */
    public int activeImpulses() {
        return impulses.size();
    }

    /** @return whether any displacement is still being applied. */
    public boolean isActive() {
        return backwardBlocks() >= EPSILON_BLOCKS;
    }

    /** Drops every impulse and the sustained term, leaving the weapon at its exact neutral transform. */
    public void reset() {
        impulses.clear();
        previousBackward = 0.0;
        backward = 0.0;
        sustainedBackward = 0.0;
        previousSustainedBackward = 0.0;
    }

    /**
     * The normalised shape of one impulse.
     *
     * @param seconds the impulse's age in seconds
     * @return {@code 0} at zero age, exactly {@code 1} at {@link #IMPULSE_RISE_SECONDS}, and {@code 0} from
     *         {@link #IMPULSE_DURATION_SECONDS} on
     */
    public static double impulseAt(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0 || seconds >= IMPULSE_DURATION_SECONDS) {
            return 0.0;
        }
        double scaled = seconds / IMPULSE_RISE_SECONDS;
        return scaled * Math.exp(1.0 - scaled);
    }

    private void recompute() {
        double total = 0.0;
        for (Impulse impulse : impulses) {
            total += impulse.amplitude * impulseAt(impulse.age);
        }
        backward = Math.min(MAX_BACKWARD_BLOCKS, total);
        if (backward < EPSILON_BLOCKS) {
            backward = 0.0;
        }
    }

    /**
     * @param profile the weapon's profile
     * @return the backward displacement one shot adds to the sustained term, in blocks
     */
    private static double sustainedIncrement(RecoilProfile profile) {
        // Only the backward channel is applied. The block's vertical and pitch increments are still parsed and preserved
        // by the profile, but they are deliberately not applied here: kickback must not move the weapon upwards, and the
        // vertical channel is exactly what caused that in the previous implementation.
        return profile.sustained().positionZIncrement();
    }

    private static double sustainedCap(RecoilProfile profile) {
        return Math.min(MAX_BACKWARD_BLOCKS, profile.sustained().positionZCap());
    }

    private static double clampSustained(double value, double cap) {
        if (!Double.isFinite(value) || value <= 0.0 || cap <= 0.0) {
            return 0.0;
        }
        return Math.min(value, cap);
    }

    /** One shot's amplitude and how long it has been running. */
    private static final class Impulse {

        private final double amplitude;
        private double age;

        private Impulse(double amplitude) {
            this.amplitude = amplitude;
        }
    }

    /**
     * One frame's weapon kickback: a displacement backwards along the weapon's own axis.
     *
     * <p>Deliberately a single scalar. The previous implementation returned separate X, Y and Z values plus a rotation,
     * which is how an upward translation could exist at all. The axis is resolved by {@link WeaponKickbackAxis} from the
     * placement the renderer uses, so no component other than "backwards along the barrel" can be produced.</p>
     *
     * @param backwardBlocks how far the weapon moves towards the player, in blocks
     */
    public record Kickback(float backwardBlocks) {

        /** No kickback at all: the weapon stays at its exact authored/ADS transform. */
        public static final Kickback NONE = new Kickback(0.0F);

        /** @return whether this displacement would change the weapon at all. */
        public boolean isZero() {
            return backwardBlocks == 0.0F;
        }
    }
}

