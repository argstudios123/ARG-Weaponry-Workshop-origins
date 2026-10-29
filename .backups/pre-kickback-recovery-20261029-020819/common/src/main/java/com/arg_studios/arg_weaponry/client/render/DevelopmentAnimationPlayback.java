package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.ChannelKind;
import com.arg_studios.arg_weaponry.weapon.animation.LoopMode;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationController;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.dev.DevelopmentWeapons;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Development-only live playback of one M100 DMR animation.
 *
 * <p>This is the manual test for the playback controller: it owns exactly one
 * {@link WeaponAnimationController}, starts {@value #ANIMATION_NAME} through {@link
 * com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationCache}, advances it once per rendered frame
 * with the real frame time, and hands the current immutable pose to the first-person renderer. It is
 * <strong>not</strong> gameplay: nothing here triggers animations on firing, reloading, inspecting, drawing,
 * walking or aiming, and it cannot run in production without forcing both switches.</p>
 *
 * <h2>Enabling it</h2>
 * <pre>
 * -Darg_weaponry.dev_weapons=true -Darg_weaponry.dev_weapon_playback=true
 * </pre>
 * <p>The older fixed-pose fixture ({@code -Darg_weaponry.dev_animation_pose=true}) still works and is used
 * only when playback is not enabled, so {@code arg_weaponry.dev_animation_pose} keeps its Stage 8C meaning.</p>
 *
 * <h2>What the user should see</h2>
 * <p>{@value #ANIMATION_NAME} is 0.4833 s long and is a one-shot clip in the asset. The development test
 * explicitly overrides the looping policy to {@link LoopMode#LOOP} so the clip repeats forever without any
 * key binding: the bolt carrier ({@value #ANIMATED_BONE}.{@value #ANIMATED_CHANNEL_JSON}) slides back along
 * its own axis (0, 0, 2.2, 4.41 model units and back), and the hand/root channels of the same clip move the
 * weapon slightly. The animation asset itself is untouched; only the playback policy is overridden.</p>
 *
 * <h2>Restarting</h2>
 * <p>Because the clip loops, it never needs restarting. Reloading resources ({@code F3+T}) stops the
 * controller through {@link WeaponRenderer#invalidateCaches()} and the next frame starts it again from a
 * freshly cached document.</p>
 *
 * <h2>Ownership</h2>
 * <p>One controller is intentionally used for this single local development session; it is keyed by nothing
 * because the test only ever renders the held development weapon. Per-instance playback for real gameplay is
 * a later milestone and must key controllers by a stable owner.</p>
 */
public final class DevelopmentAnimationPlayback {

    /** System property that turns live playback on. Off unless explicitly set to {@code true}. */
    public static final String ENABLE_PROPERTY = "arg_weaponry.dev_weapon_playback";

    /** The animation played by the development test. */
    public static final String ANIMATION_NAME = "anim.model.fire";

    /** The looping policy forced by the development test (the asset itself is one-shot). */
    public static final LoopMode LOOP_POLICY = LoopMode.LOOP;

    /** The bone whose movement is easiest to see. */
    public static final String ANIMATED_BONE = "bolt2";

    /** The channel that moves on {@link #ANIMATED_BONE}. */
    public static final ChannelKind ANIMATED_CHANNEL = ChannelKind.POSITION;

    /** The channel name written in the animation file, for log messages. */
    public static final String ANIMATED_CHANNEL_JSON = "position";

    /** Longest frame delta fed to the controller, so a paused client does not jump through the clip. */
    private static final double MAX_FRAME_DELTA_SECONDS = 0.25;

    /** How many frames are logged as evidence that playback really advances. */
    private static final int EVIDENCE_FRAMES = 12;

    private static final WeaponAnimationController CONTROLLER = new WeaponAnimationController();
    private static final Set<String> reported = new HashSet<>();

    private static ResourceId playingResource;
    private static long lastFrameNanos;
    private static int loggedFrames;

    private DevelopmentAnimationPlayback() {
    }

    /** @return {@code true} when the playback switch and the development weapon switch are both on. */
    public static boolean isEnabled() {
        String override = System.getProperty(ENABLE_PROPERTY);
        if (override == null || !Boolean.parseBoolean(override.trim())) {
            return false;
        }
        return DevelopmentWeapons.isEnabled();
    }

    /**
     * Advances the development playback by one rendered frame, using the real frame time.
     *
     * <p>Called from the first-person render hook, which is the only per-frame client callback this project
     * already owns. The renderer itself never advances time; it only receives the pose.</p>
     *
     * @param pack       the content pack of the weapon being rendered
     * @param definition the weapon definition being rendered
     */
    public static void beginFrame(ContentPack pack, WeaponDefinition definition) {
        if (!isEnabled()) {
            return;
        }
        long now = System.nanoTime();
        double deltaSeconds = lastFrameNanos == 0L ? 0.0 : (now - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = now;
        advance(pack, definition, deltaSeconds);
    }

    /**
     * Starts the clip when needed and advances it by an explicit delta.
     *
     * <p>Split out from {@link #beginFrame(ContentPack, WeaponDefinition)} so the development fixture can be
     * exercised deterministically without a clock.</p>
     *
     * @param pack         the content pack of the weapon being rendered
     * @param definition   the weapon definition being rendered
     * @param deltaSeconds the frame time in seconds; clamped to a sane maximum, invalid values ignored
     * @return the pose for this frame, or {@code null} when the fixture is off or could not start
     */
    public static WeaponPose advance(ContentPack pack, WeaponDefinition definition, double deltaSeconds) {
        if (!isEnabled() || pack == null || definition == null) {
            return null;
        }
        WeaponDisplay display = WeaponDisplayCache.instance().get(pack, definition).orElse(null);
        if (display == null || display.animations() == null) {
            warnOnce("display-" + (definition.fileIdentity() == null ? "?" : definition.fileIdentity().asString()),
                    "Development playback is enabled but the weapon declares no animation resource; nothing plays");
            return null;
        }

        boolean needsStart = !display.animations().equals(playingResource)
                || (!CONTROLLER.isPlaying() && !CONTROLLER.isFinished());
        if (needsStart && !start(pack, display)) {
            return null;
        }

        double delta = deltaSeconds;
        if (!Double.isFinite(delta) || delta < 0.0) {
            delta = 0.0;
        }
        if (delta > MAX_FRAME_DELTA_SECONDS) {
            delta = MAX_FRAME_DELTA_SECONDS;
        }
        CONTROLLER.tick(delta);
        logEvidence(delta);
        return CONTROLLER.currentPose();
    }

    /**
     * @return the pose the renderer should apply this frame, or {@code null} when the fixture is disabled or
     *         has nothing to show (so the renderer falls back to static rendering)
     */
    public static WeaponPose currentPose() {
        if (!isEnabled() || (!CONTROLLER.isPlaying() && !CONTROLLER.isFinished())) {
            return null;
        }
        WeaponPose pose = CONTROLLER.currentPose();
        return pose.isEmpty() ? null : pose;
    }

    /** Stops development playback and forgets the loaded clip; called when content is reloaded. */
    public static void reset() {
        CONTROLLER.stop();
        playingResource = null;
        lastFrameNanos = 0L;
        loggedFrames = 0;
        synchronized (reported) {
            reported.clear();
        }
    }

    /** @return the controller owned by the development fixture, for diagnostics and tests. */
    public static WeaponAnimationController controller() {
        return CONTROLLER;
    }

    private static boolean start(ContentPack pack, WeaponDisplay display) {
        if (!CONTROLLER.play(pack, display.animations(), ANIMATION_NAME, LOOP_POLICY)) {
            warnOnce("start-" + display.animations().asString(), "Development playback could not start '"
                    + ANIMATION_NAME + "' from '" + display.animations().asString() + "'; nothing plays");
            return false;
        }
        playingResource = display.animations();
        loggedFrames = 0;
        Constants.LOG.info("[ARG] Development playback started: '{}' from '{}' (policy {}), duration {}s",
                ANIMATION_NAME, display.animations().asString(), LOOP_POLICY, CONTROLLER.durationSeconds());
        return true;
    }

    private static void logEvidence(double deltaSeconds) {
        if (loggedFrames >= EVIDENCE_FRAMES) {
            return;
        }
        loggedFrames++;
        WeaponPose pose = CONTROLLER.currentPose();
        SampledBone bone = pose.bone(ANIMATED_BONE);
        Vec3 value = bone == null ? null : bone.channel(ANIMATED_CHANNEL);
        Constants.LOG.info(String.format(Locale.ROOT,
                "[ARG] Development playback frame %d: delta %.4fs, elapsed %.4fs, %s.%s = %s", loggedFrames,
                deltaSeconds, CONTROLLER.elapsedSeconds(), ANIMATED_BONE, ANIMATED_CHANNEL_JSON, value));
    }

    private static void warnOnce(String key, String message) {
        synchronized (reported) {
            if (reported.add(key)) {
                Constants.LOG.warn("[ARG] {}", message);
            }
        }
    }
}
