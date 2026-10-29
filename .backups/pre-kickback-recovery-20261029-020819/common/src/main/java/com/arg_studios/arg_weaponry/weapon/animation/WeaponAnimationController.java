package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.ResourceId;

import java.util.Optional;

/**
 * Advances one animation over time and exposes the evaluated pose.
 *
 * <p>This is the only mutable object in the animation pipeline, and it holds nothing but playback state for
 * the one animation it was asked to play: the immutable {@link BedrockAnimation} it plays, the elapsed time,
 * and two flags. It never touches the parsed animation, the model, the cache or the renderer, so two
 * controllers can play different animations, or the same animation at different times, without any
 * interference.</p>
 *
 * <h2>Time</h2>
 * <p>Time only advances when the owner calls {@link #tick(double)} with an explicit delta in seconds. The
 * controller never reads a clock, so playback is deterministic and frame-rate independent: sixty 1/60 s
 * ticks, thirty 1/30 s ticks and one 1 s tick all end at the same elapsed time and therefore at the same
 * pose. Invalid deltas ({@code NaN}, infinity, zero or negative) are ignored rather than corrupting the
 * state.</p>
 *
 * <h2>States</h2>
 * <ul>
 *     <li><strong>idle</strong> - no animation. {@link #isPlaying()} false, {@link #isFinished()} false,
 *     {@link #currentPose()} is {@link WeaponPose#NONE}.</li>
 *     <li><strong>playing</strong> - an animation is advancing. A looping animation stays here forever.</li>
 *     <li><strong>finished</strong> - a non-looping animation reached its end. {@link #isPlaying()} false,
 *     {@link #isFinished()} true, and the terminal pose keeps being returned by {@link #currentPose()} so the
 *     owner can decide whether to hold it (Bedrock's {@code hold_on_last_frame}) or blend away from it later.
 *     A finished animation never restarts by itself.</li>
 *     <li><strong>stopped</strong> - after {@link #stop()}: back to idle, pose cleared.</li>
 * </ul>
 *
 * <h2>Loop behaviour</h2>
 * <p>The mode comes from the animation itself ({@link LoopMode}); an unspecified property is treated exactly
 * like the sampler treats it, as {@link LoopMode#ONCE}, and the parsed data is never rewritten. A caller may
 * pass an explicit override (used by the development playback test to loop a one-shot clip). Looping
 * animations wrap their elapsed time into {@code [0, length)} instead of accumulating unbounded time, and the
 * wrap matches {@link AnimationTime}: the exact duration resolves to {@code 0}.</p>
 *
 * <h2>Threading</h2>
 * <p>All state access is {@code synchronized} on the controller and every pose handed out is an immutable
 * {@link WeaponPose}, so a client update path and a render path can share one controller safely. The lock is
 * only held for the cheap state update or for one sample of one animation.</p>
 */
public final class WeaponAnimationController {

    private BedrockAnimation animation;
    private String animationName;
    private LoopMode loopOverride;
    private double elapsedSeconds;
    private boolean playing;
    private boolean finished;
    private WeaponPose cachedPose;
    private double cachedPoseTime = Double.NaN;
    private boolean paused;

    /** Largest sub-tick offset {@link #poseAt(double)} will sample outside the current playback time. */
    private static final double MAX_SAMPLE_OFFSET_SECONDS = 0.1;

    /**
     * Starts an animation from the beginning, replacing whatever was playing.
     *
     * @param animation the animation definition to play
     * @return {@code true} when playback started, {@code false} for a {@code null} animation
     */
    public boolean play(BedrockAnimation animation) {
        return play(animation, null);
    }

    /**
     * Starts an animation from the beginning with an explicit looping policy.
     *
     * @param animation    the animation definition to play
     * @param loopOverride {@link LoopMode#LOOP} or {@link LoopMode#ONCE} to override the animation's own
     *                     setting, or {@code null} to respect the animation
     * @return {@code true} when playback started, {@code false} for a {@code null} animation
     */
    public synchronized boolean play(BedrockAnimation animation, LoopMode loopOverride) {
        if (animation == null) {
            return false;
        }
        this.animation = animation;
        this.animationName = animation.name();
        this.loopOverride = loopOverride;
        this.elapsedSeconds = 0.0;
        this.playing = true;
        this.finished = false;
        this.paused = false;
        this.cachedPose = null;
        this.cachedPoseTime = Double.NaN;
        return true;
    }

    /**
     * Resolves an animation through {@link WeaponAnimationCache} and plays it.
     *
     * <p>A missing document or an unknown animation name leaves the controller exactly as it was: no
     * substitute animation is played and no empty animation is invented. The cache already reports the
     * failure once per resource, so a failed start cannot flood the log.</p>
     *
     * @param pack          the owning content pack
     * @param resource      the animation resource identifier from the weapon's display definition
     * @param animationName the animation name as written in the file
     * @return {@code true} when playback started
     */
    public boolean play(ContentPack pack, ResourceId resource, String animationName) {
        return play(pack, resource, animationName, null);
    }

    /**
     * Resolves an animation through {@link WeaponAnimationCache} and plays it with an explicit loop policy.
     *
     * @param pack          the owning content pack
     * @param resource      the animation resource identifier from the weapon's display definition
     * @param animationName the animation name as written in the file
     * @param loopOverride  the looping policy to use, or {@code null} to respect the animation
     * @return {@code true} when playback started
     */
    public boolean play(ContentPack pack, ResourceId resource, String animationName, LoopMode loopOverride) {
        if (pack == null || resource == null || animationName == null || animationName.isBlank()) {
            return false;
        }
        Optional<BedrockAnimation> resolved = WeaponAnimationCache.instance().animation(pack, resource,
                animationName);
        return resolved.isPresent() && play(resolved.get(), loopOverride);
    }

    /**
     * Advances playback by an explicit delta.
     *
     * @param deltaSeconds the elapsed time in seconds; zero, negative, {@code NaN} and infinite values are
     *                     ignored
     */
    public synchronized void tick(double deltaSeconds) {
        if (!playing || paused || animation == null) {
            return;
        }
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            return;
        }

        double length = animation.lengthSeconds();
        if (effectiveLoopMode() == LoopMode.LOOP) {
            if (!(length > 0.0)) {
                elapsedSeconds = 0.0;
                return;
            }
            double wrapped = (elapsedSeconds + deltaSeconds) % length;
            if (wrapped < 0.0 || wrapped >= length || !Double.isFinite(wrapped)) {
                wrapped = 0.0;
            }
            elapsedSeconds = wrapped;
            invalidatePose();
            return;
        }

        elapsedSeconds = Math.min(elapsedSeconds + deltaSeconds, length);
        if (elapsedSeconds >= length) {
            elapsedSeconds = length;
            playing = false;
            finished = true;
        }
        invalidatePose();
    }

    /**
     * Returns the pose for the current playback time.
     *
     * <p>Sampling happens at most once per elapsed time, so reading the pose several times in one frame is
     * free, and reading it never advances playback.</p>
     *
     * @return the current immutable pose, or {@link WeaponPose#NONE} when nothing is loaded
     */
    public synchronized WeaponPose currentPose() {
        if (animation == null) {
            return WeaponPose.NONE;
        }
        if (cachedPose != null && Double.compare(cachedPoseTime, elapsedSeconds) == 0) {
            return cachedPose;
        }
        cachedPose = WeaponPose.fromSampledAnimation(AnimationSampler.sample(animation, elapsedSeconds));
        cachedPoseTime = elapsedSeconds;
        return cachedPose;
    }

    /**
     * Returns the pose at the current playback time plus an explicit offset, without advancing playback.
     *
     * <p>Milestone 10S moved playback advancement to the client tick so the timeline keeps running while the weapon is
     * not being rendered (third-person view, a GUI, a hidden hand). Rendering therefore must only <em>sample</em>, never
     * advance - and this is the sampling entry point: it evaluates the same animation at {@code elapsed + offset}, which
     * is what a partial-tick interpolation needs to stay smooth at more than twenty frames per second.</p>
     *
     * <p>The offset is clamped to one tick in each direction, so a caller cannot sample far outside the current state.
     * A looping animation wraps the sample time exactly like {@link #tick(double)} does; a one-shot animation clamps it
     * to its own length.</p>
     *
     * @param offsetSeconds the sub-tick offset in seconds; non-finite values are treated as zero
     * @return the sampled pose, or {@link WeaponPose#NONE} when nothing is playing
     */
    public synchronized WeaponPose poseAt(double offsetSeconds) {
        if (animation == null) {
            return WeaponPose.NONE;
        }
        if (!Double.isFinite(offsetSeconds) || offsetSeconds == 0.0) {
            return currentPose();
        }
        double offset = Math.max(-MAX_SAMPLE_OFFSET_SECONDS, Math.min(MAX_SAMPLE_OFFSET_SECONDS, offsetSeconds));
        double length = animation.lengthSeconds();
        double time = elapsedSeconds + offset;
        if (effectiveLoopMode() == LoopMode.LOOP && length > 0.0) {
            double wrapped = time % length;
            time = wrapped < 0.0 ? wrapped + length : wrapped;
        } else {
            time = Math.max(0.0, Math.min(time, length));
        }
        return WeaponPose.fromSampledAnimation(AnimationSampler.sample(animation, time));
    }

    /** Stops playback and clears the pose, returning the controller to the idle state. */
    public synchronized void stop() {
        animation = null;
        animationName = null;
        loopOverride = null;
        elapsedSeconds = 0.0;
        playing = false;
        finished = false;
        paused = false;
        invalidatePose();
    }

    /**
     * Freezes playback at the current time and pose.
     *
     * <p>A paused controller still reports {@link #isPlaying() playing} - an animation is loaded and the
     * pose is still valid - but {@link #tick(double)} no longer advances it. Pausing an idle or finished
     * controller does nothing.</p>
     */
    public synchronized void pause() {
        if (playing) {
            paused = true;
        }
    }

    /** Continues a paused animation from the time it was paused at. */
    public synchronized void resume() {
        paused = false;
    }

    /** @return {@code true} while playback is frozen at its current time. */
    public synchronized boolean isPaused() {
        return paused;
    }

    /**
     * Moves the playback time without starting or stopping playback.
     *
     * <p>Seeking is a pure sampling operation: the time is clamped to {@code [0, length]} for a one-shot
     * animation and wrapped into {@code [0, length)} for a looping one, then the pose is re-evaluated on the
     * next read. Seeking to the end of a one-shot clip does not mark it finished, because completion is a
     * playback event, not a sampling one.</p>
     *
     * @param seconds the requested time in seconds; {@code NaN} and infinities are ignored
     */
    public synchronized void seek(double seconds) {
        if (animation == null || !Double.isFinite(seconds)) {
            return;
        }
        double length = animation.lengthSeconds();
        if (effectiveLoopMode() == LoopMode.LOOP && length > 0.0) {
            double wrapped = seconds % length;
            elapsedSeconds = wrapped < 0.0 ? wrapped + length : wrapped;
        } else {
            elapsedSeconds = Math.max(0.0, Math.min(seconds, length));
        }
        invalidatePose();
    }

    /** @return {@code true} while the animation is advancing. */
    public synchronized boolean isPlaying() {
        return playing;
    }

    /** @return {@code true} once a non-looping animation reached its end and was not stopped. */
    public synchronized boolean isFinished() {
        return finished;
    }

    /** @return the exact animation name being played, or {@code null} when idle. */
    public synchronized String animationName() {
        return animationName;
    }

    /** @return the playback time in seconds, never negative; a finished animation reports its length. */
    public synchronized double elapsedSeconds() {
        return elapsedSeconds;
    }

    /** @return the duration of the animation being played, or {@code 0} when idle. */
    public synchronized double durationSeconds() {
        return animation == null ? 0.0 : animation.lengthSeconds();
    }

    /**
     * @return the looping policy in force: the explicit override when one was supplied, otherwise the
     *         animation's own setting, with an unspecified setting treated as {@link LoopMode#ONCE}
     */
    public synchronized LoopMode effectiveLoopMode() {
        if (loopOverride != null) {
            return loopOverride;
        }
        return animation == null ? LoopMode.ONCE : AnimationTime.effectiveLoopMode(animation);
    }

    private void invalidatePose() {
        cachedPose = null;
        cachedPoseTime = Double.NaN;
    }
}
