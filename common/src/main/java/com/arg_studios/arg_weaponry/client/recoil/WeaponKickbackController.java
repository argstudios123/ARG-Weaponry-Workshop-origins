package com.arg_studios.arg_weaponry.client.recoil;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller A: the weapon's kickback - a backwards displacement of the weapon model along its own axis.
 *
 * <p>The ARG Weaponry counterpart of Point Blank's {@code GunRecoilAnimationController}
 * ({@code com.vicmatskiv.pointblank.client.controller}), which keeps the weapon's procedural movement in its own
 * controller, separates it entirely from the player-recoil controller, and applies it to the first-person pose stack as a
 * translation with {@code posZ} positive meaning "towards the player". This class keeps that separation and that axis, and
 * drops the parts of the reference that are not wanted here: no lateral drift, no rotation, no oscillation.</p>
 *
 * <h2>Why the previous response was replaced</h2>
 * <p>Driving the previous controller with the M100 DMR's real configuration (20 Hz ticks, 60 fps frames) exposed two
 * single-frame discontinuities, both caused by the old sustained accumulator:</p>
 * <ul>
 *   <li>it was applied as an <em>instantaneous step</em> at every shot, so the weapon teleported backwards by
 *   {@code positionZIncrement} in the frame the shot landed ({@code +0.050000} blocks, 78% of that frame's peak);</li>
 *   <li>{@link #sample(float)} tested only the per-shot channel before returning, so once that channel reached zero the
 *   still-nonzero sustained displacement was dropped: {@code 0.017045} blocks to exactly {@code 0.000000} in one frame -
 *   the snap that was reported.</li>
 * </ul>
 * <p>Both are gone now, because the backward displacement has exactly one source: the per-shot impulse below, which is a
 * pure function of the shot's age and therefore continuous everywhere, endpoints included.</p>
 *
 * <h2>The response: one impulse per shot, two phases</h2>
 * <pre>
 *   f(t)  1 |      ___
 *           |    /     \
 *           |   /       \___
 *         0 +--|-----------|----- t
 *             K          K+R
 *
 *   Phase A  t in [0, K)      f = smoothstep(t / K)                  (the kick)
 *   Phase B  t in [K, K + R)  f = 1 - smoothstep((t - K) / R)        (the recovery)
 *   f = 0 outside [0, K + R), with smoothstep(x) = x^2 (3 - 2x)
 * </pre>
 * <p>The curve was chosen for these properties, not for a multiplier:</p>
 * <ul>
 *   <li>{@code f(0) = 0} with {@code f'(0) = 0}: the kick starts from rest, so the frame the shot lands cannot move the
 *   weapon by itself;</li>
 *   <li>{@code f} reaches exactly {@code 1} at {@code K} ({@link #KICK_SECONDS}, just under one client tick): the weapon
 *   arrives at its full displacement immediately instead of drifting into position;</li>
 *   <li>{@code f'(K) = 0} from both sides: the weapon turns around at the peak instead of reversing at speed;</li>
 *   <li>{@code f(K + R) = 0} with {@code f'(K + R) = 0}: the recovery decelerates into the resting position and reaches
 *   <em>exactly</em> zero with zero velocity, so there is nothing left to cut off;</li>
 *   <li>{@code 0 <= f <= 1} everywhere: no overshoot, no bounce, no wobble.</li>
 * </ul>
 *
 * <h2>Combining shots</h2>
 * <p>Every shot keeps its own impulse, and the displacement is the <em>sum</em> of the impulses alive at that instant, each
 * evaluated from its own age. A new shot therefore never erases or rewinds what an earlier shot left behind: at its own age
 * zero it contributes exactly nothing, and from then on it adds to the motion that was already happening. The sum is capped
 * at {@link #MAX_OVERLAP_FACTOR} shots' worth (and hard-limited by {@link #MAX_BACKWARD_BLOCKS}), so sustained fire cannot
 * run away, and when the last impulse ends the displacement is exactly zero - no permanent drift.</p>
 *
 * <h2>Sampling and determinism</h2>
 * <p>{@link #tick(double)} is the only method that advances time (once per client tick), and all it does is age the
 * impulses. {@link #sample(float)} evaluates the same curves at the sub-tick instant {@code age + step * partialTick}, so the
 * shape is rendered at the frame rate instead of being linearly interpolated between 20 Hz samples, and it writes nothing:
 * drawing the same frame twice returns the same displacement.</p>
 *
 * <h2>Enablement</h2>
 * <p>The controller is <strong>inert unless the weapon explicitly declares {@code kickback_recoil_amplitude}</strong>:
 * {@link RecoilProfile#hasKickback()} is the only gate, {@link #shot(RecoilProfile)} returns immediately otherwise, and no
 * other recoil setting can start it. A weapon that omits the property is exactly as if this class did not exist.</p>
 */
public final class WeaponKickbackController {

    /** Phase A: seconds from the shot to the peak of the backward displacement. */
    public static final double KICK_SECONDS = 0.045;

    /** Phase B: seconds from the peak back to the resting position. */
    public static final double RECOVERY_SECONDS = 0.240;

    /** The impulse is exactly zero from this age on, because the curve reaches zero there by itself. */
    public static final double IMPULSE_DURATION_SECONDS = KICK_SECONDS + RECOVERY_SECONDS;

    /**
     * How many overlapping shots' worth of displacement the sum of the impulses may reach, as a multiple of the amplitude.
     *
     * <p>At the M100 DMR's 300 RPM (one shot every 200 ms) only two impulses overlap and the sum stays below twice the
     * amplitude; the cap exists so an implausible fire rate, or an implausible amplitude, cannot push the weapon through the
     * camera.</p>
     */
    public static final double MAX_OVERLAP_FACTOR = 3.0;

    /** Hard ceiling of the backward displacement of any configuration, in blocks. */
    public static final double MAX_BACKWARD_BLOCKS = 1.0;

    /** Largest time step one tick may advance, in seconds, so a stall cannot skip the whole response in one go. */
    public static final double MAX_STEP_SECONDS = 0.25;

    /** Step assumed before the first tick has ever run, in seconds; one vanilla client tick. */
    public static final double DEFAULT_STEP_SECONDS = 0.05;

    /**
     * Memory guard on the impulse list.
     *
     * <p>An impulse lives {@link #IMPULSE_DURATION_SECONDS} seconds, so this many alive at once would need more than a
     * hundred shots per second; no content pack can reach it. It exists so a pathological fire rate cannot grow the list
     * without bound. The oldest impulse is only ever dropped at a rate the overlap cap already makes invisible.</p>
     */
    public static final int MAX_CONCURRENT_IMPULSES = 32;

    /** Values below this fraction of a block are reported as exactly zero, so the neutral transform is exact. */
    private static final double EPSILON_BLOCKS = 1.0E-9;

    private final List<Impulse> impulses = new ArrayList<>();

    /** The largest amplitude started since the last reset, in blocks; it defines the overlap ceiling. */
    private double amplitude;

    /** The displacement at the last completed tick, in blocks. */
    private double backward;

    /** The last tick's step in seconds, used to sample between ticks. */
    private double stepSeconds;

    /**
     * Applies one accepted shot to the weapon-model layer.
     *
     * <p>Does nothing unless the weapon explicitly enables kickback. In particular the
     * {@code sustained_firing_Recoil} block alone never starts it.</p>
     *
     * <p>The shot is added as its own impulse and the already-running impulses keep their ages, so the displacement the
     * renderer receives does not change in this instant: nothing is teleported and nothing is rewound. The impulse is born
     * one step in the past and the next tick ages it back to zero, which is what makes the quick kick of
     * {@link #KICK_SECONDS} actually render as a ramp across the following frames instead of being swallowed whole by the
     * tick that delivered the shot.</p>
     *
     * @param profile the weapon's profile, may be {@code null}
     */
    public void shot(RecoilProfile profile) {
        if (profile == null || !profile.hasKickback()) {
            return;
        }
        double shotAmplitude = profile.kickbackBlocks();
        if (!(shotAmplitude > 0.0) || !Double.isFinite(shotAmplitude)) {
            return;
        }
        double step = stepSeconds > 0.0 ? stepSeconds : DEFAULT_STEP_SECONDS;
        impulses.add(new Impulse(shotAmplitude, -step));
        while (impulses.size() > MAX_CONCURRENT_IMPULSES) {
            impulses.remove(0);
        }
        amplitude = Math.max(amplitude, shotAmplitude);
        backward = displacementAt(0.0);
    }

    /**
     * Advances the layer by one client tick.
     *
     * <p>Only the impulses' ages move; the displacement is never multiplied, decayed or clamped per tick, so nothing here
     * can depend on the tick rate. Impulses are dropped once their curve has reached exactly zero.</p>
     *
     * @param deltaSeconds the elapsed time; non-finite or non-positive values advance nothing
     */
    public void tick(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            return;
        }
        stepSeconds = Math.min(deltaSeconds, MAX_STEP_SECONDS);
        for (int index = impulses.size() - 1; index >= 0; index--) {
            Impulse impulse = impulses.get(index);
            impulse.age += stepSeconds;
            if (impulse.age >= IMPULSE_DURATION_SECONDS) {
                impulses.remove(index);
            }
        }
        backward = displacementAt(0.0);
    }

    /**
     * Samples the displacement of one rendered frame.
     *
     * <p>The impulses are evaluated at {@code age + step * partialTick}, i.e. at the frame's own instant inside the tick, so
     * the quick kick of {@link #KICK_SECONDS} is drawn with its real shape at the frame rate instead of being flattened into
     * one linear ramp between two 20 Hz samples. Nothing is written, so a frame drawn twice returns the same displacement and
     * the response cannot drift.</p>
     *
     * @param partialTick the frame's partial tick, clamped to {@code [0, 1]}
     * @return the backwards displacement to draw this frame; {@link Kickback#NONE} when nothing is active
     */
    public Kickback sample(float partialTick) {
        if (impulses.isEmpty()) {
            return Kickback.NONE;
        }
        float tick = Float.isFinite(partialTick) ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
        double total = displacementAt(stepSeconds * tick);
        if (total < EPSILON_BLOCKS) {
            return Kickback.NONE;
        }
        return new Kickback((float) total);
    }

    /** @return the backwards displacement of the last completed tick, in blocks; for diagnostics and tests. */
    public double backwardBlocks() {
        return backward;
    }

    /** @return how many shots currently contribute, for tests and diagnostics. */
    public int activeImpulses() {
        return impulses.size();
    }

    /** @return whether any impulse is still alive. */
    public boolean isActive() {
        return !impulses.isEmpty();
    }

    /** Drops every impulse, leaving the weapon at its exact neutral transform. */
    public void reset() {
        impulses.clear();
        amplitude = 0.0;
        backward = 0.0;
        stepSeconds = 0.0;
    }

    /**
     * The normalised shape of one shot's impulse - the two-phase curve documented on the class.
     *
     * @param seconds the impulse's age in seconds
     * @return {@code 0} at zero age, exactly {@code 1} at {@link #KICK_SECONDS}, and exactly {@code 0} from
     *         {@link #IMPULSE_DURATION_SECONDS} on
     */
    public static double impulseAt(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0 || seconds >= IMPULSE_DURATION_SECONDS) {
            return 0.0;
        }
        if (seconds < KICK_SECONDS) {
            return smoothstep(seconds / KICK_SECONDS);
        }
        return 1.0 - smoothstep((seconds - KICK_SECONDS) / RECOVERY_SECONDS);
    }

    /**
     * @param x a position along a phase, clamped to {@code [0, 1]}
     * @return {@code x²(3 - 2x)}: {@code 0} and {@code 1} at the ends, with zero slope at both
     */
    public static double smoothstep(double x) {
        double clamped = Math.max(0.0, Math.min(1.0, x));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    /**
     * @param extraSeconds how far past the impulses' stored ages to evaluate, for sub-tick sampling
     * @return the summed displacement at that instant, in blocks, capped and never negative
     */
    private double displacementAt(double extraSeconds) {
        double total = 0.0;
        for (Impulse impulse : impulses) {
            total += impulse.amplitude * impulseAt(impulse.age + extraSeconds);
        }
        if (!Double.isFinite(total) || total <= 0.0) {
            return 0.0;
        }
        return Math.min(ceiling(), total);
    }

    /**
     * @return the largest displacement any number of overlapping shots may reach for this weapon, in blocks
     */
    private double ceiling() {
        return Math.min(MAX_BACKWARD_BLOCKS, amplitude * Math.max(1.0, MAX_OVERLAP_FACTOR));
    }

    /**
     * One shot's amplitude and position on its own curve.
     *
     * <p>The age is the time since the shot. It is negative between the instant the shot is registered and the next tick,
     * because the shot arrived inside the tick window rather than at its start; {@link #impulseAt(double)} reports zero for
     * every non-positive age, so a negative age simply means "not started yet".</p>
     */
    private static final class Impulse {

        private final double amplitude;
        private double age;

        private Impulse(double amplitude, double age) {
            this.amplitude = amplitude;
            this.age = age;
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

