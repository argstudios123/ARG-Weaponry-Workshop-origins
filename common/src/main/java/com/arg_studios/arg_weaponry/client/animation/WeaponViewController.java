package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.render.WeaponDisplayCache;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.animation.AnimationFile;
import com.arg_studios.arg_weaponry.weapon.animation.AimAnimationLayer;
import com.arg_studios.arg_weaponry.weapon.animation.ChannelKind;
import com.arg_studios.arg_weaponry.weapon.animation.MovementLayer;
import com.arg_studios.arg_weaponry.weapon.animation.MovementState;
import com.arg_studios.arg_weaponry.weapon.animation.ReusableAnimations;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationController;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPoseBlend;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPoseLayers;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponTransition;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

import java.util.List;
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
     * Fade lengths for the other clip changes, all on TaCZ's ease-out curve.
     *
     * <p>Each one is shorter than the clip it introduces (the controller additionally caps a fade at half the incoming
     * clip's length), so an action still starts as itself: a shot reads immediately, while a reload, an inspection and the
     * return to idle ease in over about a tenth of a second - the range the reference implementation uses for its own
     * state changes.</p>
     */
    static final double FIRE_BLEND_SECONDS = 0.08;

    /**
     * The cross-fade into a reload clip, used when the entry declares no {@code transition_duration}.
     *
     * <p>A staged reload is several clips back to back, so every stage boundary is a pose change: the end of the opening
     * clip is not the start of the shell clip, and neither is the end of the looping clip the start of the closing one.
     * Blending for this long turns each of those boundaries into one continuous motion. The value matches
     * {@link com.arg_studios.arg_weaponry.weapon.reload.ReloadRules#DEFAULT_TRANSITION_SECONDS}; a pack can override it
     * per entry with {@code transition_duration}, and {@code 0} switches without blending.</p>
     */
    static final double RELOAD_BLEND_SECONDS = 0.15;
    static final double INSPECT_BLEND_SECONDS = 0.16;
    /**
     * The return to idle, long enough to use the ported TaCZ curve (see {@code WeaponTransition#TA_CZ_CURVE_MIN_SECONDS});
     * nothing is time-critical on this fade, so the calmer settle is worth the extra ticks.
     */
    static final double IDLE_BLEND_SECONDS = 0.26;

    /**
     * [DrawHolster hook] How long a finished equip clip may keep holding its last frame while the equip subsystem has not
     * reported completion yet.
     *
     * <p>The completion always arrives within a few ticks of the window, so this is a safety net rather than a timing
     * rule: it guarantees that a caller which somehow never completes the transition cannot leave the weapon stuck on a
     * finished clip. Server-side timing and authority are untouched.</p>
     */
    static final double MAX_EQUIP_HOLD_SECONDS = 2.0;

    /**
     * How long a reload clip whose animation has ended may keep drawing its last frame while the reload itself is still
     * running.
     *
     * <p>This is the reload counterpart of {@link #MAX_EQUIP_HOLD_SECONDS} and, like it, a safety net only: the reload
     * ends when the server ends it, so the hold normally lasts well under a tick. It exists so a lost outcome packet can
     * never leave the weapon frozen on a finished reload pose.</p>
     */
    static final double MAX_RELOAD_HOLD_SECONDS = 2.0;

    /** [DrawHolster diagnostic] Root movement in one frame, in model units, that counts as a discontinuity. */
    private static final double POSE_JUMP_UNITS = 2.0;

    /** [DrawHolster diagnostic] Root rotation in one frame, in degrees, that counts as a discontinuity. */
    private static final double POSE_JUMP_DEGREES = 8.0;


    /** Slowest a reload clip may be played to stretch it onto the configured duration. */
    static final double MIN_PLAYBACK_RATE = 0.25;

    /** Fastest a reload clip may be played to compress it onto the configured duration. */
    static final double MAX_PLAYBACK_RATE = 4.0;

    private final WeaponAnimationController animation = new WeaponAnimationController();

    /**
     * The movement overlay: the walking clips and the running sequence, merged into whatever this weapon's base clip
     * produces. It owns no playback precedence - it can only ever add root motion to a pose the base clip already
     * produced - which is what keeps a reload, an inspection, a shot or an equip transition in full control.
     */
    private final MovementLayer movement = new MovementLayer();

    /**
     * The aiming overlay: the optional authored {@code aim_in} / {@code aim_out} clips of this weapon, merged into the pose
     * <em>after</em> the movement overlay so an aiming animation wins every channel it animates while movement keeps driving
     * the rest.
     */
    private final AimAnimationLayer aimAnimation = new AimAnimationLayer();

    /** The animation resource the movement layer was bound to, so a resource change re-binds it exactly once. */
    private Object movementResource;
    private ContentPack movementPack;
    private List<String> movementLibraries = List.of();

    /** The aiming configuration the aiming layer was bound to, so a changed weapon or resource re-binds it exactly once. */
    private Object aimResource;
    private ContentPack aimPack;
    private List<String> aimLibraries = List.of();
    private String aimInName;
    private String aimOutName;

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

    /**
     * The pose cross-fade every clip change uses. See {@link WeaponTransition} for the ported TaCZ algorithm: the outgoing
     * pose is frozen at the switch, the incoming clip keeps playing, and the blend runs on TaCZ's ease-out curve.
     */
    private final WeaponTransition transition = new WeaponTransition();
    /** [DrawHolster hook] How long the finished equip clip has been holding its last frame. */
    private double heldEquipSeconds;

    /**
     * The looping idle clip's phase when an action took playback over.
     *
     * <p>Resuming the same idle clip at that phase instead of at time zero is what stops the idle sway from restarting every
     * time a shot, a reload, an inspection or a draw ends - the "losing animation phase between compatible looping states"
     * behaviour. A different idle selection (an empty magazine, for example) starts at its own first frame, as it must.</p>
     */
    private String parkedIdleAnimation;
    private double parkedIdleSeconds;

    /**
     * [DrawHolster hook] The exact pose the last rendered frame returned for the current owner.
     *
     * <p>This is the only pose that is guaranteed to have been on screen, which is what makes it usable as a cross-fade
     * source and as the discontinuity watchdog's reference. It is cleared by {@link #reset()} and by
     * {@link #activate(Owner, ContentPack, WeaponDefinition, int)}, so it can never describe a weapon the controller no
     * longer belongs to.</p>
     */
    private WeaponPose lastDisplayedPose;

    /**
     * [DrawHolster hook] The pose the last rendered frame produced <em>before</em> the movement and aiming overlays were
     * merged into it.
     *
     * <p>This is the only pose a cross-fade may start from. {@link #lastDisplayedPose} is what the player saw, which already
     * contains the locomotion root motion; fading from that pose and then applying the movement overlay again would count the
     * bob twice for the length of the fade - the "the weapon lurches on the first frame of an action" artefact. The overlays
     * are re-applied on top of whatever this pose becomes, so a fade source must never contain them.</p>
     */
    private WeaponPose lastBasePose;

    /**
     * The locomotion timeline of the weapon this controller replaced, waiting for this weapon's clips to be bound.
     *
     * <p>{@link #ensureMovement} binds on the first tick and {@code bind} resets the layer, so the timeline cannot be restored
     * in the constructor; it is held here and applied as soon as the clips are known.</p>
     */
    private MovementLayer.Timeline pendingLocomotion;

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

    /** How long the current reload clip has been drawing its last frame; see {@link #MAX_RELOAD_HOLD_SECONDS}. */
    private double reloadHoldSeconds;

    private int lastSelectionAmmo = Integer.MIN_VALUE;

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
        // [Movement] The carry is the *base* pose, never the composed one: the incoming weapon applies its own movement
        // overlay on top of the fade, so a source that already contained this weapon's locomotion would apply it twice.
        WeaponPose outgoing = poseRendered ? lastBasePose : null;
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
        return tick(pack, definition, ammoCount, deltaSeconds, MovementState.STILL);
    }

    /**
     * Advances playback by one client tick, including the movement overlay.
     *
     * <p>The movement layer is advanced here, next to the clip it will be merged into, so both timelines belong to the same
     * tick. It always receives the player's real movement, but its overlay is gated off while the base clip is an action: a
     * reload, inspection, shot or equip transition has its own root motion, and overlaying a walk cycle on top of it would
     * replace exactly the motion that makes those clips readable. Because only the contribution is gated - not the timeline
     * - the walk and run phases keep running and the movement fades back in where it was.</p>
     *
     * @param pack          the content pack that owns the weapon
     * @param definition    the weapon definition
     * @param ammoCount     the stack's current ammunition count, the idle selection input
     * @param deltaSeconds  elapsed time since the previous tick, in seconds
     * @param state         the player's movement, never {@code null}
     * @return the pose at the new time, or {@code null} when nothing is playing
     */
    public synchronized WeaponPose tick(ContentPack pack, WeaponDefinition definition, int ammoCount,
                                        double deltaSeconds, MovementState state) {
        return tick(pack, definition, ammoCount, deltaSeconds, state, false);
    }

    /**
     * Advances playback by one client tick, including the movement overlay and the aiming overlay.
     *
     * <p>The aiming flag is a plain boolean, not the aiming controller: the animation pipeline stays free of the client's
     * input and can be verified without a running game, and the two layers communicate only through well-defined states.
     * Both overlays are advanced here, next to the base clip they will be merged into, so all three timelines belong to the
     * same tick.</p>
     *
     * @param pack          the content pack that owns the weapon
     * @param definition    the weapon definition
     * @param ammoCount     the stack's current ammunition count, the idle selection input
     * @param deltaSeconds  elapsed time since the previous tick, in seconds
     * @param state         the player's movement, never {@code null}
     * @param aiming        whether the player is holding aim right now
     * @return the pose at the new time, or {@code null} when nothing is playing
     */
    public synchronized WeaponPose tick(ContentPack pack, WeaponDefinition definition, int ammoCount,
                                        double deltaSeconds, MovementState state, boolean aiming) {
        ensureMovement(pack, definition);
        ensureAim(pack, definition);
        refreshIdle(pack, definition, ammoCount);
        if (Double.isFinite(deltaSeconds) && deltaSeconds > 0.0) {
            // [DrawHolster hook] The equip clip is retimed like a reload/inspect clip, so it ends exactly when the
            // authoritative window does.
            double rate = equipPlaying ? equipPlaybackRate
                    : reloadPlaying ? reloadPlaybackRate : inspectPlaying ? inspectPlaybackRate : 1.0;
            // The tick length and the rate are passed separately: the controller advances its clock by the product and scales
            // the sub-tick sampling offset by the same rate, which is what keeps a retimed clip on one continuous timeline.
            animation.tick(Math.min(deltaSeconds, MAX_FRAME_DELTA_SECONDS), rate);
        }
        MovementState fed = state == null ? MovementState.STILL : state;
        // The real movement is always fed; the gate is what hides the overlay while an action owns the pose. The walk and
        // run timelines therefore keep their phase, so the movement returns where it was instead of restarting.
        // The aiming flag and the firing flag are what the locomotion state machine needs to choose the aiming walk pose and
        // to refuse to start a sprint mid-shot - the reference reads the same two facts from its gun state.
        movement.update(fed, deltaSeconds, movementCompatible(), aiming, firePlaying);
        // The aiming layer is independent of the action gate for its own state: a held aim keeps its phase, and only its
        // contribution fades out while a higher-priority clip owns the pose.
        aimAnimation.update(aiming, aimCompatible(), deltaSeconds);
        finishExpiredClips(pack, definition, deltaSeconds);
        if (equipPlaying && animation.isPaused()) {
            // An armed draw is waiting for the frame that first draws it, so its cross-fade has to wait too: the fade
            // belongs to the visible raise, and consuming it here would make that first drawn frame start mid-fade - the
            // "the weapon snaps into place" artifact this hook exists to remove. The clip itself is paused for the same
            // reason.
            return pose();
        }
        advanceBlend(deltaSeconds);
        return pose();
    }

    /**
     * [Movement] Merges the movement overlay into the pose that is about to be drawn.
     *
     * <p>This is a <strong>weighted</strong> contribution, never a switch: the layer's own weight already fades to zero while
     * a shot, a reload, an inspection or a draw owns the pose (it is fed {@link #movementCompatible()} every tick) and fades
     * back in afterwards. Skipping the overlay outright as soon as an action started is what made a moving weapon jump
     * between its movement pose and its base pose on every shot - a whole-pose discontinuity, not a bob. The action still
     * wins: by the time its first frame is drawn the weight is on its way to zero, and the fade means the movement travels
     * into the action pose instead of being cut out of it.</p>
     *
     * @param base   the pose the base clip produced, may be {@code null}
     * @param offset the frame's sub-tick sampling offset
     * @return the merged pose, exactly {@code base} when the layer contributes nothing
     */
    private WeaponPose overlayMovement(WeaponPose base, double offset) {
        if (movement.isEmpty()) {
            return base;
        }
        WeaponPose layer = movement.pose(offset);
        if (layer == null) {
            return base;
        }
        // The weight is sampled at the frame's own offset: the fade resolves once per tick, and applying the tick value to
        // every frame of that tick would turn a 0.10 s fade into a two-step staircase.
        return WeaponPoseLayers.overlay(base, layer, movement.weightAt(offset));
    }

    /**
     * [Aiming] Merges the aiming overlay into the pose that is about to be drawn.
     *
     * <p>Applied after the movement overlay on purpose: an aiming clip wins every channel it animates, while a channel it
     * leaves out keeps the movement's root motion. The layer itself clamps its contribution to zero while a higher-priority
     * action owns the pose, so it can never overwrite a reload, an inspection, a shot or an equip clip.</p>
     *
     * @param base   the pose the base clip (and the movement overlay) produced, may be {@code null}
     * @param offset the frame's sub-tick sampling offset
     * @return the merged pose, exactly {@code base} when the layer contributes nothing
     */
    private WeaponPose overlayAim(WeaponPose base, double offset) {
        if (aimAnimation.isEmpty() || aimAnimation.weight() <= 0.0F) {
            return base;
        }
        WeaponPose layer = aimAnimation.pose(offset);
        if (layer == null) {
            return base;
        }
        return WeaponPoseLayers.overlay(base, layer, aimAnimation.weight());
    }

    /**
     * [Aiming] Whether the aiming overlay may contribute to the current base pose.
     *
     * <p>The same rule as the movement overlay: an action clip owns the whole pose while it plays. The aiming layer keeps
     * its own state either way, so it returns where it was instead of replaying {@code aim_in}.</p>
     *
     * @return {@code true} when aiming may be merged into the current base pose
     */
    private boolean aimCompatible() {
        return movementCompatible();
    }

    /**
     * [Movement] Whether the movement overlay may contribute at full weight right now.
     *
     * <p>This is the layer's <em>fade target</em>, not a gate: the value is fed to
     * {@link MovementLayer#update(MovementState, double, boolean, boolean, boolean)} every tick, which moves the overlay's
     * weight towards {@code 1} or {@code 0} over {@link MovementLayer#FADE_SECONDS}. An action clip still owns the pose - the
     * weight reaches zero within two ticks and stays there for as long as the action plays - but the movement travels in and
     * out of the action instead of being switched off, which is the difference between a cross-fade and a jump. Aiming needs
     * no special case: it is an additive placement, not a clip.</p>
     *
     * @return {@code true} when movement may contribute at full weight
     */
    private boolean movementCompatible() {
        return !isActionPlaying();
    }

    /**
     * @return whether a weapon action currently owns the pose: a shot, a reload, an inspection or the draw/holster clip
     */
    public synchronized boolean isActionPlaying() {
        return firePlaying || reloadPlaying || inspectPlaying || equipPlaying;
    }

    /**
     * Binds the movement layer to the weapon's animation resource, once per resource.
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     */
    private void ensureMovement(ContentPack pack, WeaponDefinition definition) {
        WeaponDisplay display = WeaponDisplayCache.instance().get(pack, definition).orElse(null);
        ResourceId primary = display == null ? null : display.animations();
        List<String> libraries = definition.reusableDefaultAnimations();
        if (movementPack == pack && Objects.equals(movementResource, primary)
                && movementLibraries.equals(libraries)) {
            return;
        }
        movementPack = pack;
        movementResource = primary;
        movementLibraries = libraries;
        AnimationFile file = primary == null && libraries.isEmpty() ? null
                : ReusableAnimations.resolve(pack, definition, primary).orElse(null);
        if (file == null) {
            movement.bind(null);
            applyPendingLocomotion();
            return;
        }
        boolean bound = movement.bind(file);
        Constants.LOG.debug("[ARG] Weapon '{}' movement layer {} ({} clip(s) found, {} reusable librar{})",
                definition.name(), bound ? "bound" : "is inactive",
                bound ? "with" : "no", libraries.size(), libraries.size() == 1 ? "y" : "ies");
        applyPendingLocomotion();
    }

    /**
     * [Movement] Continues the locomotion track of the weapon this controller replaced.
     *
     * <p>A weapon switch must not restart the walk: the player is still walking, at the same speed, in the same direction.
     * {@code VisualWeapon} is constructed with a fresh controller, so the phase, the direction weights, the playback rate and
     * the overlay weight are handed over explicitly - as a value taken <em>before</em> the outgoing weapon was disposed, which
     * is what makes the transfer independent of both weapons' lifetimes. No pose is transferred, so the movement overlay can
     * never be applied twice.</p>
     *
     * @param timeline the outgoing weapon's locomotion timeline, may be {@code null}
     */
    public synchronized void carryLocomotionFrom(MovementLayer.Timeline timeline) {
        pendingLocomotion = timeline;
        applyPendingLocomotion();
    }

    /**
     * @return this controller's locomotion timeline, so a weapon that replaces this one can continue it. Take it before the
     *         controller is reset: a reset restarts the track from rest.
     */
    public synchronized MovementLayer.Timeline locomotionTimeline() {
        return movement.timeline();
    }

    /** Restores a pending locomotion timeline once this controller's clips are known. */
    private void applyPendingLocomotion() {
        if (pendingLocomotion == null) {
            return;
        }
        if (movementPack == null && movement.isEmpty()) {
            // The clips are bound on the first tick, and binding resets the layer; keep the timeline until then.
            return;
        }
        if (!movement.isEmpty()) {
            movement.restore(pendingLocomotion);
        }
        pendingLocomotion = null;
    }

    /**
     * Binds the aiming layer to the weapon's {@code aim_in} / {@code aim_out} identifiers and its animation resource.
     *
     * <p>Re-binding only happens when the resource or one of the two identifiers changed, so a weapon that keeps the same
     * configuration never has a running {@code aim_in} restarted by a tick. A weapon that declares neither clip simply gets
     * an inactive layer - the framework's placement and zoom transition is still the whole aiming behaviour.</p>
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     */
    private void ensureAim(ContentPack pack, WeaponDefinition definition) {
        WeaponDisplay display = WeaponDisplayCache.instance().get(pack, definition).orElse(null);
        ResourceId primary = display == null ? null : display.animations();
        List<String> libraries = definition.reusableDefaultAnimations();
        WeaponDefinition.Aiming aiming = definition == null ? null : definition.aiming();
        String aimIn = aiming == null ? null : aiming.aimIn();
        String aimOut = aiming == null ? null : aiming.aimOut();
        if (aimPack == pack && Objects.equals(aimResource, primary) && Objects.equals(aimInName, aimIn)
                && Objects.equals(aimOutName, aimOut) && aimLibraries.equals(libraries)) {
            return;
        }
        aimPack = pack;
        aimResource = primary;
        aimLibraries = libraries;
        aimInName = aimIn;
        aimOutName = aimOut;
        if ((primary == null && libraries.isEmpty()) || (aimIn == null && aimOut == null)) {
            aimAnimation.reset();
            return;
        }
        AnimationFile file = ReusableAnimations.resolve(pack, definition, primary).orElse(null);
        boolean bound = aimAnimation.bind(file, aimIn, aimOut);
        Constants.LOG.debug("[ARG] Weapon '{}' aiming layer {} (aim_in='{}', aim_out='{}')", definition.name(),
                bound ? "bound" : "is inactive", aimIn, aimOut);
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
        WeaponPose displayed = blendDisplayed(sampled, offset);
        // [Movement] The fade source for the *next* clip must not contain the ordered overlays: they are merged in below, and a
        // source that already had them would have the locomotion counted twice for the length of the fade.
        lastBasePose = displayed == null || displayed.isEmpty() ? null : displayed;
        // [Movement] The overlay is applied last, on the pose that will actually be drawn, so it composes with the idle
        // selection, the equip cross-fade and every other base pose without any of them knowing about it. It is a weighted
        // contribution - the layer fades itself out while an action owns the pose - never a switch.
        displayed = overlayMovement(displayed, offset);
        // [Aiming] Applied last, so an aiming clip wins the channels it animates while movement keeps the rest.
        displayed = overlayAim(displayed, offset);
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
                transition.isActive());
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
        return transition.apply(sampled, offsetSeconds);
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
        transition.start(from, keepOutsideClipBoundary(durationSeconds));
        invalidateSampledPose();
    }

    /**
     * Keeps a fade short enough that it never swallows the clip it fades into.
     *
     * <p>TaCZ declares a transition time per animation for the same reason: a fade may not be longer than the animation it
     * introduces. Half the clip's length is the ceiling here, so a one-shot action still reads as itself.</p>
     *
     * @param requested the requested fade length in seconds
     * @return the fade length to use, never negative
     */
    private double keepOutsideClipBoundary(double requested) {
        if (!(requested > 0.0)) {
            return 0.0;
        }
        if (animation == null || animation.durationSeconds() <= 0.0) {
            return requested;
        }
        return Math.min(requested, animation.durationSeconds() * 0.5);
    }

    /**
     * @return the pose the last rendered frame showed, or {@code null} when nothing is animating
     *
     * <p>The captured pose is deliberately <em>not</em> re-sampled from the clip that happens to be playing now. That
     * distinction is the whole point: by the time a new clip asks where to fade from, the previous clip may already have
     * been replaced - the reload path resumes the idle clip before the next stage starts - so a re-sample would return a
     * frame of the <em>incoming</em> clip and the weapon would jump to the idle pose before easing into the next stage.
     * The captured base pose is the frame the player was looking at, so the fade always starts from there.</p>
     */
    private WeaponPose displayedPose() {
        if (lastBasePose == null || lastBasePose.isEmpty()) {
            // Nothing was drawn from a pose, so there is nothing to fade from; the clip starts at its own first frame.
            return null;
        }
        return lastBasePose;
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
        if (!transition.isActive()) {
            return;
        }
        transition.advance(deltaSeconds);
        invalidateSampledPose();
    }

    /** Forgets any running cross-fade. */
    private void clearBlend() {
        transition.clear();
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
            // HOLD_ON_LAST_FRAME, exactly like the reference implementation: a reload animation is owned by the reload,
            // not by its own clip. PB gates its reload controllers on the authoritative reload state and plays every
            // reload clip with LoopType.HOLD_ON_LAST_FRAME, so a clip that ends before the reload does simply keeps
            // drawing its final pose - it never hands playback back to idle.
            //
            // This is the reload counterpart of the equip hold below and the reason a staged reload cannot fall out of
            // its animation between two stages: if a stage's clip reaches its end before the next stage is due (the
            // staged schedule is wall-clock based while the clip clock accumulates client tick deltas, and a duration
            // that is not a whole number of ticks is rounded up for the schedule but not for the retiming), the weapon
            // keeps the pose it is already in instead of blending towards the idle clip. The next stage then cross-fades
            // from that pose, and the server's end - or a cancellation - returns playback to idle.
            double held = Double.isFinite(deltaSeconds) && deltaSeconds > 0.0
                    ? Math.min(deltaSeconds, MAX_FRAME_DELTA_SECONDS) : 0.0;
            reloadHoldSeconds += held;
            if (reloadHoldSeconds < MAX_RELOAD_HOLD_SECONDS) {
                return;
            }
            // The reload never reported its end (a lost packet). A finished clip must not lock the weapon, so end it
            // the way a completion would and let the fade take over.
            Constants.LOG.debug("[ARG] Reload clip '{}' held its last frame for {}s without an end; returning to idle",
                    reloadAnimation, reloadHoldSeconds);
            WeaponPose outgoing = displayedPose();
            reloadPlaying = false;
            reloadAnimation = null;
            reloadDurationSeconds = 0.0;
            reloadPlaybackRate = 1.0;
            reloadHoldSeconds = 0.0;
            resumeIdle(pack, definition);
            beginBlend(outgoing, IDLE_BLEND_SECONDS);
            return;
        }
        if (inspectPlaying) {
            WeaponPose outgoing = displayedPose();
            inspectPlaying = false;
            inspectAnimation = null;
            inspectPlaybackRate = 1.0;
            resumeIdle(pack, definition);
            beginBlend(outgoing, IDLE_BLEND_SECONDS);
            return;
        }
        if (firePlaying) {
            WeaponPose outgoing = displayedPose();
            firePlaying = false;
            fireAnimation = null;
            resumeIdle(pack, definition);
            beginBlend(outgoing, IDLE_BLEND_SECONDS);
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
        // Captured before the clip changes, so the fade starts from the pose that is on screen (TaCZ freezes the outgoing
        // runner at the switch for the same reason).
        WeaponPose outgoing = blendSource();
        parkIdlePhase();
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
        beginBlend(outgoing, FIRE_BLEND_SECONDS);
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
        return playReload(pack, definition, name, durationSeconds, RELOAD_BLEND_SECONDS);
    }

    /**
     * Starts one reload clip at an explicit cross-fade length.
     *
     * <p>The fade length is the only thing a staged reload contributes beyond the clip itself: it is taken from the
     * entry's optional {@code transition_duration} and it is entirely a rendering concern. The clip still starts at
     * {@code durationSeconds} worth of its own timeline from zero, the playback rate is still derived from the
     * server-side duration, and nothing about the reload's timing, its insertions or its iteration counter depends on
     * it - the fade overlaps the clip instead of postponing it.</p>
     *
     * @param pack              the content pack that owns the weapon
     * @param definition        the weapon definition
     * @param name              the declared reload animation name
     * @param durationSeconds   the server-side duration of this clip
     * @param transitionSeconds the cross-fade into it, {@code 0} to switch without blending
     * @return {@code true} when a reload animation is playing afterwards
     */
    public synchronized boolean playReload(ContentPack pack, WeaponDefinition definition, String name,
                                           double durationSeconds, double transitionSeconds) {
        if (owner == null || definition == null || name == null || name.isBlank()) {
            return false;
        }
        if (reloadPlaying && name.equals(reloadAnimation)) {
            return true;
        }
        WeaponPose outgoing = blendSource();
        parkIdlePhase();
        if (!startDeclaredAnimation(pack, definition, name)) {
            return false;
        }
        reloadAnimation = name;
        reloadPlaying = true;
        reloadHoldSeconds = 0.0;
        reloadDurationSeconds = Double.isFinite(durationSeconds) && durationSeconds > 0.0 ? durationSeconds : 0.0;
        reloadPlaybackRate = playbackRateFor(animation.durationSeconds(), reloadDurationSeconds);
        firePlaying = false;
        fireAnimation = null;
        inspectPlaying = false;
        inspectAnimation = null;
        inspectPlaybackRate = 1.0;
        beginBlend(outgoing, transitionSeconds);
        Constants.LOG.debug("[ARG] Reload clip '{}' started: {}s long, {}s configured, playback rate {}, "
                        + "cross-fade {}s", name, animation.durationSeconds(), reloadDurationSeconds,
                reloadPlaybackRate, transitionSeconds);
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
        WeaponPose outgoing = blendSource();
        parkIdlePhase();
        if (!startDeclaredAnimation(pack, definition, name)) {
            return false;
        }
        inspectAnimation = name;
        inspectPlaying = true;
        inspectPlaybackRate = playbackRateFor(animation.durationSeconds(),
                Double.isFinite(durationSeconds) && durationSeconds > 0.0 ? durationSeconds : 0.0);
        firePlaying = false;
        fireAnimation = null;
        beginBlend(outgoing, INSPECT_BLEND_SECONDS);
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
        // The last reload clip is usually still playing when the server reports the end (it ends when the authoritative
        // duration does), so the return to idle is a pose change like any other stage boundary: it is captured and
        // cross-faded instead of cutting. A cancelled reload takes the same path.
        WeaponPose outgoing = blendSource();
        reloadPlaying = false;
        reloadAnimation = null;
        reloadDurationSeconds = 0.0;
        reloadPlaybackRate = 1.0;
        reloadHoldSeconds = 0.0;
        idleAnimation = definition == null ? null : selectIdle(definition, lastSelectionAmmo);
        resumeIdle(pack, definition);
        beginBlend(outgoing, IDLE_BLEND_SECONDS);
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
        return playEquip(pack, definition, name, durationSeconds, null, false);
    }

    /**
     * [DrawHolster hook] Arms a draw clip on a weapon that is about to become visible, without letting its animation run yet.
     *
     * <p>This is the reference implementations' "start the draw on the first rendered frame" rule, expressed on the animator:
     * the clip is started at its first frame and then <em>paused</em>, so no frame of it is consumed while the weapon is not
     * being drawn - third person, a screen, or the frame that lands between a slot change and the next tick. The owner
     * releases it with {@link #releaseEquipPlayback()} from the frame in which the weapon is really drawn, so the first
     * drawn frame is the clip's first frame instead of a pose the player never saw.</p>
     *
     * <p>{@code blendFrom} is the pose the outgoing weapon was last drawn with. Fading from it is what makes the hand-off
     * continuous: at the boundary frame the new weapon is drawn where the old one ended, and the draw eases in from there
     * instead of cutting.</p>
     *
     * @param pack            the content pack that owns the weapon
     * @param definition      the weapon definition
     * @param name            the declared draw clip name
     * @param durationSeconds the authored window, in seconds
     * @param blendFrom       the pose to fade in from, may be {@code null}
     * @return {@code true} when the clip was armed
     */
    public synchronized boolean armEquip(ContentPack pack, WeaponDefinition definition, String name,
                                         double durationSeconds, WeaponPose blendFrom) {
        return playEquip(pack, definition, name, durationSeconds, blendFrom, true);
    }

    /**
     * [DrawHolster hook] Starts playback of a clip that was armed with {@link #armEquip}.
     *
     * <p>Safe to call when nothing is armed: a playhead that is already running is left alone.</p>
     *
     * @return {@code true} when this call started the paused playback
     */
    public synchronized boolean releaseEquipPlayback() {
        if (!equipPlaying || !animation.isPaused()) {
            return false;
        }
        animation.resume();
        Constants.LOG.debug("[ARG] Equip clip '{}' released on its first rendered frame", equipAnimation);
        return true;
    }

    /** @return {@code true} while an armed clip is waiting for its first rendered frame. */
    public synchronized boolean isEquipAwaitingFirstFrame() {
        return equipPlaying && animation.isPaused();
    }

    /**
     * @return the pose the last rendered frame of this controller returned, or {@code null} when nothing was drawn. Used as
     *         the cross-fade source of the weapon that replaces this one.
     */
    public synchronized WeaponPose lastDisplayedPose() {
        return poseRendered ? lastDisplayedPose : null;
    }

    private synchronized boolean playEquip(ContentPack pack, WeaponDefinition definition, String name,
                                           double durationSeconds, WeaponPose blendFrom, boolean paused) {
        if (owner == null || definition == null || name == null || name.isBlank()) {
            return false;
        }
        if (equipPlaying && name.equals(equipAnimation)) {
            return true;
        }
        // [DrawHolster hook] Capture what the player is looking at before the clip changes: an interrupted raise continues
        // from the displayed pose, and a weapon switch continues from the pose the outgoing weapon was drawn with.
        WeaponPose outgoing = blendFrom != null ? blendFrom : blendSource();
        parkIdlePhase();
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
        if (paused) {
            animation.pause();
        }
        Constants.LOG.debug("[ARG] Equip clip '{}' started: {}s long, {}s configured, playback rate {}{}", name,
                animation.durationSeconds(), durationSeconds, equipPlaybackRate, paused ? ", armed (paused)" : "");
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
        WeaponPose outgoing = displayedPose();
        idleAnimation = startDeclaredAnimation(pack, definition, selected) ? selected : null;
        if (idleAnimation != null) {
            // Two different looping idle clips (full magazine to empty magazine): the new one starts at its own first frame
            // and the old pose fades into it, so an ammunition change never cuts.
            beginBlend(outgoing, IDLE_BLEND_SECONDS);
        }
    }

    /**
     * Remembers the looping idle clip's phase before an action takes playback over.
     *
     * <p>Only recorded when the idle clip owns playback right now: an action interrupting another action must not overwrite
     * the phase the idle really stopped at.</p>
     */
    private void parkIdlePhase() {
        if (firePlaying || reloadPlaying || inspectPlaying || equipPlaying) {
            return;
        }
        if (idleAnimation == null || animation == null || !idleAnimation.equals(animation.animationName())) {
            return;
        }
        parkedIdleAnimation = idleAnimation;
        parkedIdleSeconds = animation.elapsedSeconds();
    }

    /**
     * Seeks a resumed idle clip back to the phase it was interrupted at, when it is the same clip.
     *
     * @param selected the idle clip playback just returned to
     */
    private void restoreIdlePhase(String selected) {
        String parked = parkedIdleAnimation;
        double seconds = parkedIdleSeconds;
        parkedIdleAnimation = null;
        parkedIdleSeconds = 0.0;
        if (parked == null || !parked.equals(selected) || !(seconds > 0.0)) {
            return;
        }
        double length = animation.durationSeconds();
        double phase = length > 0.0 ? seconds % length : seconds;
        animation.seek(phase);
        invalidateSampledPose();
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
            parkedIdleAnimation = null;
            parkedIdleSeconds = 0.0;
            return;
        }
        if (startDeclaredAnimation(pack, definition, selected)) {
            restoreIdlePhase(selected);
        }
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
        // [DrawHolster hook] A fade belongs to the weapon that started it, so an ownership change drops it as well.
        clearBlend();
        // [DrawHolster hook] Nothing displayed by this controller may survive a reset: a pose from an earlier weapon, or from
        // before a world change, a resource reload or a release, must never become a cross-fade source.
        lastDisplayedPose = null;
        lastBasePose = null;
        poseRendered = false;
        carriedPose = null;
        // [Movement] A locomotion track belongs to the weapon that played it, so a reset restarts it from rest as well - and a
        // timeline waiting to be adopted must not outlive the reset either.
        pendingLocomotion = null;
        movement.reset();
        movementResource = null;
        movementPack = null;
        movementLibraries = List.of();
        // [Aiming] An aiming clip belongs to this weapon's owner too, and must never be replayed from a stale phase.
        aimAnimation.reset();
        aimPack = null;
        aimResource = null;
        aimLibraries = List.of();
        aimInName = null;
        aimOutName = null;
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
        ResourceId primary = display == null ? null : display.animations();
        if (primary == null && definition.reusableDefaultAnimations().isEmpty()) {
            Constants.LOG.debug("[ARG] Weapon '{}' declares animation '{}' but its display configuration "
                    + "names no animation resource and no reusable libraries", definition.name(), name);
            return false;
        }

        AnimationFile file = ReusableAnimations.resolve(pack, definition, primary).orElse(null);
        playbackGeneration++;
        if (!animation.play(file, name, null)) {
            // The cache already reported the missing document or animation name once.
            return false;
        }
        Constants.LOG.debug("[ARG] Weapon '{}' started animation '{}' from {} ({} reusable librar{})",
                definition.name(), name, primary, definition.reusableDefaultAnimations().size(),
                definition.reusableDefaultAnimations().size() == 1 ? "y" : "ies");
        return true;
    }
}
