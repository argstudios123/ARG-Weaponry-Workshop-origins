package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.render.WeaponDisplayCache;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.ChannelKind;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationController;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPoseBlend;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

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

    /**
     * [DrawHolster hook] How long the pose cross-fade lasts when an equip clip starts or ends.
     *
     * <p>One tenth of a second: long enough to hide a pose discontinuity, short enough that the equip clip is not visibly
     * "soft" at either end. The reference implementation's idle layer fades over 80 ms and its animation states declare
     * their own {@code fade_in}, so this sits in the same range.</p>
     */
    static final double EQUIP_BLEND_SECONDS = 0.1;

    /**
     * [DrawHolster hook] How long a finished equip clip may keep holding its last frame while the equip subsystem has not
     * reported completion yet.
     *
     * <p>The completion always arrives within a few ticks of the window, so this is a safety net rather than a timing
     * rule: it guarantees that a caller which somehow never completes the transition cannot leave the weapon stuck on a
     * finished clip. Server-side timing and authority are untouched.</p>
     */
    static final double MAX_EQUIP_HOLD_SECONDS = 2.0;

    /** [DrawHolster diagnostic] Root movement in one frame, in model units, that counts as a discontinuity. */
    private static final double POSE_JUMP_UNITS = 2.0;

    /** [DrawHolster diagnostic] Root rotation in one frame, in degrees, that counts as a discontinuity. */
    private static final double POSE_JUMP_DEGREES = 8.0;


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

    // [DrawHolster hook] The fourth playback owner: the draw/holster clip. It outranks fire, reload and inspect because
    // it belongs to the weapon's whole equip transition, not to an action the player performs while aiming it.
    private String equipAnimation;
    private boolean equipPlaying;
    private double equipPlaybackRate = 1.0;

    /** One-entry cache of the last frame sample, so the camera and the pass share one evaluation. */
    private WeaponPose sampledPose;
    private double sampledPoseTime;
    private double sampledPoseOffset;
    private boolean sampledPoseValid;
    private int playbackGeneration;
    private int sampledPoseGeneration;

    // [DrawHolster hook] The equip cross-fade: the pose that was on screen when the current equip clip change happened,
    // how long the fade has been running, and how long it lasts. All three are cleared as soon as it finishes.
    private WeaponPose blendFrom;
    private double blendElapsedSeconds;
    private double blendDurationSeconds;
    /** [DrawHolster hook] How long the finished equip clip has been holding its last frame. */
    private double heldEquipSeconds;

    /** The sub-tick offset of the last sampled frame, so a clip change can capture what the player actually saw. */
    private double lastSampleOffsetSeconds;

    /**
     * [DrawHolster hook] The exact pose the last rendered frame returned for the current owner.
     *
     * <p>This is the only pose that is guaranteed to have been on screen, which is what makes it usable as a cross-fade
     * source and as the discontinuity watchdog's reference. It is cleared by {@link #reset()} and by
     * {@link #activate(Owner, ContentPack, WeaponDefinition, int)}, so it can never describe a weapon the controller no
     * longer belongs to.</p>
     */
    private WeaponPose lastDisplayedPose;

    /** [DrawHolster hook] Whether a frame has been rendered for the current owner yet. */
    private boolean poseRendered;

    /**
     * [DrawHolster hook] The outgoing weapon's last displayed pose, kept across one owner change.
     *
     * <p>{@link WeaponViewControllers#bind} reuses one controller per hand, so a weapon switch reaches the incoming weapon
     * through {@link #activate(Owner, ContentPack, WeaponDefinition, int)}, which resets everything. The pose the outgoing
     * weapon was really drawn with is captured first and carried here, so the incoming draw fades in from what the player
     * was looking at instead of from an idle pose that was started in the same tick and never rendered.</p>
     *
     * <p>The carry is single-use and short-lived by construction: it is consumed by the next clip start, and it is dropped
     * as soon as a frame of the new owner has been drawn - at which point the new owner has a genuinely displayed pose of
     * its own.</p>
     */
    private WeaponPose carriedPose;

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
     * <p>[DrawHolster hook] The pose the outgoing weapon was last <em>drawn</em> with is captured before the reset and kept
     * as the cross-fade source for the incoming weapon's first clip. That capture has to happen here and not in the caller:
     * by the time the caller could sample the controller again, the reset has already replaced the clip, and a fresh sample
     * would be an idle pose of the new weapon that was never on screen. When no frame was ever rendered - the first weapon of
     * a session, a controller created mid-frame, or one that was just reset - nothing is carried, so the incoming clip starts
     * at its own first frame instead of fading from a pose the player has never seen.</p>
     *
     * @param newOwner   the owner this controller now belongs to
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     * @param ammoCount  the stack's current ammunition count, the idle selection input
     */
    public synchronized void activate(Owner newOwner, ContentPack pack, WeaponDefinition definition,
                                      int ammoCount) {
        WeaponPose outgoing = poseRendered ? lastDisplayedPose : null;
        reset();
        carriedPose = outgoing;
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
            // [DrawHolster hook] The equip clip is retimed like a reload/inspect clip, so it ends exactly when the
            // authoritative window does.
            double rate = equipPlaying ? equipPlaybackRate
                    : reloadPlaying ? reloadPlaybackRate : inspectPlaying ? inspectPlaybackRate : 1.0;
            animation.tick(Math.min(deltaSeconds, MAX_FRAME_DELTA_SECONDS) * rate);
        }
        finishExpiredClips(pack, definition, deltaSeconds);
        advanceBlend(deltaSeconds);
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
        lastSampleOffsetSeconds = offset;
        WeaponPose displayed = blendDisplayed(sampled, offset);
        sampledPose = displayed == null || displayed.isEmpty() ? null : displayed;
        sampledPoseTime = animation.elapsedSeconds();
        sampledPoseOffset = offset;
        sampledPoseGeneration = playbackGeneration;
        sampledPoseValid = true;
        if (sampledPose != null) {
            // [DrawHolster hook] This is now the pose the player is looking at: it is both the watchdog's reference and the
            // only legitimate source for a later cross-fade, so a pose carried from a previous owner is no longer needed.
            reportPoseJump(lastDisplayedPose, sampledPose);
            lastDisplayedPose = sampledPose;
            poseRendered = true;
            carriedPose = null;
        } else {
            // Nothing is drawn from a pose, so there is nothing that could be faded from later.
            lastDisplayedPose = null;
        }
        return sampledPose;
    }

    /**
     * [DrawHolster diagnostic] Reports a single frame that moved the weapon discontinuously.
     *
     * <p>This is the in-game evidence tool for the remaining snapping: it prints the two clips, their times, the root
     * movement and whether a cross-fade was running, so a report of "it still snaps" can be tied to the exact transition
     * instead of being guessed at. It is silent unless debug logging is enabled and only ever runs on a frame that
     * actually jumps, so it cannot flood the log.</p>
     *
     * @param previous the pose the previous frame returned, may be {@code null}
     * @param current  the pose this frame returns, may be {@code null}
     */
    private void reportPoseJump(WeaponPose previous, WeaponPose current) {
        if (previous == null || current == null || previous == current || !Constants.LOG.isDebugEnabled()) {
            return;
        }
        Vec3 previousPosition = rootChannel(previous, ChannelKind.POSITION);
        Vec3 currentPosition = rootChannel(current, ChannelKind.POSITION);
        double moved = Math.sqrt(Math.pow(currentPosition.x() - previousPosition.x(), 2.0)
                + Math.pow(currentPosition.y() - previousPosition.y(), 2.0)
                + Math.pow(currentPosition.z() - previousPosition.z(), 2.0));
        double turned = WeaponPoseBlend.angleDegrees(rootChannel(previous, ChannelKind.ROTATION),
                rootChannel(current, ChannelKind.ROTATION));
        if (moved < POSE_JUMP_UNITS && turned < POSE_JUMP_DEGREES) {
            return;
        }
        Constants.LOG.debug("[ARG] Pose jump in one frame: {} u / {} deg. '{}'@{}s -> '{}'@{}s, root {} -> {}, "
                        + "cross-fade active: {}",
                String.format("%.2f", moved), String.format("%.1f", turned), previous.animationName(),
                String.format("%.3f", previous.timeSeconds()), current.animationName(),
                String.format("%.3f", current.timeSeconds()), previousPosition, currentPosition,
                blendFrom != null && blendDurationSeconds > 0.0);
    }

    /**
     * [DrawHolster diagnostic] Records which pose a new equip clip is faded in from.
     *
     * <p>The transition this makes visible is the one that matters for the remaining snapping: whether the fade starts
     * from a pose this weapon really displayed, from the pose carried across a weapon switch, or from nothing at all.</p>
     *
     * @param clip     the clip that is about to start
     * @param outgoing the pose the fade will start from, may be {@code null}
     */
    private void reportBlendSource(String clip, WeaponPose outgoing) {
        String origin = carriedPose != null ? "the outgoing weapon's last displayed pose"
                : poseRendered ? "this weapon's last displayed pose" : "no cross-fade";
        if (outgoing == null || outgoing.isEmpty()) {
            Constants.LOG.debug("[ARG] Equip clip '{}' starts with no cross-fade (nothing was on screen)", clip);
            return;
        }
        Constants.LOG.debug("[ARG] Equip clip '{}' fades in from {} '{}'@{}s, root pos {} rot {}",
                clip, origin, outgoing.animationName(), String.format("%.3f", outgoing.timeSeconds()),
                rootChannel(outgoing, ChannelKind.POSITION), rootChannel(outgoing, ChannelKind.ROTATION));
    }

    /** @return one root channel of a pose, or the identity delta when the pose does not animate it. */
    private static Vec3 rootChannel(WeaponPose pose, ChannelKind kind) {
        SampledBone root = pose.bone("root");
        Vec3 value = root == null ? null : root.channel(kind);
        if (value != null) {
            return value;
        }
        return kind == ChannelKind.SCALE ? new Vec3(1.0F, 1.0F, 1.0F) : Vec3.ZERO;
    }

    // ------------------------------------------------------------------ equip cross-fade

    /**
     * [DrawHolster hook] Applies the running equip cross-fade to one sampled pose.
     *
     * <p>The outgoing pose is a snapshot taken when the clip changed, which is what the reference implementation does as
     * well: its sequence keeps the previous clip's time at the switch instant and fades that sample out, and its idle
     * layer fades a frozen phase out. Freezing is enough because the fade lasts about a tenth of a second, and it keeps
     * the controller on a single playback clock.</p>
     *
     * @param sampled       the pose the current clip evaluates to
     * @param offsetSeconds the sub-tick offset the pose was sampled at
     * @return the blended pose, or the sampled pose when no fade is running
     */
    private WeaponPose blendDisplayed(WeaponPose sampled, double offsetSeconds) {
        if (blendFrom == null || blendDurationSeconds <= 0.0) {
            return sampled;
        }
        WeaponPose blended = WeaponPoseBlend.blend(blendFrom, sampled, blendWeight(offsetSeconds));
        return blended.isEmpty() ? null : blended;
    }

    /**
     * @param offsetSeconds the sub-tick offset the frame is sampled at
     * @return the eased blend position in {@code [0, 1]} for that instant
     */
    private float blendWeight(double offsetSeconds) {
        if (blendFrom == null || blendDurationSeconds <= 0.0) {
            return 1.0F;
        }
        double elapsed = Math.max(0.0, blendElapsedSeconds + offsetSeconds);
        double position = Math.min(1.0, elapsed / blendDurationSeconds);
        // Smoothstep: the fade starts and ends with zero velocity, so it neither jerks nor stops abruptly.
        double eased = position * position * (3.0 - 2.0 * position);
        return (float) eased;
    }

    /**
     * [DrawHolster hook] Starts a cross-fade from one specific pose.
     *
     * <p>The caller captures the outgoing pose <em>before</em> it changes the clip, so the fade always starts from the
     * pose that was on screen. When a fade is already running, {@link #displayedPose()} returns the blended pose, which is
     * what makes an interruption mid-fade continue smoothly instead of snapping back to an older snapshot.</p>
     *
     * @param from            the pose to fade out, may be {@code null}
     * @param durationSeconds the length of the fade; {@code 0} or less disables it
     */
    private void beginBlend(WeaponPose from, double durationSeconds) {
        clearBlend();
        if (from == null || from.isEmpty() || !(durationSeconds > 0.0)) {
            return;
        }
        blendFrom = from;
        blendDurationSeconds = durationSeconds;
        blendElapsedSeconds = 0.0;
    }

    /**
     * @return the pose the last rendered frame showed, or {@code null} when nothing is animating
     */
    private WeaponPose displayedPose() {
        if (animation == null) {
            return null;
        }
        WeaponPose sampled = animation.poseAt(lastSampleOffsetSeconds);
        if (sampled.isEmpty()) {
            return null;
        }
        return blendFrom == null || blendDurationSeconds <= 0.0 ? sampled
                : WeaponPoseBlend.blend(blendFrom, sampled, blendWeight(lastSampleOffsetSeconds));
    }

    /**
     * [DrawHolster hook] Resolves the pose a new clip fades in from, in priority order.
     *
     * <ol>
     *     <li>the pose carried across an owner change - it is the outgoing weapon's last displayed pose, so a switch
     *     continues from what the player was looking at;</li>
     *     <li>otherwise the pose this owner last rendered, which covers interrupting a running clip;</li>
     *     <li>otherwise {@code null}, meaning nothing was on screen, so the clip starts at its own first frame.</li>
     * </ol>
     *
     * @return the pose to fade out, or {@code null} when there is nothing to fade from
     */
    private WeaponPose blendSource() {
        if (carriedPose != null) {
            return carriedPose;
        }
        return poseRendered ? displayedPose() : null;
    }

    /**
     * Advances the cross-fade by one client tick and drops it once it is over, so a finished fade costs nothing.
     *
     * @param deltaSeconds the wall-clock time the tick covered
     */
    private void advanceBlend(double deltaSeconds) {
        if (blendFrom == null) {
            return;
        }
        if (Double.isFinite(deltaSeconds) && deltaSeconds > 0.0) {
            blendElapsedSeconds += Math.min(deltaSeconds, MAX_FRAME_DELTA_SECONDS);
        }
        if (blendElapsedSeconds >= blendDurationSeconds) {
            clearBlend();
        }
        invalidateSampledPose();
    }

    /** Forgets any running cross-fade. */
    private void clearBlend() {
        blendFrom = null;
        blendElapsedSeconds = 0.0;
        blendDurationSeconds = 0.0;
        invalidateSampledPose();
    }

    /** Drops the one-entry sample cache after a blend or clip change. */
    private void invalidateSampledPose() {
        sampledPoseValid = false;
    }

    /**
     * Ends any clip whose animation reached its end, handing playback back to the idle selection.
     *
     * <p>Called from the tick path only, because completion is a playback event, never a sampling one: a frame that
     * happens to sample at the end of a clip must not end the action.</p>
     *
     * <p>[DrawHolster hook] The equip clip is the exception: its terminal frame is held until the equip subsystem reports
     * completion, so the weapon keeps the pose the animation ended on instead of dropping to idle before the handoff.</p>
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     * @param deltaSeconds the wall-clock time the tick covered
     */
    private void finishExpiredClips(ContentPack pack, WeaponDefinition definition, double deltaSeconds) {
        if (!animation.isFinished()) {
            return;
        }
        // [DrawHolster hook] The equip clip is the highest-priority owner, so it is also the first one to release playback.
        if (equipPlaying) {
            if (reloadPlaying || inspectPlaying || firePlaying) {
                // Another action took playback over while the equip clip was still marked as playing, so the clip that
                // would be held is not playing any more: release the equip state instead of holding a pose that is gone.
                equipPlaying = false;
                equipAnimation = null;
                equipPlaybackRate = 1.0;
                heldEquipSeconds = 0.0;
                return;
            }
            // [DrawHolster hook] P2: hold the last frame of the equip clip. Resuming idle here is what used to make the
            // raised weapon flash for the remaining ticks of the authoritative window, because clip time advances with
            // the wall clock while the window is counted in ticks: below twenty ticks per second the clip expired early
            // and idle showed through until the handoff. The clip is ended by the equip completion instead
            // (finishEquip), which starts the cross-fade back into idle; the transition itself is unchanged.
            heldEquipSeconds += Double.isFinite(deltaSeconds) && deltaSeconds > 0.0
                    ? Math.min(deltaSeconds, MAX_FRAME_DELTA_SECONDS) : 0.0;
            if (heldEquipSeconds < MAX_EQUIP_HOLD_SECONDS) {
                return;
            }
            // The completion never arrived. A finished clip must not keep the weapon locked, so end it the same way a
            // completion would and let the fade take over.
            Constants.LOG.debug("[ARG] Equip clip '{}' held its last frame for {}s without a completion; returning to "
                    + "idle", equipAnimation, heldEquipSeconds);
            endEquipClip(pack, definition);
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
     * [DrawHolster hook] Starts a draw or holster clip.
     *
     * <p>Called by the client's equip state machine when it starts a window, never from the render path, so a frame cannot
     * start or restart an equip clip. The clip outranks every other owner: a firing, reload or inspection clip is dropped
     * rather than left to fight it, which is what makes the weapon visibly leave and re-enter the player's hands. The clip
     * is retimed to the authored window with the same {@link #playbackRateFor(double, double)} rule the reload and
     * inspection clips use, so the animation ends exactly when the authoritative timing does.</p>
     *
     * @param pack            the content pack that owns the weapon
     * @param definition      the weapon definition
     * @param name            the declared draw or holster clip name
     * @param durationSeconds the authored window, in seconds
     * @return {@code true} when the clip started
     */
    public synchronized boolean playEquip(ContentPack pack, WeaponDefinition definition, String name,
                                          double durationSeconds) {
        if (owner == null || definition == null || name == null || name.isBlank()) {
            return false;
        }
        if (equipPlaying && name.equals(equipAnimation)) {
            return true;
        }
        // [DrawHolster hook] Capture what the player is looking at before the clip changes: an interrupted raise continues
        // from the displayed pose, and a weapon switch continues from the pose the outgoing weapon was drawn with.
        WeaponPose outgoing = blendSource();
        if (Constants.LOG.isDebugEnabled()) {
            reportBlendSource(name, outgoing);
        }
        if (!startDeclaredAnimation(pack, definition, name)) {
            return false;
        }
        equipAnimation = name;
        equipPlaying = true;
        equipPlaybackRate = playbackRateFor(animation.durationSeconds(),
                Double.isFinite(durationSeconds) && durationSeconds > 0.0 ? durationSeconds : 0.0);
        heldEquipSeconds = 0.0;
        firePlaying = false;
        fireAnimation = null;
        reloadPlaying = false;
        reloadAnimation = null;
        reloadDurationSeconds = 0.0;
        reloadPlaybackRate = 1.0;
        inspectPlaying = false;
        inspectAnimation = null;
        inspectPlaybackRate = 1.0;
        // [DrawHolster hook] The carried pose is consumed by this clip and by nothing else, so a later change inside the same
        // owner fades from what this clip is drawing instead of from the previous weapon.
        carriedPose = null;
        beginBlend(outgoing, EQUIP_BLEND_SECONDS);
        Constants.LOG.debug("[ARG] Equip clip '{}' started: {}s long, {}s configured, playback rate {}", name,
                animation.durationSeconds(), durationSeconds, equipPlaybackRate);
        return true;
    }

    /**
     * [DrawHolster hook] Ends a draw or holster clip and hands playback back to the idle selection.
     *
     * <p>Called when its window ends, when it is interrupted, and when the hand no longer holds the weapon. Safe to call
     * when nothing is equipping.</p>
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     */
    public synchronized void finishEquip(ContentPack pack, WeaponDefinition definition) {
        finishEquip(pack, definition, null);
    }

    /**
     * [DrawHolster hook] Ends the draw or holster clip this caller started, and nothing else.
     *
     * <p>A completion may arrive after the hand already holds a different weapon, or after a newer transition replaced the
     * clip. Ending "whatever is playing" in that situation would cut an animation that belongs to another weapon, so the
     * expected clip name is checked first and a mismatch is ignored.</p>
     *
     * @param pack         the content pack that owns the weapon
     * @param definition   the weapon definition
     * @param expectedClip the clip the caller started, or {@code null} to end whatever is equipping
     * @return {@code true} when this call ended the clip
     */
    public synchronized boolean finishEquip(ContentPack pack, WeaponDefinition definition, String expectedClip) {
        if (!equipPlaying) {
            return false;
        }
        if (expectedClip != null && !expectedClip.equals(equipAnimation)) {
            return false;
        }
        endEquipClip(pack, definition);
        return true;
    }

    /**
     * [DrawHolster hook] Ends the equip clip and fades its last frame back into the idle selection.
     *
     * <p>The pose is captured before the clip is replaced, so the fade starts from the terminal frame the player is
     * looking at. This is the only place that ends an equip clip, so a completed transition and the safety timeout in
     * {@link #finishExpiredClips} cannot diverge.</p>
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     */
    private void endEquipClip(ContentPack pack, WeaponDefinition definition) {
        WeaponPose outgoing = displayedPose();
        equipPlaying = false;
        equipAnimation = null;
        equipPlaybackRate = 1.0;
        heldEquipSeconds = 0.0;
        idleAnimation = definition == null ? null : selectIdle(definition, lastSelectionAmmo);
        resumeIdle(pack, definition);
        beginBlend(outgoing, EQUIP_BLEND_SECONDS);
    }

    /** [DrawHolster hook] @return {@code true} while a draw or holster clip owns playback. */
    public synchronized boolean isEquipPlaying() {
        return equipPlaying;
    }

    /**
     * @return {@code true} when the equip clip has reached its end. The controller keeps holding the clip's terminal frame
     *         until the owner ends it, which is what makes this the completion signal a handoff may act on without a frame
     *         of idle showing through first.
     */
    public synchronized boolean isEquipClipFinished() {
        return equipPlaying && animation.isFinished();
    }

    /**
     * @return the authored length of the equip clip currently playing, in seconds, or {@code 0} when none is playing. The
     *         handoff subsystem uses this length as its window, so the clip and the window cannot drift apart.
     */
    public synchronized double equipClipDurationSeconds() {
        return equipPlaying ? animation.durationSeconds() : 0.0;
    }

    /** [DrawHolster hook] @return the draw or holster clip currently playing, or {@code null}. */
    public synchronized String equipAnimation() {
        return equipAnimation;
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
        // [DrawHolster hook] The equip clip owns playback like a fire/reload/inspect clip does: remember the new idle
        // selection without interrupting the draw or holster.
        if (firePlaying || reloadPlaying || inspectPlaying || equipPlaying) {
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
        advanceBlend(deltaSeconds);
        return pose();
    }

    /**
     * @return the current immutable pose, or {@code null} when the controller holds no animation or the
     *         animation animates nothing (so the renderer uses the authored static transform)
     */
    public synchronized WeaponPose pose() {
        WeaponPose displayed = blendDisplayed(animation.currentPose(), 0.0);
        return displayed == null || displayed.isEmpty() ? null : displayed;
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
        // [DrawHolster hook]
        equipAnimation = null;
        equipPlaying = false;
        equipPlaybackRate = 1.0;
        heldEquipSeconds = 0.0;
        lastSelectionAmmo = Integer.MIN_VALUE;
        lastFrameNanos = 0L;
        lastSampleOffsetSeconds = 0.0;
        // [DrawHolster hook] A fade belongs to the weapon that started it, so an ownership change drops it as well.
        clearBlend();
        // [DrawHolster hook] Nothing displayed by this controller may survive a reset: a pose from an earlier weapon, or from
        // before a world change, a resource reload or a release, must never become a cross-fade source.
        lastDisplayedPose = null;
        poseRendered = false;
        carriedPose = null;
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
