package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.render.WeaponDisplayCache;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationController;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;

import java.util.Objects;

/**
 * Owns the animation playback of one held weapon in one hand.
 *
 * <p>This is the gameplay-facing counterpart of {@code AnimationTester}: it reuses the existing
 * {@link WeaponAnimationController}, the existing cache, sampler and pose types, and hands immutable
 * {@link WeaponPose} values to the renderer. It is not a second animation engine and it changes no
 * interpolation, timing or transform policy.</p>
 *
 * <h2>Ownership vs animation-selection inputs</h2>
 * <p>Ownership is deliberately small: an {@link Owner} is the inventory slot the weapon occupies plus the
 * item's registry id. Character identity is never used, so a copied or re-created
 * {@code WeaponDefinition} object cannot fake or lose ownership.</p>
 * <p>The stack's {@code WeaponState} is <em>not</em> part of ownership. Ammunition is a mutable gameplay
 * value that changes while the same weapon stays in the same hand, so treating it as identity would
 * restart the idle animation on every shot. It is passed in as an animation-selection input instead, so a
 * changed count re-evaluates the declared idle selection without resetting playback. The fire mode is not
 * read here at all, because no idle selection consumes it.</p>
 * <p><strong>Known limitation:</strong> two different stacks of the same item in the same slot cannot be
 * told apart without a persistent stack UUID, which this project deliberately does not have. Replacing one
 * with the other therefore keeps the current playback; a different item in the slot, a different slot or a
 * different hand is always detected.</p>
 *
 * <h2>State ownership</h2>
 * <p>Everything mutable lives here: the controller, the animation name, the selection input snapshot and
 * the frame clock. Nothing is written to {@code WeaponState} or {@code WeaponItem}; ammunition and fire
 * mode remain pure data.</p>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *     <li>{@link #activate} resets first, then selects and starts the idle animation for the new owner,</li>
 *     <li>{@link #tick(ContentPack, WeaponDefinition, int, double)} advances playback once per client tick and
 *     {@link #poseFor(ContentPack, WeaponDefinition, int, float)} samples it for a rendered frame, so the timeline is
 *     independent of how often, or whether, the weapon is drawn,</li>
 *     <li>{@link #refreshIdle} only re-evaluates the selection when an input it consumes changed,</li>
 *     <li>{@link #playReload} starts a reload clip the server accepted, and {@link #finishReload} ends it,</li>
 *     <li>{@link #reset()} stops playback and forgets everything, which is what ownership changes and
 *     resource reloads use,</li>
 *     <li>{@link #pose()} only reads and never advances.</li>
 * </ul>
 *
 * <h2>Playback precedence</h2>
 * <p>A reload clip outranks a firing clip, and a firing clip outranks the idle selection. Only one of them ever
 * owns playback: a reload drops a running shot, a shot is refused while a reload owns the weapon (the server
 * refuses it as well), and the idle selection keeps being <em>recomputed</em> underneath them, so the return to
 * idle uses the magazine the action ended with.</p>
 */
public final class WeaponViewController {

    /** Longest frame delta fed to the controller, mirroring the Animation Tester's stall guard. */
    /** One client tick in seconds, used to turn a partial tick into a sampling offset. */
    public static final double SECONDS_PER_TICK = 0.05;

    static final double MAX_FRAME_DELTA_SECONDS = 0.25;


    /** Slowest a reload clip may be played to stretch it onto the configured duration. */
    static final double MIN_PLAYBACK_RATE = 0.25;

    /** Fastest a reload clip may be played to compress it onto the configured duration. */
    static final double MAX_PLAYBACK_RATE = 4.0;

    private final WeaponAnimationController animation = new WeaponAnimationController();

    private Owner owner;
    private String idleAnimation;
    private String fireAnimation;
    private boolean firePlaying;
    private String reloadAnimation;
    private boolean reloadPlaying;
    private String inspectAnimation;

    /** One-entry cache of the last frame sample, so the camera and the pass share one evaluation. */
    private WeaponPose sampledPose;
    private double sampledPoseTime;
    private double sampledPoseOffset;
    private boolean sampledPoseValid;
    private int playbackGeneration;
    private int sampledPoseGeneration;
    private boolean inspectPlaying;
    private double inspectPlaybackRate = 1.0;
    private double reloadDurationSeconds;
    private double reloadPlaybackRate = 1.0;
    private int lastSelectionAmmo = Integer.MIN_VALUE;
    private long lastFrameNanos;

    public WeaponViewController() {
    }

    /**
     * The weapon a controller currently belongs to: where it is and what it is.
     *
     * <p>Mutable weapon state is deliberately absent. Ammunition changes while the weapon stays in the
     * same hand, so it is an animation-selection input rather than identity, and the fire mode is not
     * consumed by any selection.</p>
     *
     * @param slot   the inventory slot the weapon occupies; the hotbar slot for the main hand, the
     *               off-hand slot for the off-hand
     * @param itemId the item's registry id
     */
    public record Owner(int slot, String itemId) {

        public Owner {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /**
     * @param candidate the ownership to test
     * @return {@code true} when this controller already belongs to that owner
     */
    public synchronized boolean owns(Owner candidate) {
        return owner != null && owner.equals(candidate);
    }

    /**
     * Binds this controller to a new owner and starts its idle animation.
     *
     * <p>Any previous playback, pose and resolved animation name are dropped first, so a controller can
     * never keep animating the previous weapon. This is the only place that resets playback for an
     * ownership change; ordinary ammunition changes go through {@link #refreshIdle} instead.</p>
     *
     * @param newOwner   the owner this controller now belongs to
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     * @param ammoCount  the stack's current ammunition count, the idle selection input
     */
    public synchronized void activate(Owner newOwner, ContentPack pack, WeaponDefinition definition,
                                      int ammoCount) {
        reset();
        this.owner = newOwner;
        this.lastSelectionAmmo = ammoCount;
        this.idleAnimation = selectAndStartIdle(pack, definition, ammoCount);
    }

    /**
     * Advances playback by one explicit time step - the client-tick entry point.
     *
     * <p>Milestone 10S moved the timeline here, and the reason is the confirmed bug this milestone fixes: playback used
     * to advance only from the first-person render call, so pressing F5 froze a reload or an inspection clip until the
     * player came back, and returning sampled a stale time. A gameplay action's clock must not be owned by one camera
     * perspective or by how often that perspective draws.</p>
     *
     * <p>The controller is still the only mutable playback owner, and it is advanced exactly once per client tick per
     * hand. Rendering samples {@link #poseFor(ContentPack, WeaponDefinition, int, float)} instead, so a frame that draws twice (or not at all) cannot advance
     * or restart anything.</p>
     *
     * @param pack          the content pack that owns the weapon
     * @param definition    the weapon definition
     * @param ammoCount     the stack's current ammunition count, the idle selection input
     * @param deltaSeconds  elapsed time since the previous tick, in seconds
     * @return the pose at the new time, or {@code null} when nothing is playing
     */
    public synchronized WeaponPose tick(ContentPack pack, WeaponDefinition definition, int ammoCount,
                                        double deltaSeconds) {
        refreshIdle(pack, definition, ammoCount);
        if (Double.isFinite(deltaSeconds) && deltaSeconds > 0.0) {
            double rate = reloadPlaying ? reloadPlaybackRate : inspectPlaying ? inspectPlaybackRate : 1.0;
            animation.tick(Math.min(deltaSeconds, MAX_FRAME_DELTA_SECONDS) * rate);
        }
        finishExpiredClips(pack, definition);
        return pose();
    }

    /**
     * Samples the current animation without advancing it, optionally at a sub-tick offset.
     *
     * <p>This is the only method the renderer calls. It also refreshes the idle selection, so an ammunition change is
     * visible on the next frame without giving the renderer any influence over playback time.</p>
     *
     * @param pack        the content pack that owns the weapon
     * @param definition  the weapon definition
     * @param ammoCount   the stack's current ammunition count
     * @param partialTick the fraction of a client tick elapsed, used for interpolation; may be {@code 0}
     * @return the sampled pose, or {@code null} when nothing is playing
     */
    public synchronized WeaponPose poseFor(ContentPack pack, WeaponDefinition definition, int ammoCount,
                                           float partialTick) {
        double offset = Double.isFinite(partialTick) ? partialTick * SECONDS_PER_TICK : 0.0;
        // One entry cache: the camera hook and the weapon pass ask for the same frame sample within a few microseconds, and a
        // repeated sample must return the same pose. The key includes the clock, so a new tick always re-samples.
        if (sampledPoseValid && sampledPoseGeneration == playbackGeneration
                && Double.compare(sampledPoseTime, animation.elapsedSeconds()) == 0
                && Double.compare(sampledPoseOffset, offset) == 0) {
            return sampledPose;
        }
        refreshIdle(pack, definition, ammoCount);
        WeaponPose sampled = animation.poseAt(offset);
        sampledPose = sampled.isEmpty() ? null : sampled;
        sampledPoseTime = animation.elapsedSeconds();
        sampledPoseOffset = offset;
        sampledPoseGeneration = playbackGeneration;
        sampledPoseValid = true;
        return sampledPose;
    }

    /**
     * Ends any clip whose animation reached its end, handing playback back to the idle selection.
     *
     * <p>Called from the tick path only, because completion is a playback event, never a sampling one: a frame that
     * happens to sample at the end of a clip must not end the action.</p>
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     */
    private void finishExpiredClips(ContentPack pack, WeaponDefinition definition) {
        if (!animation.isFinished()) {
            return;
        }
        if (reloadPlaying) {
            // The clip's playback rate is derived from the server-side duration, so "the clip finished" and "the
            // duration elapsed" are the same instant: idle never shows through before the server completes the reload.
            reloadPlaying = false;
            reloadAnimation = null;
            reloadDurationSeconds = 0.0;
            reloadPlaybackRate = 1.0;
            resumeIdle(pack, definition);
            return;
        }
        if (inspectPlaying) {
            inspectPlaying = false;
            inspectAnimation = null;
            inspectPlaybackRate = 1.0;
            resumeIdle(pack, definition);
            return;
        }
        if (firePlaying) {
            firePlaying = false;
            fireAnimation = null;
            resumeIdle(pack, definition);
        }
    }

    /**
     * Starts the declared firing animation for one accepted shot.
     *
     * <p>Called when the server confirms a shot, never from the input path, so the client cannot animate a shot
     * the server refused. The clip comes from the content pack's {@code fire_anims}; the selection uses the same
     * first-match rules and the same ammunition input as {@code idle_anims}, which is why the shipped weapon can
     * declare a different clip for the last round.</p>
     *
     * <p>While a firing animation plays it owns playback: an ammunition change still updates the idle
     * <em>selection</em>, but {@link #refreshIdle} does not interrupt the clip. When the clip finishes,
     * playback returns to that selection from time zero.</p>
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     * @param ammoCount  the stack's ammunition count after the shot, the selection input
     * @return {@code true} when a firing animation started
     */
    public synchronized boolean playFire(ContentPack pack, WeaponDefinition definition, int ammoCount) {
        if (owner == null || definition == null) {
            return false;
        }
        if (reloadPlaying) {
            // A reload owns the weapon; the server refuses shots while it runs, so nothing may animate one.
            return false;
        }
        String selected = selectFire(definition, ammoCount);
        if (selected == null) {
            return false;
        }
        if (!startDeclaredAnimation(pack, definition, selected)) {
            return false;
        }
        // Fire outranks inspection: the player just shot, so the inspection clip is dropped rather than left to fight
        // the firing clip. The server ends the inspection session on the same shot.
        inspectPlaying = false;
        inspectAnimation = null;
        inspectPlaybackRate = 1.0;
        fireAnimation = selected;
        firePlaying = true;
        return true;
    }

    /**
     * Starts the reload animation the server accepted.
     *
     * <p>Called from the networking layer when the server confirms a reload, never from the input path, so a
     * rejected request produces no animation. The clip name comes from the content pack's
     * {@code reload_sequences}, chosen by the server for the magazine state at reload start; the client never
     * decides which clip applies.</p>
     *
     * <p>A reload clip outranks a firing clip: a shot that was still animating is dropped, because the server
     * refuses shots while the reload runs. Asking for the <em>same</em> clip again is a no-op, so a repeated
     * acceptance cannot restart the animation mid-way.</p>
     *
     * @param pack            the content pack that owns the weapon
     * @param definition      the weapon definition
     * @param name            the declared reload animation name
     * @param durationSeconds the server-side duration; the clip is played at the rate that makes it last exactly
     *                        this long (see {@link #playbackRateFor(double, double)})
     * @return {@code true} when a reload animation is playing afterwards
     */
    public synchronized boolean playReload(ContentPack pack, WeaponDefinition definition, String name,
                                           double durationSeconds) {
        if (owner == null || definition == null || name == null || name.isBlank()) {
            return false;
        }
        if (reloadPlaying && name.equals(reloadAnimation)) {
            return true;
        }
        if (!startDeclaredAnimation(pack, definition, name)) {
            return false;
        }
        reloadAnimation = name;
        reloadPlaying = true;
        reloadDurationSeconds = Double.isFinite(durationSeconds) && durationSeconds > 0.0 ? durationSeconds : 0.0;
        reloadPlaybackRate = playbackRateFor(animation.durationSeconds(), reloadDurationSeconds);
        firePlaying = false;
        fireAnimation = null;
        inspectPlaying = false;
        inspectAnimation = null;
        inspectPlaybackRate = 1.0;
        Constants.LOG.debug("[ARG] Reload clip '{}' started: {}s long, {}s configured, playback rate {}",
                name, animation.durationSeconds(), reloadDurationSeconds, reloadPlaybackRate);
        return true;
    }

    /**
     * Derives the playback rate that makes a clip of {@code nativeSeconds} last exactly {@code configuredSeconds}.
     *
     * <p>The content pack's reload sequence duration is the authoritative timing - the server completes the reload
     * after it - while the clip has its own authored length. The two are allowed to differ (the shipped M100 DMR
     * declares 3.0 s for a 3.66 s empty-reload clip and 3.2 s for a 2.9 s standard clip), so the clip is played
     * faster or slower instead of being clipped or leaving an idle gap:</p>
     * <pre>
     *   rate = nativeSeconds / configuredSeconds
     * </pre>
     * <p>That keeps the whole authored animation, ends it exactly when the server finishes, and never changes a
     * single keyframe. The rate is clamped to {@code [0.25, 4]} so a nonsensical asset pairing cannot make an
     * animation flash past or crawl.</p>
     *
     * @param nativeSeconds     the clip's own {@code animation_length}, in seconds
     * @param configuredSeconds the authored reload duration, in seconds; non-positive means "not configured"
     * @return the playback rate multiplier, {@code 1.0} when either value is unusable
     */
    static double playbackRateFor(double nativeSeconds, double configuredSeconds) {
        if (!Double.isFinite(nativeSeconds) || nativeSeconds <= 0.0
                || !Double.isFinite(configuredSeconds) || configuredSeconds <= 0.0) {
            return 1.0;
        }
        double rate = nativeSeconds / configuredSeconds;
        return Math.max(MIN_PLAYBACK_RATE, Math.min(MAX_PLAYBACK_RATE, rate));
    }

    /** @return the playback rate of the reload clip currently playing, {@code 1.0} when none is. */
    public synchronized double reloadPlaybackRate() {
        return reloadPlaybackRate;
    }

    /**
     * Starts the inspection clip the server accepted.
     *
     * <p>Inspection is the third playback owner, and its priority is defined explicitly:</p>
     * <pre>
     *   reload &gt; fire &gt; inspect &gt; idle
     * </pre>
     * <p>A reload always wins (it owns the weapon on the server too). A shot cancels an inspection, because firing is the
     * action the player just took and the server ends the inspection session when it accepts that shot. Idle never
     * interrupts an inspection. Asking for the <em>same</em> clip again is a no-op, so a repeated acceptance cannot
     * restart the animation mid-way; asking for a different inspection clip replaces it, which is what switching to an
     * empty magazine does.</p>
     *
     * <p>Like the reload clip, the inspection clip is played at the rate that makes it last exactly the server-side
     * duration, so the animation ends when the authoritative session does without changing a keyframe.</p>
     *
     * @param pack            the content pack that owns the weapon
     * @param definition      the weapon definition
     * @param name            the declared inspection animation name
     * @param durationSeconds the server-side duration in seconds
     * @return {@code true} when an inspection animation is playing afterwards
     */
    public synchronized boolean playInspect(ContentPack pack, WeaponDefinition definition, String name,
                                            double durationSeconds) {
        if (owner == null || definition == null || name == null || name.isBlank()) {
            return false;
        }
        if (reloadPlaying) {
            // A reload owns the weapon for its whole duration; the server refuses inspections while it runs.
            return false;
        }
        if (inspectPlaying && name.equals(inspectAnimation)) {
            return true;
        }
        if (!startDeclaredAnimation(pack, definition, name)) {
            return false;
        }
        inspectAnimation = name;
        inspectPlaying = true;
        inspectPlaybackRate = playbackRateFor(animation.durationSeconds(),
                Double.isFinite(durationSeconds) && durationSeconds > 0.0 ? durationSeconds : 0.0);
        firePlaying = false;
        fireAnimation = null;
        Constants.LOG.debug("[ARG] Inspection clip '{}' started: {}s long, {}s configured, playback rate {}", name,
                animation.durationSeconds(), durationSeconds, inspectPlaybackRate);
        return true;
    }

    /**
     * Ends an inspection and hands playback back to the idle selection.
     *
     * <p>Used when the server cancels the session (a shot, a reload, a weapon change) and is safe to call when nothing is
     * inspecting.</p>
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     */
    public synchronized void finishInspect(ContentPack pack, WeaponDefinition definition) {
        if (!inspectPlaying) {
            return;
        }
        inspectPlaying = false;
        inspectAnimation = null;
        inspectPlaybackRate = 1.0;
        idleAnimation = definition == null ? null : selectIdle(definition, lastSelectionAmmo);
        resumeIdle(pack, definition);
    }

    /** @return {@code true} while an inspection clip owns playback. */
    public synchronized boolean isInspectPlaying() {
        return inspectPlaying;
    }

    /** @return the inspection clip currently playing, or {@code null}. */
    public synchronized String inspectAnimation() {
        return inspectAnimation;
    }

    /**
     * Ends the reload animation and hands playback back to the idle selection for the current magazine.
     *
     * <p>Called when the server reports completion or cancellation. Safe to call when no reload is playing,
     * so an unexpected payload cannot break the controller.</p>
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     */
    public synchronized void finishReload(ContentPack pack, WeaponDefinition definition) {
        if (!reloadPlaying) {
            return;
        }
        reloadPlaying = false;
        reloadAnimation = null;
        reloadDurationSeconds = 0.0;
        reloadPlaybackRate = 1.0;
        idleAnimation = definition == null ? null : selectIdle(definition, lastSelectionAmmo);
        resumeIdle(pack, definition);
    }

    /** @return {@code true} while a reload animation owns playback. */
    public synchronized boolean isReloadPlaying() {
        return reloadPlaying;
    }

    /** @return the reload animation currently playing, or {@code null} when no reload is animating. */
    public synchronized String reloadAnimation() {
        return reloadAnimation;
    }

    /**
     * Re-evaluates the declared idle selection when one of its inputs changed.
     *
     * <p>Only the ammunition count feeds the idle conditions today, so this returns immediately unless
     * that count differs from the one the current animation was chosen with. That is what keeps an
     * ammunition change from restarting the idle animation, and why a fire-mode change — which no
     * selection consumes, and which is never passed here — cannot reset it.</p>
     *
     * <p>When the selection really changes, the newly selected animation starts from time zero, which is
     * the correct transition between two different clips. When it does not change, the running playback,
     * including its current time, is left untouched.</p>
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     * @param ammoCount  the stack's current ammunition count
     */
    public synchronized void refreshIdle(ContentPack pack, WeaponDefinition definition, int ammoCount) {
        if (owner == null || ammoCount == lastSelectionAmmo) {
            return;
        }
        lastSelectionAmmo = ammoCount;

        String selected = selectIdle(definition, ammoCount);
        if (firePlaying || reloadPlaying || inspectPlaying) {
            // A firing or reloading clip owns playback: remember what the idle selection became, so the return to
            // idle afterwards uses the new choice, but never interrupt the action itself.
            idleAnimation = selected;
            return;
        }
        if (selected != null && selected.equals(idleAnimation)) {
            // Same clip: keep playing it from where it is instead of restarting on an ammunition change.
            return;
        }
        if (selected == null) {
            animation.stop();
            playbackGeneration++;
            idleAnimation = null;
            return;
        }
        idleAnimation = startDeclaredAnimation(pack, definition, selected) ? selected : null;
    }

    /**
     * Returns playback to the idle selection after a firing clip finished.
     *
     * <p>Uses the selection recorded while the shot was playing, which {@link #refreshIdle} kept up to date, so
     * the weapon goes back to the idle clip that matches the magazine <em>after</em> the shot.</p>
     */
    private void resumeIdle(ContentPack pack, WeaponDefinition definition) {
        String selected = idleAnimation != null ? idleAnimation : selectIdle(definition, lastSelectionAmmo);
        idleAnimation = selected;
        if (selected == null) {
            animation.stop();
            playbackGeneration++;
            return;
        }
        startDeclaredAnimation(pack, definition, selected);
    }

    /**
     * Advances playback by an explicit delta instead of the wall clock.
     *
     * <p>Used by headless verification and available for deterministic playback, mirroring the explicit
     * delta the animation controller itself consumes.</p>
     *
     * @param deltaSeconds elapsed time in seconds; non-finite values are ignored and long stalls are capped
     * @return the current pose, or {@code null} when nothing is playing
     */
    public synchronized WeaponPose advance(double deltaSeconds) {
        if (Double.isFinite(deltaSeconds)) {
            animation.tick(Math.min(deltaSeconds, MAX_FRAME_DELTA_SECONDS));
        }
        return pose();
    }

    /**
     * @return the current immutable pose, or {@code null} when the controller holds no animation or the
     *         animation animates nothing (so the renderer uses the authored static transform)
     */
    public synchronized WeaponPose pose() {
        WeaponPose current = animation.currentPose();
        return current.isEmpty() ? null : current;
    }

    /** @return the idle animation currently selected, or {@code null} when no declared entry applies. */
    public synchronized String idleAnimation() {
        return idleAnimation;
    }

    /**
     * @return the playback time of the current animation in seconds, {@code 0} when none is playing; used by
     *         diagnostics and headless verification to prove that ownership changes restart playback
     */
    public synchronized double elapsedSeconds() {
        return animation.elapsedSeconds();
    }

    /** Stops playback and forgets the owner, the animations, the selection input and the frame clock. */
    public synchronized void reset() {
        animation.stop();
        owner = null;
        idleAnimation = null;
        fireAnimation = null;
        firePlaying = false;
        reloadAnimation = null;
        reloadPlaying = false;
        reloadDurationSeconds = 0.0;
        inspectAnimation = null;
        inspectPlaying = false;
        inspectPlaybackRate = 1.0;
        lastSelectionAmmo = Integer.MIN_VALUE;
        lastFrameNanos = 0L;
    }

    /** @return the firing animation currently playing, or {@code null} when idle owns playback. */
    public synchronized String fireAnimation() {
        return fireAnimation;
    }

    /** @return {@code true} while a firing animation owns playback. */
    public synchronized boolean isFirePlaying() {
        return firePlaying;
    }

    /**
     * Selects the declared idle animation for this ammunition count and starts it.
     *
     * @return the started animation name, or {@code null} when none applies or cannot be resolved
     */
    private String selectAndStartIdle(ContentPack pack, WeaponDefinition definition, int ammoCount) {
        String selected = selectIdle(definition, ammoCount);
        if (selected == null || !startDeclaredAnimation(pack, definition, selected)) {
            return null;
        }
        return selected;
    }

    /**
     * @param definition the weapon definition
     * @param ammoCount  the current ammunition count
     * @return the animation the declaration selects for that count, or {@code null}
     */
    private String selectIdle(WeaponDefinition definition, int ammoCount) {
        IdleAnimationSelector.Selection selection =
                IdleAnimationSelector.select(definition.idleAnimations(), ammoCount);
        if (selection.hasUnsupportedConditions()) {
            Constants.LOG.debug("[ARG] Idle animation conditions that cannot be evaluated for weapon '{}': {}",
                    definition.name(), selection.unsupportedConditions());
        }
        return selection.animation().orElse(null);
    }

    /**
     * Selects the declared firing animation for this ammunition count.
     *
     * <p>{@code fire_anims} uses the same {@code AnimationSelection} shape and the same condition vocabulary as
     * {@code idle_anims}, so the existing selector is reused instead of a second, subtly different evaluator.
     * The ammunition count is the one <em>after</em> the shot, which is what lets a weapon declare a distinct
     * "last round" clip.</p>
     *
     * @param definition the weapon definition
     * @param ammoCount  the ammunition count after the shot
     * @return the declared firing animation, or {@code null} when the weapon declares none that applies
     */
    private String selectFire(WeaponDefinition definition, int ammoCount) {
        IdleAnimationSelector.Selection selection =
                IdleAnimationSelector.select(definition.fireAnimations(), ammoCount);
        if (selection.hasUnsupportedConditions()) {
            Constants.LOG.debug("[ARG] Firing animation conditions that cannot be evaluated for weapon '{}': {}",
                    definition.name(), selection.unsupportedConditions());
        }
        return selection.animation().orElse(null);
    }

    /**
     * Starts one declared animation through the existing cache and controller.
     *
     * <p>The name always comes from the content pack ({@code idle_anims} or {@code fire_anims}); nothing is
     * hard-coded and no fallback name is invented. No loop override is passed, so the animation's own
     * {@code loop} setting decides: the shipped idle clips declare {@code "loop": true} and therefore loop,
     * while the firing clips declare no loop and therefore finish — which is what returns playback to idle.</p>
     *
     * @return {@code true} when playback started
     */
    private boolean startDeclaredAnimation(ContentPack pack, WeaponDefinition definition, String name) {
        WeaponDisplay display = WeaponDisplayCache.instance().get(pack, definition).orElse(null);
        if (display == null || display.animations() == null) {
            Constants.LOG.debug("[ARG] Weapon '{}' declares animation '{}' but its display configuration "
                    + "names no animation resource", definition.name(), name);
            return false;
        }

        playbackGeneration++;
        if (!animation.play(pack, display.animations(), name, null)) {
            // The cache already reported the missing document or animation name once.
            return false;
        }
        Constants.LOG.debug("[ARG] Weapon '{}' started animation '{}' from {}", definition.name(), name,
                display.animations());
        return true;
    }
}
