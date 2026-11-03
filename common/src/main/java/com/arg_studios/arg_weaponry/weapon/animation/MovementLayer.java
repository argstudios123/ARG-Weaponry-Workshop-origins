package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The first-person locomotion layer: a state machine that mirrors Vic's Point Blank, sampling this framework's weapon
 * animations.
 *
 * <h2>Layer, not replacement</h2>
 * <p>Movement is the only animation family that runs <em>while another animation runs</em>: a player walks while the weapon
 * idles, aims, fires or reloads. Replacing the playing clip with a walking clip would drop the hand poses the weapon needs,
 * which is why the shipped locomotion clips animate {@code root} only - they are authored as an overlay on top of whatever
 * the weapon is doing. This class produces exactly that overlay, and {@link WeaponPoseLayers#overlay} merges it into the
 * base pose without touching a channel it does not animate.</p>
 *
 * <h2>What changed relative to the old running sequence</h2>
 * <p>The previous implementation advanced four independent clocks and a home-grown run sequence
 * ({@code STARTING/RUNNING/HOLDING/ENDING}) whose transitions were derived from the analog input. That produced the reported
 * jitter: the sequence could leave and re-enter a stage on a single tick (a step down, a dropped input, an airborne
 * flicker), every re-entry started a cross-fade, and the walking clock was frozen for the whole run. This implementation
 * keeps the pose algebra but replaces the sequencing with {@link LocomotionStateMachine} - a port of the reference's
 * transition table - and adds the three things that make the reference feel continuous:</p>
 * <ol>
 *   <li><strong>One-shots are played to the end.</strong> {@code run_start} must finish before {@code run} starts, and
 *   {@code run_end} must finish before any ground state is entered - the reference's {@code !isPlayingAnimation(...)} gates.
 *   The state therefore cannot be left and re-entered mid-clip.</li>
 *   <li><strong>Running-end carryover.</strong> Leaving the prepare phase plays {@code run_end} from the <em>mirrored</em>
 *   phase of {@code run_start} (see {@link #enter}), which is the reference's
 *   {@code computeRunningEndCarryoverOffsetFraction} behaviour: the pose continues where it was instead of restarting.</li>
 *   <li><strong>Clips are clocked, not restarted.</strong> The walk phase, the run clock and the airborne clock survive the
 *   states that do not use them, so landing, direction changes and brief overlay gates resume instead of replaying.</li>
 * </ol>
 *
 * <h2>Clips and suffixes</h2>
 * <p>No new JSON property: the clips are discovered in the weapon's own animation resource by suffix -
 * {@code walk_forward}, {@code walk_backward}, {@code walk_left}, {@code walk_right}, {@code walking},
 * {@code walk_aiming}, {@code run_start}, {@code run}, {@code run_hold}, {@code run_end}, {@code off_ground} and
 * {@code off_ground_sprinting}. A clip called exactly that and one called {@code anim.model.that} both resolve. Every clip
 * is optional, and a state whose clips are missing falls back to a related clip or to the pose that was on screen when the
 * state was entered, so a pack written before this milestone keeps working unchanged.</p>
 *
 * <h2>Playback speed</h2>
 * <p>The locomotion clips advance at {@link LocomotionIntent#speedScale()}, which the client derives from the player's real
 * speed the same way the reference's walk speed provider does ({@code sqrt(clamp(speed / reference, 0.1, 10))}). A walk
 * that speeds up and slows down with the player is the difference between a bob that matches the footsteps and one that
 * drifts against them.</p>
 *
 * <h2>One time base, sampled by the frame</h2>
 * <p>Everything that shapes the pose is evaluated at the <em>frame's</em> timestamp, not at the tick's, which is what the
 * reference does by deriving its animation time from the render call:</p>
 * <ul>
 *   <li>each clock advances by {@code delta * rate} and the renderer adds {@code offset * rate}, so the frames inside a tick
 *   and the tick boundary itself move at the same speed ({@link #pose(double)});</li>
 *   <li>the direction weights and the overlay weight are interpolated between the value at the start of the tick and the
 *   value it resolved ({@link #weightAt(double)}), so a blend that moves half its range per tick is still a ramp;</li>
 *   <li>the cross-fade between two controllers keeps running its own clock, unscaled, because it measures real time.</li>
 * </ul>
 * <p>Before this, a clip played at one speed inside a tick and jumped at every tick boundary by {@code tick *
 * |speedScale - 1|}: a 20 Hz discontinuity whose size grows with the playback rate, and one that a tick-level test cannot
 * see.</p>
 *
 * <h2>Which clock plays at which rate</h2>
 * <p>A gait follows the player's speed, because that is what makes the bob match the footsteps. A <em>transition</em> does
 * not: {@code run_end} is a recovery the player is already decelerating through, and the jumping and landing clips are
 * reactions to an event, so {@link #clockRate} gives them their own authored rate. Inheriting the movement multiplier is what
 * made a released sprint crawl.</p>
 *
 * <h2>Jumping</h2>
 * <p>An airborne player samples the jumping ladder in {@link #airbornePose}: {@code jump_start} once, then
 * {@code jump_loop}, with the sprinting variants preferred while the sprint branch owns the jump, then Point Blank's single
 * {@code off_ground} clip, then the gait the player left the ground with. The touch-down edge in {@link #enter} starts the
 * one-shot landing reaction ({@code land} / {@code sprint_land}), which is an <em>overlay</em> rather than a state: the
 * machine keeps choosing between standing, walking and running underneath it.</p>
 *
 * <p>Those clips are reactions, not holds. The shipped jumping clips author a vertical translation (the loop keeps its root
 * about one unit above the idle pose) and playing that for the whole airborne period lifted the weapon on take-off and kept
 * it there at the apex and while falling, with no player movement behind it. The layer therefore maps the player's real
 * vertical velocity to {@link #JUMP_TAKEOFF_SPEED}'s {@code 0..1} response: the authored vertical part is applied while the
 * player is genuinely rising and is re-based onto the gait at the apex and during the fall, while the clip's authored
 * rotation is always kept. The landing clip is eased in from the pose the jump ended on, so a touch-down never snaps.</p>
 *
 * <h2>Transitions blend controllers, not snapshots</h2>
 * <p>A fade is only started when the two states sample <em>different clips</em> ({@link #controllerOf}), and the outgoing
 * state is then sampled again on every frame at the same timestamp as the incoming one. The four walking states and the
 * aiming walk therefore change direction without any fade at all - they are one controller - while a jump and a landing
 * hand over between the walking and running controllers without a pose ever being frozen.</p>
 */
public final class MovementLayer {

    /** The documented clip suffixes, in the order they are resolved. */
    public static final String WALK_FORWARD = "walk_forward";
    public static final String WALK_BACKWARD = "walk_backward";
    public static final String WALK_LEFT = "walk_left";
    public static final String WALK_RIGHT = "walk_right";

    /** A single walking clip, used when a pack does not author the four directional ones (Point Blank's {@code walking}). */
    public static final String WALKING = "walking";

    /** The aiming walk clip (Point Blank's {@code walking_aiming}). */
    public static final String WALK_AIMING = "walk_aiming";

    public static final String RUN_START = "run_start";
    public static final String RUN = "run";
    public static final String RUN_HOLD = "run_hold";
    public static final String RUN_END = "run_end";

    /** The airborne pose while not sprinting (Point Blank's {@code off_ground}). */
    public static final String OFF_GROUND = "off_ground";

    /** The airborne pose while sprinting (Point Blank's {@code off_ground_sprinting}). */
    public static final String OFF_GROUND_SPRINTING = "off_ground_sprinting";

    /**
     * The jumping one-shots and loops.
     *
     * <p>They split Point Blank's single {@code off_ground} clip into the three phases a first-person jump really has: a
     * take-off pose, an airborne loop and a landing reaction. The legacy single-clip names stay supported and take over when a
     * pack authors no jump clips, so every existing content pack keeps its exact current behaviour.</p>
     */
    public static final String JUMP_START = "jump_start";
    public static final String JUMP_LOOP = "jump_loop";
    public static final String SPRINT_JUMP_START = "sprint_jump_start";
    public static final String SPRINT_JUMP_LOOP = "sprint_jump_loop";

    /** The landing reaction, played once when the player touches down. */
    public static final String LAND = "land";
    public static final String SPRINT_LAND = "sprint_land";

    /**
     * The rate {@code run_end} plays at.
     *
     * <p>Deliberately independent of {@link #speedScale}. The frame rate is derived from the player's <em>speed</em>, and a
     * player who released sprint is decelerating, so the multiplier sits around 0.5 at exactly the moment the recovery starts:
     * inheriting it made the authored 0.42 s run-end play over roughly 0.8 s and kept the state alive for ~15 ticks instead of
     * ~9. A transition pose is a recovery, not a gait - it plays at its authored speed.</p>
     */
    public static final double RUN_END_PLAYBACK_RATE = 1.0;

    /** The rate the jumping clips and the landing reaction play at, for the same reason as {@link #RUN_END_PLAYBACK_RATE}. */
    public static final double JUMP_PLAYBACK_RATE = 1.0;

    /** How long the take-off pose cross-fades into the airborne loop, when the pack authors both. */
    public static final double JUMP_HANDOVER_SECONDS = 0.08;

    /**
     * The upward velocity a vanilla jump starts with, in blocks per tick.
     *
     * <p>The airborne clips carry their own vertical offsets, but those offsets are authored for the <em>whole</em> jump:
     * the shipped {@code jump_loop}, for example, holds its root about one unit above the idle pose for as long as the
     * player is off the ground. Applying that hold for the entire airborne period is what made the weapon look as if it was
     * floating up on its own, independent of the player's real movement. This constant turns the player's actual vertical
     * velocity into a {@code 0..1} response, so the authored vertical displacement is shown while the player is really
     * rising and settles back to the gait at the apex and during the fall.</p>
     */
    public static final double JUMP_TAKEOFF_SPEED = 0.42;

    /** How fast the vertical response follows the player's real vertical velocity, in seconds for a full change. */
    public static final double VERTICAL_RESPONSE_SECONDS = 0.10;

    /** How long the landing reaction takes to ease in from the pose it replaces, in seconds. */
    public static final double LAND_ENTER_SECONDS = 0.08;

    /**
     * How much of the landing reaction's authored vertical arc is kept, as a fraction of the arc from the clip's own
     * first frame to its dip.
     *
     * <p>The clips are authored to continue the airborne hold: {@code land} opens at the same height as
     * {@code jump_loop}, and {@code sprint_land} opens at the same height as {@code sprint_jump_loop}. Once the airborne
     * pose is re-based onto the gait (see {@link #JUMP_TAKEOFF_SPEED}), that opening value is no longer on screen, so the
     * reaction must be re-based onto the pose it actually replaces. This fraction then controls how deep its dip is;
     * {@code 0.5} keeps the touch-down reaction clearly visible without throwing the weapon off the screen.</p>
     */
    public static final double LAND_VERTICAL_SCALE = 0.5;

    /** How much of the landing reaction's tail is spent blending back into the locomotion pose. */
    public static final double LAND_RELEASE_SECONDS = 0.12;

    /**
     * How long the player must have been airborne for a touch-down to play a landing reaction.
     *
     * <p>A step down a half block leaves the ground for a single tick and must not fire a landing: the stabiliser already
     * keeps the <em>state</em> from flickering, and this keeps the reaction from flickering with it.</p>
     */
    public static final double LANDING_MIN_AIR_SECONDS = 0.15;

    /**
     * The cross-fade of an ordinary state change, in seconds.
     *
     * <p>Two client ticks, which is the length the reference declares for every locomotion transition except the ones around
     * {@code runningend} ({@code withTransition(from, to, 2, false)}). Short on purpose: the smoothness comes from the state
     * machine not churning, not from a long blend.</p>
     */
    public static final double TRANSITION_SECONDS = 0.10;

    /** The cross-fade into and out of {@code complete_running}, three client ticks in the reference. */
    public static final double COMPLETE_TRANSITION_SECONDS = 0.15;

    /** How long a full directional weight change takes, in seconds. */
    public static final double DIRECTION_BLEND_SECONDS = 0.20;

    /** How long the overlay takes to fade in or out, in seconds. */
    public static final double FADE_SECONDS = 0.10;

    /** How long after an action released the pose a locomotion state change is still suppressed, in seconds. */
    public static final double POST_ACTION_GRACE_SECONDS = 0.09;

    /** How fast the playback-rate multiplier follows the player's speed, in seconds for a full change. */
    public static final double SPEED_SCALE_BLEND_SECONDS = 0.15;

    /** Longest delta one update may apply, so a stall cannot jump the state machine. */
    private static final double MAX_DELTA_SECONDS = 0.1;

    /** Below this a vertical velocity counts as "no sample", which is what keeps legacy callers on the authored pose. */
    private static final double VERTICAL_VELOCITY_EPSILON = 1.0E-4;

    /** Below this a smoothed direction component counts as zero, so no pose is produced from numerical noise. */
    private static final double DIRECTION_EPSILON = 1.0E-4;

    /**
     * How much weight a direction needs before the state machine follows it.
     *
     * <p>A third of a full deflection: high enough that rapidly tapped strafes do not flip the state on every tick, low
     * enough that a deliberate direction change is picked up on the tick its weight passes it. The pose does not depend on
     * this number - it is a blend of all four clips - so the only thing it controls is which state the guards run for.</p>
     */
    private static final double DOMINANT_DIRECTION = 0.35;

    /**
     * Whether every locomotion state change is written to the log.
     *
     * <p>Off by default and enabled with {@code -Darg_weaponry.debug_locomotion=true}, the same convention the reference uses
     * for its own walking debug flag ({@code pointblank.debug.walking}). It exists because a state machine this quiet is
     * otherwise impossible to verify in a running game.</p>
     */
    private static final boolean DEBUG_LOCOMOTION = Boolean.getBoolean("arg_weaponry.debug_locomotion");

    private final Map<String, BedrockAnimation> clips = new LinkedHashMap<>();

    private final LocomotionStateMachine machine = new LocomotionStateMachine();

    /** The shared walking phase, in seconds; all four walking clips are sampled with it. */
    private double walkPhase;

    private double prepareTime;
    private double runTime;
    private double completeTime;
    private double airTime;

    /**
     * The landing reaction that is playing, or {@code null} when none is.
     *
     * <p>A reaction, not a state: the state machine never sees it, so the walking/running/sprint decisions it makes during a
     * landing are exactly the ones it would make without one. It starts on the single edge from an airborne state to a ground
     * state, plays once, and blends back into whatever the machine chose.</p>
     */
    private String landClip;

    /** How long the landing reaction has been playing, in seconds of real time. */
    private double landTime;

    /**
     * The pose the landing reaction replaces, captured on the touch-down edge.
     *
     * <p>It is the pose the jump actually ended on - after the airborne vertical response, not the clip's authored hold -
     * and is the anchor the reaction's vertical translation is re-based onto.</p>
     */
    private WeaponPose landEntryPose;

    /**
     * The landing clip's own first frame, sampled once when the reaction starts.
     *
     * <p>Subtracting it from every later sample removes the "continue the airborne hold" part of the authored clip while
     * keeping its dip and its tail.</p>
     */
    private WeaponPose landAnchorPose;

    /** The smoothed longitudinal input, {@code -1} backward to {@code 1} forward. */
    private double smoothedForward;

    /** The smoothed lateral input, {@code -1} left to {@code 1} right. */
    private double smoothedRight;

    /** The layer's fade weight, {@code 0..1}, as the current tick resolved it. */
    private double weight;

    /**
     * The weight at the start of the current tick, so a frame can be drawn <em>between</em> the two.
     *
     * <p>{@link #advanceWeight} resolves the fade once per tick, and a fade that covers half its range per tick is a 20 Hz
     * staircase if its tick value is applied to every frame: the weapon steps part of the way into an action on the tick and
     * then holds that value for the rest of the tick's frames. Interpolating from the value at the start of the tick to the
     * value it resolved - {@link #weightAt(double)} - turns the same fade into a continuous ramp, and it means a target change
     * (an action starting, an action ending) can only change the slope, never the value.</p>
     */
    private double previousWeight;

    /** The direction weights at the start of the current tick, for the same reason as {@link #previousWeight}. */
    private double previousSmoothedForward;
    private double previousSmoothedRight;

    /**
     * How much of the authored airborne vertical motion is currently applied, in {@code [0, 1]}.
     *
     * <p>Driven by the player's real vertical velocity, not by a clock: {@code 1} while the player is rising at the speed a
     * jump starts with, and {@code 0} at the apex, during a fall and after touch-down. This is what keeps a jump from
     * translating the weapon upwards for its whole duration - see {@link #JUMP_TAKEOFF_SPEED}.</p>
     */
    private double verticalResponse;

    /** The vertical response at the start of the current tick, so a frame can interpolate it. */
    private double previousVerticalResponse;

    /**
     * Whether an airborne sample has carried a real vertical velocity since the player left the ground.
     *
     * <p>A caller that only supplies a {@link MovementState} with the legacy constructors reports {@code 0} for the vertical
     * velocity because it has no player to read it from (the animation tester, an older call path, a headless test). Such a
     * caller must keep the authored airborne pose in full - it has not asked for the new response and it cannot distinguish
     * a take-off from an apex. A real client always supplies the value, so the first non-zero sample opts the layer into the
     * response for the rest of that airborne period.</p>
     */
    private boolean airborneVelocityObserved;

    /** The length of the tick this layer last advanced by, i.e. the span a sub-tick offset is a fraction of. */
    private double tickSeconds;

    /** The smoothed playback-rate multiplier. */
    private double speedScale = 1.0;

    /** How long ago the overlay was last gated off by an action, in seconds. */
    private double sinceActionGate = Double.MAX_VALUE;

    /** Whether the overlay was allowed on the previous tick, so the action gate runs from its falling edge. */
    private boolean lastOverlayAllowed = true;

    /**
     * The state whose controller is fading out, or {@code null} when no cross-fade is running.
     *
     * <p>This is the pose <em>source</em> of the fade, not a pose: the outgoing controller is sampled again on every frame,
     * at the same render timestamp as the incoming one, so both sides of the blend keep moving. The previous implementation
     * stored one frozen {@link WeaponPose} instead, which stalled the outgoing motion for the length of the fade and then
     * made the incoming motion catch up - the "the bob hitches when the state changes" report. See {@link #pose(double)}.</p>
     */
    private LocomotionState outgoingState;
    private double transitionElapsed;
    private double transitionDuration;

    /** The pose held while airborne when the pack authors no airborne clip, or {@code null}. */
    private WeaponPose frozenPose;

    /**
     * The sprinting gait pose captured on the take-off edge and held for the whole airborne period.
     *
     * <p>It is a snapshot, not a clock: the run cycle is frozen in the air, but the ground {@code RUNNING} state advances the
     * same {@code runTime} clock on the touch-down tick. Re-sampling it there would move the carry by a whole run frame at the
     * exact moment the airborne pose is still being faded out, which is a visible snap on landing. Holding the take-off pose
     * makes the airborne contribution independent of what the ground clock does.</p>
     */
    private WeaponPose sprintCarryPose;

    /** The state the previous update ended in, so an entry can be detected. */
    private LocomotionState state = LocomotionState.NONE;

    /**
     * Resolves the locomotion clips of one animation resource.
     *
     * @param file the weapon's animation document, may be {@code null}
     * @return whether at least one locomotion clip was found
     */
    public boolean bind(AnimationFile file) {
        clips.clear();
        reset();
        if (file == null || file.animations() == null) {
            return false;
        }
        for (String suffix : new String[]{WALK_FORWARD, WALK_BACKWARD, WALK_LEFT, WALK_RIGHT, WALKING, WALK_AIMING,
                RUN_START, RUN, RUN_HOLD, RUN_END, OFF_GROUND, OFF_GROUND_SPRINTING,
                JUMP_START, JUMP_LOOP, SPRINT_JUMP_START, SPRINT_JUMP_LOOP, LAND, SPRINT_LAND}) {
            BedrockAnimation clip = find(file.animations(), suffix);
            if (clip != null) {
                clips.put(suffix, clip);
            }
        }
        return !clips.isEmpty();
    }

    /**
     * Finds one clip by its documented suffix.
     *
     * @param animations the document's clips
     * @param suffix     the suffix to look for
     * @return the clip, or {@code null} when the document declares none
     */
    private static BedrockAnimation find(Map<String, BedrockAnimation> animations, String suffix) {
        BedrockAnimation exact = animations.get(suffix);
        if (exact != null) {
            return exact;
        }
        BedrockAnimation best = null;
        String bestName = null;
        for (Map.Entry<String, BedrockAnimation> entry : animations.entrySet()) {
            String name = entry.getKey();
            if (name == null || !name.endsWith("." + suffix)) {
                continue;
            }
            if (bestName == null || name.length() < bestName.length()) {
                best = entry.getValue();
                bestName = name;
            }
        }
        return best;
    }

    /**
     * @param suffix one of the documented suffixes
     * @return whether that clip exists in the bound resource
     */
    public boolean has(String suffix) {
        return clips.containsKey(suffix);
    }

    /** @return whether no locomotion clip is bound, so the layer can never produce a pose. */
    public boolean isEmpty() {
        return clips.isEmpty();
    }

    /** Forgets every timeline and the current state, e.g. when the weapon or the animation resource changes. */
    public void reset() {
        machine.reset();
        state = LocomotionState.NONE;
        walkPhase = 0.0;
        prepareTime = 0.0;
        runTime = 0.0;
        completeTime = 0.0;
        airTime = 0.0;
        smoothedForward = 0.0;
        smoothedRight = 0.0;
        weight = 0.0;
        speedScale = 1.0;
        sinceActionGate = Double.MAX_VALUE;
        lastOverlayAllowed = true;
        previousWeight = 0.0;
        previousSmoothedForward = 0.0;
        previousSmoothedRight = 0.0;
        verticalResponse = 0.0;
        previousVerticalResponse = 0.0;
        airborneVelocityObserved = false;
        tickSeconds = 0.0;
        clearTransition();
        frozenPose = null;
        sprintCarryPose = null;
        landClip = null;
        landTime = 0.0;
        landEntryPose = null;
        landAnchorPose = null;
    }

    /**
     * A locomotion track's state at one instant, so a weapon switch can continue it instead of restarting it.
     *
     * <p>It is deliberately a value: {@code VisualWeapon} disposes the outgoing weapon's animator (which resets its layer)
     * before the incoming weapon binds its own clips, so a live reference would already be empty by the time it could be
     * used. Holding the numbers instead makes the transfer independent of both weapons' lifetimes.</p>
     *
     * <p>The transition is <strong>not</strong> part of it: a fade belongs to the weapon that started it, and carrying it
     * would mean blending a pose the incoming weapon never drew.</p>
     *
     * @param state             the state machine's state
     * @param walkPhase         the shared walking phase
     * @param prepareTime       {@code run_start}'s playback time
     * @param runTime           {@code run}'s playback time
     * @param completeTime      {@code run_end}'s playback time
     * @param airTime           the airborne clock
     * @param smoothedForward   the smoothed longitudinal direction weight
     * @param smoothedRight     the smoothed lateral direction weight
     * @param weight            the overlay's own fade weight
     * @param speedScale        the smoothed playback-rate multiplier
     * @param sinceActionGate   how long ago an action released the pose
     * @param lastOverlayAllowed whether the overlay was allowed on the last tick
     * @param frozenPose        the pose held by a state without a clip, may be {@code null}
     * @param landClip          the landing reaction clip that is playing, may be {@code null}
     * @param landTime          how long that reaction has been playing, in seconds
     */
    public record Timeline(LocomotionState state, double walkPhase, double prepareTime, double runTime,
                           double completeTime, double airTime, double smoothedForward, double smoothedRight,
                           double weight, double speedScale, double sinceActionGate, boolean lastOverlayAllowed,
                           WeaponPose frozenPose, String landClip, double landTime) {
    }

    /**
     * @return this layer's timeline, so another weapon can continue it
     */
    public Timeline timeline() {
        return new Timeline(state, walkPhase, prepareTime, runTime, completeTime, airTime, smoothedForward,
                smoothedRight, weight, speedScale, sinceActionGate, lastOverlayAllowed, frozenPose, landClip, landTime);
    }

    /**
     * Continues a timeline another weapon produced.
     *
     * <p>Everything that decides <em>where</em> the locomotion is - the phase, the direction weights, the playback rate and
     * the overlay weight - is adopted; no pose is composed twice and no cross-fade is carried. The clips stay this layer's
     * own, because the two weapons may author different ones; that is also why the phase is the only thing that has to be
     * shared for the switch to be invisible.</p>
     *
     * @param timeline the timeline to continue, {@code null} is ignored
     */
    public void restore(Timeline timeline) {
        if (timeline == null) {
            return;
        }
        state = timeline.state();
        machine.seed(timeline.state());
        walkPhase = timeline.walkPhase();
        prepareTime = timeline.prepareTime();
        runTime = timeline.runTime();
        completeTime = timeline.completeTime();
        airTime = timeline.airTime();
        smoothedForward = timeline.smoothedForward();
        smoothedRight = timeline.smoothedRight();
        weight = timeline.weight();
        speedScale = timeline.speedScale();
        sinceActionGate = timeline.sinceActionGate();
        lastOverlayAllowed = timeline.lastOverlayAllowed();
        frozenPose = timeline.frozenPose();
        landClip = timeline.landClip();
        landTime = timeline.landTime();
        // The first frame of the new weapon shows exactly the pose the old one ended on: nothing is interpolated across the
        // switch, and the next tick computes its own direction and fade from there.
        previousWeight = timeline.weight();
        previousSmoothedForward = timeline.smoothedForward();
        previousSmoothedRight = timeline.smoothedRight();
        tickSeconds = 0.0;
        clearTransition();
    }

    /**
     * Advances the layer by one client tick with the overlay enabled and no weapon action owning the pose.
     *
     * @param movement     the player's movement, may be {@code null}
     * @param deltaSeconds the time the tick covered
     */
    public void update(MovementState movement, double deltaSeconds) {
        update(movement, deltaSeconds, true, false, false);
    }

    /**
     * Advances the layer by one client tick.
     *
     * @param movement       the player's movement, may be {@code null}
     * @param deltaSeconds   the time the tick covered
     * @param overlayAllowed whether the overlay may contribute right now; {@code false} while a higher-priority weapon
     *                       action owns the pose. The timelines keep advancing either way, so the movement resumes where it
     *                       was instead of restarting
     */
    public void update(MovementState movement, double deltaSeconds, boolean overlayAllowed) {
        update(movement, deltaSeconds, overlayAllowed, false, false);
    }

    /**
     * Advances the layer by one client tick.
     *
     * <p>The state machine runs every tick, including while an action owns the pose: the reference keeps its walking
     * controller updated during fire, reload and aim as well, and only the <em>contribution</em> of the overlapping
     * animation is what an action replaces. Because the timelines keep running, an action that ends mid-stride returns to the
     * stride instead of to its first frame.</p>
     *
     * @param movement       the player's movement, may be {@code null}
     * @param deltaSeconds   the time the tick covered
     * @param overlayAllowed whether the overlay may contribute right now
     * @param aiming         whether the weapon is aiming, which selects the aiming walk pose
     * @param firing         whether a shot clip is playing, which suppresses starting a sprint
     */
    public void update(MovementState movement, double deltaSeconds, boolean overlayAllowed, boolean aiming,
                       boolean firing) {
        MovementState current = movement == null ? MovementState.STILL : movement;
        if (clips.isEmpty()) {
            return;
        }
        double delta = Double.isFinite(deltaSeconds)
                ? Math.max(0.0, Math.min(MAX_DELTA_SECONDS, deltaSeconds)) : 0.0;
        tickSeconds = delta;
        // The values at the start of this tick, so a frame can be drawn between them and the ones this tick resolves.
        previousWeight = weight;
        previousSmoothedForward = smoothedForward;
        previousSmoothedRight = smoothedRight;
        previousVerticalResponse = verticalResponse;

        // Advance fades that were already running *before* this tick's state machine runs. A fade started by this tick
        // therefore begins at elapsed 0 on the first rendered frame and takes its full duration, instead of being advanced
        // instantly by one tick and starting halfway through (the one-tick snap at every sprint-jump take-off and
        // touch-down). The same applies to the landing reaction below.
        advanceTransition(delta);
        advanceLanding(delta);

        LocomotionIntent intent = current.intent();

        // The pose that is on screen right now, captured *before* the state machine can change it and before the direction
        // weights move: a cross-fade has to start from what the player was looking at.
        WeaponPose outgoing = pose(0.0);
        advanceInput(current, intent, delta, overlayAllowed);

        // The authored airborne displacement follows the player's real upward movement, not the airborne clock. Only the
        // rising half of a jump maps to a response: at the apex, while falling and on the ground the weapon settles onto the
        // gait it left with, so no clip can lift it independently of the player. The response is smoothed so a single noisy
        // velocity sample - the touch-down tick, a ladder, water - cannot make it jump between frames.
        boolean airborne = current.airborne();
        double verticalVelocity = current.verticalVelocity();
        if (!airborne) {
            airborneVelocityObserved = false;
        } else if (Math.abs(verticalVelocity) > VERTICAL_VELOCITY_EPSILON) {
            airborneVelocityObserved = true;
        } else if (!airborneVelocityObserved) {
            // A caller that does not report vertical velocity (the animation tester, an older call path, a headless test)
            // cannot distinguish take-off from apex, so it keeps the authored pose in full, exactly as before. A real client
            // always reports it, and the first non-zero sample opts into the response for the rest of the jump.
            verticalResponse = 1.0;
            previousVerticalResponse = 1.0;
        }
        double verticalTarget = !airborne ? 0.0
                : airborneVelocityObserved
                        ? AnimationBlending.clamp01(verticalVelocity / JUMP_TAKEOFF_SPEED)
                        : 1.0;
        verticalResponse = AnimationBlending.approach(verticalResponse, verticalTarget, delta,
                VERTICAL_RESPONSE_SECONDS);

        LocomotionState previous = state;
        LocomotionState next = machine.update(factsFor(current, intent, aiming, firing));
        if (next != previous) {
            enter(previous, next, outgoing);
        }
        state = next;

        advanceClocks(next, delta);
        advanceWeight(overlayAllowed, delta);
    }

    /**
     * Builds one tick of state-machine facts.
     *
     * <p>The intent is the primary source; the analog vector is used as a fallback so a caller that only has a
     * {@link MovementState} (the animation tester, an older code path) still moves the machine.</p>
     *
     * @param movement the player's movement
     * @param intent   its intent
     * @param aiming   whether the weapon aims
     * @param firing   whether a shot clip plays
     * @return the facts, never {@code null}
     */
    private LocomotionStateMachine.Facts factsFor(MovementState movement, LocomotionIntent intent, boolean aiming,
                                                  boolean firing) {
        boolean moving = movement.moving();
        // The *direction* a locomotion state is chosen for is the smoothed dominant direction, not the raw key: the pose is a
        // weighted blend of four clips, so it only leans towards a direction once that direction's weight is real. Reading
        // the raw keys here made rapid strafing alternate WALKING_LEFT/WALKING_RIGHT every tick, starting a cross-fade per
        // tick - the state name flickered although the pose did not. The *intent* stays raw, so leaving and entering walking
        // is still immediate.
        boolean forward = smoothedForward > DOMINANT_DIRECTION;
        boolean backward = smoothedForward < -DOMINANT_DIRECTION;
        boolean left = smoothedRight < -DOMINANT_DIRECTION;
        boolean right = smoothedRight > DOMINANT_DIRECTION;
        if (!forward && !backward && !left && !right) {
            // No weight has accumulated yet (the first tick of a movement, or a legacy caller that only supplies an analog
            // vector): fall back to the requested direction so a single-tick press is not dropped.
            forward = intent.forwardKey() || movement.forward() > 0.0;
            backward = intent.backwardKey() || movement.forward() < 0.0;
            left = intent.leftKey() || movement.right() < 0.0;
            right = intent.rightKey() || movement.right() > 0.0;
        }
        // Aiming and sprinting are mutually exclusive. The player controller and the sprint mixins stop a real sprint, and
        // these two flags are the animation-side half of the same rule: a held sprint key, a stale stabiliser grace or a
        // second mod setting the entity flag cannot make any run clip start, continue or blend in while ADS is active.
        boolean sprinting = movement.sprinting() && !aiming;
        boolean sprintIntent = (intent.sprintIntent() || movement.sprinting()) && !aiming;
        return new LocomotionStateMachine.Facts(true,
                intent.movementIntent() || moving,
                sprintIntent,
                forward, backward, left, right,
                moving, !movement.airborne(), sprinting, intent.slow(), aiming, firing,
                intent.offGroundAllowed(), sinceActionGate <= POST_ACTION_GRACE_SECONDS,
                isFinished(LocomotionState.PREPARE_RUNNING), isFinished(LocomotionState.COMPLETE_RUNNING));
    }

    /**
     * Advances the direction weights, the playback-rate multiplier and the post-action gate.
     *
     * @param movement       the player's movement
     * @param intent         its intent
     * @param delta          the clamped tick time
     * @param overlayAllowed whether the overlay may contribute right now
     */
    private void advanceInput(MovementState movement, LocomotionIntent intent, double delta,
                              boolean overlayAllowed) {
        if (movement.moving()) {
            // A direction change is a weight change, never a clip change: the four clips share one phase, so a diagonal or
            // a reversal passes through intermediate poses instead of switching animations.
            smoothedForward = AnimationBlending.approach(smoothedForward, movement.forward(), delta,
                    DIRECTION_BLEND_SECONDS);
            smoothedRight = AnimationBlending.approach(smoothedRight, movement.right(), delta,
                    DIRECTION_BLEND_SECONDS);
        }
        // Not moving, but still in a walking state (pushing a wall): the last direction is held, exactly like the
        // reference holds its walking clip while the state does not change.
        speedScale = AnimationBlending.approach(speedScale, intent.speedScale(), delta, SPEED_SCALE_BLEND_SECONDS);
        // The post-action grace runs from the moment an action *starts* owning the pose, not for as long as it lasts: a
        // reload that takes two seconds must not keep the locomotion state machine in "just drew" for two seconds. The
        // reference's equivalent gate is the 90 ms after its drawing flag became true.
        sinceActionGate = !overlayAllowed && lastOverlayAllowed
                ? 0.0
                : Math.min(Double.MAX_VALUE, sinceActionGate + delta);
        lastOverlayAllowed = overlayAllowed;
    }

    /**
     * Handles the entry into a state: which clock it starts at, and how long the cross-fade is.
     *
     * <h2>Running-end carryover</h2>
     * <p>{@code run_start} and {@code run_end} are authored as mirror images of each other: the first raises the weapon from
     * rest into the running pose, the second lowers it back. When a sprint is released during the prepare phase, the
     * reference starts {@code runningend} at the <em>complement</em> of the prepare phase
     * ({@code computeRunningEndCarryoverOffsetFraction} returns {@code 1 - currentTime / length}), so the pose at the switch
     * instant is the pose the run-end passes through. Playing it from zero instead would snap the weapon back to the running
     * pose first - the "sprint abort jump" this method removes.</p>
     *
     * @param previous the state the layer was in
     * @param next     the state it entered
     * @param outgoing the pose that was on screen before this tick, may be {@code null}
     */
    private void enter(LocomotionState previous, LocomotionState next, WeaponPose outgoing) {
        switch (next) {
            case WALKING, WALKING_AIMING, WALKING_BACKWARD, WALKING_LEFT, WALKING_RIGHT -> {
                // A new walk starts at the authored first frame; a landing resumes the frozen stride, a direction change keeps
                // the phase it already has, and leaving the running-end recovery keeps it too: the player was walking before the
                // sprint, so restarting the cycle at frame zero there would be a visible snap at the end of every sprint.
                if (!previous.isWalking() && previous != LocomotionState.OFF_GROUND
                        && previous != LocomotionState.COMPLETE_RUNNING) {
                    walkPhase = 0.0;
                }
            }
            case PREPARE_RUNNING -> prepareTime = 0.0;
            case RUNNING -> {
                if (previous != LocomotionState.OFF_GROUND_SPRINTING) {
                    // A fresh run starts at its first frame; a landing continues the cycle it left.
                    runTime = 0.0;
                }
            }
            case COMPLETE_RUNNING -> {
                if (previous == LocomotionState.PREPARE_RUNNING) {
                    double reached = AnimationBlending.clamp01(fraction(prepareTime, length(RUN_START)));
                    completeTime = (1.0 - reached) * length(RUN_END);
                } else {
                    completeTime = 0.0;
                }
            }
            case OFF_GROUND, OFF_GROUND_SPRINTING -> {
                // The airborne clock belongs to the jump, not to the state name: a jump that turns into a sprint-jump (or back)
                // keeps the phase it has instead of replaying the take-off pose.
                if (!previous.isOffGround()) {
                    airTime = 0.0;
                    // Snapshot the sprinting gait before any clock advances. It is the pose the airborne overlay settles
                    // onto, and it must not change while the ground RUNNING clock is alive again on the landing tick.
                    sprintCarryPose = next == LocomotionState.OFF_GROUND_SPRINTING ? runningCarry() : null;
                }
                // Held only if the pack authors no airborne clip for this state, so a jump never loses the weapon pose.
                frozenPose = outgoing;
                // A new jump keeps the landing reaction alive for the length of the take-off fade: the outgoing ground pose
                // must still include it (with the same re-based anchor) or the first frame of the jump would step from the
                // landing pose to the run pose. It cannot overlay the airborne pose itself - overlayLanding is gated by the
                // state that produced the pose - and the next touch-down replaces it through startLanding.
            }
            case NONE, STANDING -> {
                // The idle base clip owns the pose from here, but the layer still has to fade *out* of the walk it was
                // playing: keeping the last pose as the source of that fade is what stops a walk from being cut to idle.
                frozenPose = outgoing;
            }
        }
        if (previous.isOffGround() && !next.isOffGround() && next != LocomotionState.NONE) {
            // The single touch-down edge. `airTime` still holds the airtime that just ended, which is what decides whether
            // this was a jump or a step down a slab.
            startLanding(previous == LocomotionState.OFF_GROUND_SPRINTING, outgoing);
        }
        double duration = next == LocomotionState.COMPLETE_RUNNING || previous == LocomotionState.COMPLETE_RUNNING
                ? COMPLETE_TRANSITION_SECONDS : TRANSITION_SECONDS;
        startTransition(previous, next, outgoing, duration);
        if (DEBUG_LOCOMOTION) {
            com.arg_studios.arg_weaponry.Constants.LOG.info(
                    "[ARG] Locomotion {} -> {} (walk {}s, prepare {}s, run {}s, complete {}s, speed x{})",
                    previous, next, walkPhase, prepareTime, runTime, completeTime, speedScale);
        }
    }

    /**
     * Advances the clocks the current state uses.
     *
     * <p>A clock that the current state does not use is not advanced and not reset, which is what makes the walk survive a
     * jump and the run survive a sprint-jump: the state that comes back finds its phase where it left it.</p>
     *
     * @param state the state this tick is in
     * @param delta the clamped tick time
     */
    private void advanceClocks(LocomotionState state, double delta) {
        // Every clock advances at the rate it plays at, and the renderer scales its partial-tick offset by the same rate (see
        // pose), which is what keeps one timeline across a tick boundary for each of them.
        advanceClock(state, delta * clockRate(state));
        if (outgoingState != null && controllerOf(outgoingState) != controllerOf(state)) {
            // Controller-to-controller blending: while the fade runs, the outgoing controller keeps playing, so the pose it
            // contributes is a live sample and not a snapshot of the instant the state changed.
            advanceClock(outgoingState, delta * clockRate(outgoingState));
        }
    }

    /**
     * The rate one state's clock advances at.
     *
     * <p>A gait plays at the player's speed - a walk that speeds up and slows down with them is what makes the bob match the
     * footsteps. A <em>transition</em> does not: {@code run_end} is a recovery the player is already decelerating through, and
     * the jump and landing clips are reactions to an event, so they all play at their authored speed. Deriving their rate from
     * the movement made the sprint release feel sluggish and stretched the state's lifetime with it.</p>
     *
     * @param which the state whose clock rate is asked for
     * @return the multiplier that state's clock advances at
     */
    private double clockRate(LocomotionState which) {
        return switch (which) {
            case COMPLETE_RUNNING -> RUN_END_PLAYBACK_RATE;
            case OFF_GROUND, OFF_GROUND_SPRINTING -> JUMP_PLAYBACK_RATE;
            case NONE, STANDING, WALKING, WALKING_AIMING, WALKING_BACKWARD, WALKING_LEFT, WALKING_RIGHT,
                 PREPARE_RUNNING, RUNNING -> speedScale;
        };
    }

    /**
     * Starts the landing reaction, if the pack authors one and this really was a jump.
     *
     * @param sprinting whether the state that just ended was the sprinting airborne one
     * @param entryPose the pose the airborne state was drawing when it ended, may be {@code null}
     */
    private void startLanding(boolean sprinting, WeaponPose entryPose) {
        landClip = null;
        landTime = 0.0;
        landEntryPose = null;
        landAnchorPose = null;
        if (airTime < LANDING_MIN_AIR_SECONDS) {
            // A step down a half block, a slab or a stair: no reaction, just the ordinary cross-fade.
            return;
        }
        String clip = sprinting && has(SPRINT_LAND) ? SPRINT_LAND : LAND;
        if (!has(clip) || !(length(clip) > 0.0)) {
            // Nothing authored: the existing behaviour is a plain cross-fade into the next locomotion state.
            return;
        }
        landClip = clip;
        landEntryPose = entryPose;
        // Sample the clip's own first frame once. Its opening height is the airborne hold it was authored to continue;
        // subtracting it lets the reaction start exactly where the jump actually ended (see overlayLanding).
        landAnchorPose = sample(clip, 0.0);
    }

    /**
     * Advances the landing reaction and ends it once its clip is over.
     *
     * @param delta the clamped tick time, in seconds of real time
     */
    private void advanceLanding(double delta) {
        if (landClip == null) {
            return;
        }
        landTime += Math.max(0.0, delta);
        if (landTime >= length(landClip)) {
            landClip = null;
            landTime = 0.0;
            landEntryPose = null;
            landAnchorPose = null;
        }
    }

    /**
     * Advances the clock one state uses.
     *
     * <p>A clock that is not used by that state is neither advanced nor reset, which is what makes a phase survive the
     * states that do not sample it: the walk cycle is frozen while airborne and resumes on landing, and the run cycle of a
     * sprint-jump is picked up where it was left.</p>
     *
     * @param which the state whose clock should advance
     * @param step  the tick length already multiplied by the playback rate, in seconds
     */
    private void advanceClock(LocomotionState which, double step) {
        switch (which) {
            case WALKING, WALKING_AIMING, WALKING_BACKWARD, WALKING_LEFT, WALKING_RIGHT -> walkPhase += step;
            case PREPARE_RUNNING -> prepareTime += step;
            case RUNNING -> {
                runTime += step;
                double loop = length(RUN);
                if (loop > 0.0 && isLooping(RUN)) {
                    // Keep the clock bounded for a looping clip; the sampler wraps as well, this only protects the double's
                    // precision in a long session.
                    runTime -= loop * Math.floor(runTime / loop);
                }
            }
            case COMPLETE_RUNNING -> completeTime += step;
            case OFF_GROUND, OFF_GROUND_SPRINTING -> airTime += step;
            case NONE, STANDING -> {
                // No clock: the idle base clip owns this pose.
            }
        }
    }

    /**
     * Starts a cross-fade between two animation controllers, if this state change is one.
     *
     * <h2>What a transition is, and what it is not</h2>
     * <p>A fade is only needed when the new state samples <em>different clips</em>. The four walking states all sample the
     * same directional blend at the same shared phase, and {@link LocomotionState#WALKING_AIMING} samples that very blend
     * when the pack authors no {@code walk_aiming} clip, so {@code WALKING -> WALKING_RIGHT} and
     * {@code WALKING -> WALKING_AIMING} are pose-identical changes. Fading between two identical samples does not smooth
     * anything: it freezes the phase for the length of the fade and then makes it catch up, which is audible in the
     * footsteps and visible in the bob. {@link #controllerOf} answers "which controller does this state sample", and only a
     * change of that answer starts a fade.</p>
     *
     * @param previous        the state the layer was in
     * @param next            the state it entered
     * @param outgoing        the pose that was on screen before this tick, used only to decide whether a fade is needed
     * @param durationSeconds the fade length
     */
    private void startTransition(LocomotionState previous, LocomotionState next, WeaponPose outgoing,
                                 double durationSeconds) {
        if (outgoing == null || outgoing.isEmpty() || !(durationSeconds > 0.0)
                || controllerOf(previous) == controllerOf(next)) {
            clearTransition();
            return;
        }
        outgoingState = previous;
        transitionElapsed = 0.0;
        transitionDuration = durationSeconds;
    }

    /** Forgets the running cross-fade. */
    private void clearTransition() {
        outgoingState = null;
        transitionElapsed = 0.0;
        transitionDuration = 0.0;
    }

    /** Advances the running cross-fade and clears it once it finished. */
    private void advanceTransition(double delta) {
        if (outgoingState == null) {
            return;
        }
        transitionElapsed += delta;
        if (transitionDuration <= 0.0 || transitionElapsed >= transitionDuration) {
            clearTransition();
        }
    }

    /**
     * Moves the layer's own weight towards its target and clears the held airborne pose once it is not needed.
     *
     * @param overlayAllowed whether the overlay may contribute right now
     * @param delta          the clamped tick time
     */
    private void advanceWeight(boolean overlayAllowed, double delta) {
        boolean wanted = overlayAllowed && contributesPose();
        if (delta > 0.0) {
            weight = AnimationBlending.moveTowards(weight, wanted ? 1.0 : 0.0, delta, FADE_SECONDS);
        }
    }

    /**
     * @return whether the layer has anything to contribute right now: a pose from its state, or a landing reaction (which also
     *         runs while the state is {@link LocomotionState#STANDING}, where no locomotion pose exists)
     */
    private boolean contributesPose() {
        return landClip != null || (state.contributes() && currentPose(0.0) != null);
    }

    /**
     * @param state the state to test
     * @return whether the one-shot clip of that state has played out, or does not exist
     */
    private boolean isFinished(LocomotionState state) {
        return switch (state) {
            case PREPARE_RUNNING -> !has(RUN_START) || prepareTime >= length(RUN_START);
            case COMPLETE_RUNNING -> !has(RUN_END) || completeTime >= length(RUN_END);
            default -> true;
        };
    }

    /**
     * The animation controller a state samples.
     *
     * <p>Two states with the same controller id are pose-identical changes: they sample the same clips at the same phase, so
     * no cross-fade is started between them. The airborne states report the controller they fall back to, which is what makes
     * a jump a continuation (frozen phase) instead of a fade to a different pose, and a landing a continuation back.</p>
     *
     * @param which the state to classify
     * @return the controller identity of that state
     */
    private LocomotionState controllerOf(LocomotionState which) {
        return switch (which) {
            case WALKING, WALKING_BACKWARD, WALKING_LEFT, WALKING_RIGHT -> LocomotionState.WALKING;
            case WALKING_AIMING -> has(WALK_AIMING) ? LocomotionState.WALKING_AIMING : LocomotionState.WALKING;
            // An authored airborne clip - the jumping ladder or Point Blank's single off_ground pose - is its own controller,
            // so take-off and touch-down cross-fade into and out of it. Without one the walk blend is sampled at the frozen
            // phase, so a jump continues the walk controller instead of fading to a different pose.
            case OFF_GROUND -> hasAnyJumpClip() || has(OFF_GROUND) ? LocomotionState.OFF_GROUND
                    : hasAnyWalkClip() ? LocomotionState.WALKING : LocomotionState.OFF_GROUND;
            // Same rule for the sprint: the run cycle is held at the phase the player left the ground with.
            case OFF_GROUND_SPRINTING -> hasAnyJumpClip() || has(OFF_GROUND_SPRINTING)
                    ? LocomotionState.OFF_GROUND_SPRINTING
                    : has(RUN) ? LocomotionState.RUNNING : LocomotionState.OFF_GROUND_SPRINTING;
            case NONE, STANDING, PREPARE_RUNNING, RUNNING, COMPLETE_RUNNING -> which;
        };
    }

    /** @return whether the pack authors any of the jumping clips, so the airborne pose is not the held gait. */
    private boolean hasAnyJumpClip() {
        return has(JUMP_START) || has(JUMP_LOOP) || has(SPRINT_JUMP_START) || has(SPRINT_JUMP_LOOP);
    }

    /** @return whether the pack authors at least one of the walking clips. */
    private boolean hasAnyWalkClip() {
        return has(WALK_FORWARD) || has(WALK_BACKWARD) || has(WALK_LEFT) || has(WALK_RIGHT) || has(WALKING);
    }

    /**
     * Samples the layer for one frame.
     *
     * <h4>One time base</h4>
     * <p>The sub-tick offset is scaled by the rate the sampled state's clock advances at ({@link #clockRate}): a gait moves
     * through its clip at {@link #speedScale}, while the run-end recovery and the jumping clips move at their own authored
     * rate. A clip playing at 1.3x covers 1.3x the animation time per real second, so a frame 60% of the way through a tick
     * has to sample 60% of {@code 1.3} tick lengths past the tick's phase. Sampling the raw offset instead makes the pose
     * advance at one speed inside a tick and jump at every boundary by {@code tick * |rate - 1|} - a 20 Hz discontinuity
     * whose size grows with the playback rate.</p>
     *
     * <h4>Controller-to-controller blending</h4>
     * <p>When a fade is running, the outgoing state is sampled again at this same timestamp rather than reusing a stored
     * pose, and both samples are interpolated by the eased fade position. The fade's own clock is not scaled: it measures
     * real time, not animation time.</p>
     *
     * @param offsetSeconds the frame's sub-tick offset, in seconds of real time
     * @return the overlay pose, or {@code null} when the layer contributes nothing
     */
    public WeaponPose pose(double offsetSeconds) {
        if (clips.isEmpty() || (weight <= 0.0 && previousWeight <= 0.0)) {
            // Nothing to overlay, or the fade is out at both ends of this tick: the base pose is the answer either way.
            return null;
        }
        double offset = Double.isFinite(offsetSeconds) ? offsetSeconds : 0.0;
        double fraction = tickFraction(offset);
        double forward = previousSmoothedForward + (smoothedForward - previousSmoothedForward) * fraction;
        double right = previousSmoothedRight + (smoothedRight - previousSmoothedRight) * fraction;
        double response = previousVerticalResponse + (verticalResponse - previousVerticalResponse) * fraction;
        // Each clock is sampled on its own time base: the gait at the player's speed, the run-end and the jump at their
        // authored speed. See clockRate.
        WeaponPose current = overlayLanding(poseOf(state, offset * clockRate(state), forward, right, response), offset,
                state);
        if (outgoingState == null || transitionDuration <= 0.0) {
            return current;
        }
        WeaponPose outgoing = overlayLanding(
                poseOf(outgoingState, offset * clockRate(outgoingState), forward, right, response), offset, outgoingState);
        if (outgoing == null || outgoing.isEmpty()) {
            return current;
        }
        if (current == null) {
            return outgoing;
        }
        double elapsed = Math.max(0.0, transitionElapsed + offset);
        float blend = (float) AnimationBlending.smoothstep(elapsed / transitionDuration);
        return WeaponPoseBlend.blend(outgoing, current, blend);
    }

    /**
     * Merges the landing reaction into the pose of a ground state.
     *
     * <p>The reaction is an overlay, not a state: the machine keeps deciding between standing, walking and running underneath it,
     * so an action, a direction change or a sprint during the reaction behaves exactly as it would without one. The clip plays
     * once from the touch-down frame, holds its last frame, and its tail blends back into the locomotion pose so the reaction
     * cannot end with a step. While airborne, or when the pack authors no landing clip, this is the identity.</p>
     *
     * @param current the pose the current state produced, may be {@code null}
     * @param offset  the frame's sub-tick offset, in seconds of real time
     * @param which   the state that produced {@code current}: the reaction only applies to a ground pose, and asking with the
     *                outgoing state is what lets a take-off fade keep the landing pose that was on screen
     * @return the pose with the landing reaction merged in, exactly {@code current} when there is none
     */
    private WeaponPose overlayLanding(WeaponPose current, double offset, LocomotionState which) {
        if (landClip == null || which == null || which.isOffGround() || which == LocomotionState.NONE) {
            return current;
        }
        double length = length(landClip);
        if (!(length > 0.0)) {
            return current;
        }
        double time = Math.max(0.0, landTime + offset);
        WeaponPose landing = sample(landClip, time);
        if (landing == null) {
            return current;
        }
        landing = rebaseLandingVertical(landing);
        double progress = Math.min(1.0, time / length);
        double releaseStart = Math.max(0.0, 1.0 - LAND_RELEASE_SECONDS / length);
        if (current == null) {
            return landing;
        }
        if (time < LAND_ENTER_SECONDS) {
            // Ease in from the pose the jump ended on: the first frame of the clip is never shown as a step, so neither a
            // short hop nor a long fall can end with the weapon suddenly moving.
            float enter = (float) AnimationBlending.smoothstep(time / LAND_ENTER_SECONDS);
            return WeaponPoseBlend.blend(current, landing, enter);
        }
        if (progress <= releaseStart) {
            return landing;
        }
        double release = Math.max(1.0E-9, 1.0 - releaseStart);
        float blend = (float) AnimationBlending.smoothstep(Math.min(1.0, (progress - releaseStart) / release));
        return WeaponPoseBlend.blend(landing, current, blend);
    }

    /**
     * Re-bases the landing clip's vertical translation onto the pose the airborne state ended on.
     *
     * <p>The shipped landing clips open at the same height as the airborne loop - they were authored to continue it. The
     * airborne pose no longer ends on that height (its vertical translation follows the player's real velocity and settles
     * onto the gait), so playing the landing clip as authored would first lift the weapon back to the old hold and then drop
     * it: an up-then-down jolt on every touch-down. Subtracting the clip's own first frame and adding the entry pose's
     * height removes that opening step while preserving the authored dip; {@link #LAND_VERTICAL_SCALE} controls its depth.
     * Rotations, scale and the lateral/longitudinal translation stay exactly as authored.</p>
     *
     * @param landing the sampled landing pose
     * @return the re-based pose, or the input when there is no entry pose to anchor to
     */
    private WeaponPose rebaseLandingVertical(WeaponPose landing) {
        if (landing == null || landEntryPose == null || landAnchorPose == null) {
            return landing;
        }
        Map<String, SampledBone> bones = new LinkedHashMap<>();
        for (Map.Entry<String, SampledBone> entry : landing.bones().entrySet()) {
            SampledBone bone = entry.getValue();
            Vec3 position = bone.position();
            if (position == null) {
                bones.put(entry.getKey(), bone);
                continue;
            }
            SampledBone anchorBone = landAnchorPose.bone(entry.getKey());
            Vec3 anchor = anchorBone == null ? null : anchorBone.position();
            if (anchor == null) {
                // The clip does not animate this position; nothing to re-base.
                bones.put(entry.getKey(), bone);
                continue;
            }
            SampledBone entryBone = landEntryPose.bone(entry.getKey());
            Vec3 entryPosition = entryBone == null ? null : entryBone.position();
            // A bone the entry pose does not animate keeps the clip's authored vertical value rather than inventing a base.
            double baseY = entryPosition == null ? anchor.y() : entryPosition.y();
            Vec3 rebased = new Vec3(
                    position.x(),
                    (float) (baseY + (position.y() - anchor.y()) * LAND_VERTICAL_SCALE),
                    position.z());
            bones.put(entry.getKey(), new SampledBone(entry.getKey(), bone.rotation(), rebased, bone.scale()));
        }
        return new WeaponPose(landing.animationName(), landing.timeSeconds(), bones, landing.issues());
    }

    /**
     * The overlay weight at a sub-tick offset.
     *
     * <p>The renderer must use this rather than {@link #weight()}. The fade is resolved once per tick, and a fade that covers
     * half its range in one tick would be drawn as a step on the tick and as a constant for every frame after it - the
     * "the weapon snaps into the action" artefact, one tick long and therefore easy to mistake for a stutter. This method
     * interpolates between the weight at the start of the tick and the weight the tick resolved, so the same fade is a
     * continuous ramp across the frames and a change of target can only bend it, never step it.</p>
     *
     * @param offsetSeconds the frame's sub-tick offset, in seconds of real time
     * @return the weight this frame must apply, in {@code [0, 1]}
     */
    public float weightAt(double offsetSeconds) {
        if (!Double.isFinite(offsetSeconds)) {
            return weight();
        }
        double fraction = tickFraction(offsetSeconds);
        double faded = previousWeight + (weight - previousWeight) * fraction;
        if (state.isWalking() && !(state == LocomotionState.WALKING_AIMING && has(WALK_AIMING))) {
            double forward = previousSmoothedForward + (smoothedForward - previousSmoothedForward) * fraction;
            double right = previousSmoothedRight + (smoothedRight - previousSmoothedRight) * fraction;
            faded *= Math.min(1.0, Math.sqrt(forward * forward + right * right));
        }
        return (float) AnimationBlending.clamp01(AnimationBlending.smoothstep(clamp01(faded)));
    }

    /**
     * @param offsetSeconds a sub-tick offset in seconds of real time
     * @return how far into the current tick that offset is, in {@code [0, 1]}
     */
    private double tickFraction(double offsetSeconds) {
        if (!(tickSeconds > 0.0) || !Double.isFinite(offsetSeconds)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, offsetSeconds / tickSeconds));
    }

    /**
     * @param value a weight
     * @return the value clamped into {@code [0, 1]}, {@code NaN} becomes {@code 0}
     */
    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * @param offsetSeconds the sub-tick offset, in seconds of real time
     * @return the pose of the current state, or {@code null} when it has nothing to contribute
     */
    private WeaponPose currentPose(double offsetSeconds) {
        return poseOf(state, offsetSeconds * clockRate(state), smoothedForward, smoothedRight);
    }

    /**
     * Samples one state's controller at a given animation time offset.
     *
     * @param which       the state to sample
     * @param clockOffset the sub-tick offset already scaled to the animation clock
     * @param forward     the longitudinal direction weight to sample with
     * @param right       the lateral direction weight to sample with
     * @return the pose, or {@code null} when that state contributes nothing
     */
    private WeaponPose poseOf(LocomotionState which, double clockOffset, double forward, double right) {
        return poseOf(which, clockOffset, forward, right, verticalResponse);
    }

    /**
     * Samples one state's controller at a given animation time offset, with an explicit vertical response.
     *
     * @param which       the state to sample
     * @param clockOffset the sub-tick offset already scaled to the animation clock
     * @param forward     the longitudinal direction weight to sample with
     * @param right       the lateral direction weight to sample with
     * @param response    how much of the authored airborne vertical motion to apply, in {@code [0, 1]}
     * @return the pose, or {@code null} when that state contributes nothing
     */
    private WeaponPose poseOf(LocomotionState which, double clockOffset, double forward, double right, double response) {
        return switch (which) {
            case NONE, STANDING -> frozenPose;
            case WALKING, WALKING_BACKWARD, WALKING_LEFT, WALKING_RIGHT -> walkingPose(clockOffset, forward, right);
            case WALKING_AIMING -> has(WALK_AIMING)
                    ? sampleOr(WALK_AIMING, walkPhase + clockOffset, () -> walkingPose(clockOffset, forward, right))
                    : walkingPose(clockOffset, forward, right);
            case PREPARE_RUNNING -> sampleOr(RUN_START, prepareTime + clockOffset,
                    () -> runningFallback(clockOffset));
            case RUNNING -> sampleOr(RUN, runTime + clockOffset, () -> runningFallback(clockOffset));
            case COMPLETE_RUNNING -> sampleOr(RUN_END, completeTime + clockOffset, () -> null);
            case OFF_GROUND -> airbornePose(clockOffset, forward, right, false, response);
            case OFF_GROUND_SPRINTING -> airbornePose(clockOffset, forward, right, true, response);
        };
    }

    /**
     * Samples the airborne pose: the jumping ladder first, then Point Blank's single airborne clip, then the held gait.
     *
     * <h2>The ladder</h2>
     * <ol>
     *   <li>{@code jump_start} (or {@code sprint_jump_start}) plays once from the frame the player left the ground;</li>
     *   <li>it cross-fades into {@code jump_loop} (or {@code sprint_jump_loop}) over the last
     *   {@link #JUMP_HANDOVER_SECONDS} of the take-off clip, and the loop then advances with the airborne clock - it is never
     *   restarted, because it is sampled from {@code airTime} and not from the tick;</li>
     *   <li>a pack that authors only the loop plays it from the start of the jump; a pack that authors only the take-off holds
     *   its last frame;</li>
     *   <li>a pack that authors neither keeps the legacy {@code off_ground} / {@code off_ground_sprinting} clip, and a pack
     *   with none of those holds the gait the player left the ground with - which is exactly the behaviour before jumping
     *   clips existed.</li>
     * </ol>
     *
     * @param clockOffset the sub-tick offset already scaled to the airborne clock
     * @param forward     the longitudinal direction weight to sample with
     * @param right       the lateral direction weight to sample with
     * @param sprinting   whether this is the sprinting branch
     * @param response    how much of the authored vertical displacement to keep, in {@code [0, 1]}; the rotations of the
     *                    airborne clip are always kept, only the translation settles onto the gait
     * @return the airborne pose, or {@code null}
     */
    private WeaponPose airbornePose(double clockOffset, double forward, double right, boolean sprinting,
                                    double response) {
        WeaponPose jump = jumpPose(clockOffset, sprinting);
        if (jump != null) {
            // The authored clip owns the reaction (its rotations and its lateral motion), but its vertical offset is
            // authored for a whole jump and would otherwise hold the weapon above its gait for the entire airborne period.
            // The player's own vertical velocity decides how much of it is real: full while rising, none at the apex and
            // while falling. See JUMP_TAKEOFF_SPEED.
            WeaponPose carry = sprinting ? sprintCarry() : walkingCarry(forward, right);
            if (carry == null || response >= 1.0) {
                return jump;
            }
            return scaleVerticalOffsets(jump, carry, response);
        }
        String legacy = sprinting ? OFF_GROUND_SPRINTING : OFF_GROUND;
        WeaponPose authored = has(legacy) ? sample(legacy, airTime + clockOffset) : null;
        if (authored != null) {
            return authored;
        }
        return sprinting ? sprintCarry() : walkingCarry(forward, right);
    }

    /**
     * @return the sprinting gait pose captured at take-off, falling back to a live sample when the state was seeded without
     *         an entry (a restored timeline or a direct test)
     */
    private WeaponPose sprintCarry() {
        return sprintCarryPose != null ? sprintCarryPose : runningCarry();
    }

    /**
     * Blends the vertical components of a pose's position channels towards another pose.
     *
     * <p>Rotations and scale come from {@code pose} unchanged, and so do the lateral and longitudinal components of the
     * translation: only the vertical component is re-based, which is exactly the part of an airborne clip that made the
     * weapon "float". A bone the target does not animate is blended against the identity offset, because an unanimated
     * position channel is a delta of zero.</p>
     *
     * @param pose   the sampled airborne pose
     * @param target the pose the weapon must settle onto, may be {@code null}
     * @param weight how much of {@code pose}'s translation to keep, {@code 0..1}
     * @return the re-based pose, exactly {@code pose} when nothing can be blended
     */
    private static WeaponPose scaleVerticalOffsets(WeaponPose pose, WeaponPose target, double weight) {
        if (pose == null || pose.isEmpty() || target == null || weight >= 1.0) {
            return pose;
        }
        double clamped = AnimationBlending.clamp01(weight);
        Map<String, SampledBone> bones = new LinkedHashMap<>();
        for (Map.Entry<String, SampledBone> entry : pose.bones().entrySet()) {
            SampledBone bone = entry.getValue();
            Vec3 position = bone.position();
            if (position == null) {
                bones.put(entry.getKey(), bone);
                continue;
            }
            SampledBone targetBone = target.bone(entry.getKey());
            Vec3 base = targetBone == null ? null : targetBone.position();
            double baseY = base == null ? 0.0 : base.y();
            Vec3 blended = new Vec3(
                    position.x(),
                    (float) (baseY + (position.y() - baseY) * clamped),
                    position.z());
            bones.put(entry.getKey(), new SampledBone(entry.getKey(), bone.rotation(), blended, bone.scale()));
        }
        return new WeaponPose(pose.animationName(), pose.timeSeconds(), bones, pose.issues());
    }

    /**
     * The jumping ladder described in {@link #airbornePose}.
     *
     * @param clockOffset the sub-tick offset already scaled to the airborne clock
     * @param sprinting   whether this is the sprinting branch
     * @return the jump pose, or {@code null} when the pack authors no jumping clip at all
     */
    private WeaponPose jumpPose(double clockOffset, boolean sprinting) {
        String start = sprinting ? SPRINT_JUMP_START : JUMP_START;
        String loop = sprinting ? SPRINT_JUMP_LOOP : JUMP_LOOP;
        double startLength = length(start);
        boolean hasLoop = has(loop) && length(loop) > 0.0;
        if (startLength <= 0.0 && !hasLoop) {
            return null;
        }
        double time = airTime + clockOffset;
        WeaponPose takeOff = startLength > 0.0 ? sample(start, time) : null;
        if (!hasLoop) {
            return takeOff;
        }
        WeaponPose looping = null;
        // The hand-over may not swallow the take-off clip: a pack is free to author a jump_start shorter than the hand-over
        // window, and fading the whole clip into the loop would make the take-off invisible. Half the clip is the ceiling.
        double handover = Math.min(JUMP_HANDOVER_SECONDS, startLength * 0.5);
        double handoverStart = Math.max(0.0, startLength - handover);
        if (takeOff == null) {
            // The loop is the only clip: it starts with the jump.
            looping = sample(loop, time);
            return looping;
        }
        if (time <= handoverStart) {
            return takeOff;
        }
        looping = sample(loop, time - handoverStart);
        if (looping == null) {
            return takeOff;
        }
        float blend = (float) AnimationBlending.smoothstep(
                Math.min(1.0, (time - handoverStart) / Math.max(1.0E-9, handover)));
        return WeaponPoseBlend.blend(takeOff, looping, blend);
    }

    /**
     * The pose held while airborne when the pack authors no airborne clip: the walking blend at the frozen phase.
     *
     * <p>Frozen, not restarted, and frozen <em>including across the frames of a tick</em>: the walk clock does not advance in
     * the air, so adding the frame's sub-tick offset to it would move the held pose forward inside every tick and snap it
     * back at the tick boundary. That is a subtle artefact on a walk clip (its root travels a hundredth of a unit per tick);
     * on a run clip, whose root travels several tenths of a unit per tick, it is a visible seizure. The carry is therefore
     * sampled at the exact phase the player left the ground with, and only the interpolated direction weights change
     * between frames. When the pack has no walking clip either - a jump from a standstill - the pose that was on screen when
     * the state was entered is held instead, so a jump never loses the weapon pose.</p>
     *
     * @param forward the longitudinal direction weight to sample with
     * @param right   the lateral direction weight to sample with
     * @return the held pose, or {@code null}
     */
    private WeaponPose walkingCarry(double forward, double right) {
        WeaponPose walking = walkingPose(0.0, forward, right);
        return walking != null ? walking : frozenPose;
    }

    /**
     * The pose held while airborne during a sprint when the pack authors no {@code off_ground_sprinting} clip.
     *
     * <p>The running cycle itself, sampled at the phase the player left the ground with - not {@code run_hold}, which is a
     * single authored frame and therefore an arbitrary point of the cycle. The phase is frozen across the airborne period
     * and across the frames of a tick, for the same reason as {@link #walkingCarry(double, double)}: sampling a frozen
     * clock with a moving sub-tick offset makes the held pose march forward and snap back on every tick, which the fast run
     * clip turns into a very visible jitter.</p>
     *
     * @return the held pose, or {@code null}
     */
    private WeaponPose runningCarry() {
        WeaponPose running = sample(RUN, runTime);
        if (running != null) {
            return running;
        }
        return sampleOr(RUN_HOLD, 0.0, () -> frozenPose);
    }

    /**
     * The pose a run state falls back to when its own clip is missing.
     *
     * <p>{@code run_hold} first, because it is the pose a running player holds; then whatever was on screen, so a pack with a
     * single running clip still runs instead of dropping to the idle pose.</p>
     *
     * @param offsetSeconds the sub-tick offset
     * @return the fallback pose, or {@code null}
     */
    private WeaponPose runningFallback(double offsetSeconds) {
        return sampleOr(RUN_HOLD, offsetSeconds, () -> frozenPose);
    }

    /**
     * Samples one clip, falling back when it is missing or empty.
     *
     * @param suffix        the clip suffix
     * @param time          the time to sample at
     * @param fallback      the supplier of the fallback pose
     * @return the sampled pose, or the fallback
     */
    private WeaponPose sampleOr(String suffix, double time, java.util.function.Supplier<WeaponPose> fallback) {
        WeaponPose sampled = sample(suffix, time);
        return sampled != null ? sampled : fallback.get();
    }

    /**
     * @param suffix the clip suffix
     * @param time   the phase
     * @return the clip's pose, or {@code null} when that clip is not declared
     */
    private WeaponPose sample(String suffix, double time) {
        BedrockAnimation clip = clips.get(suffix);
        if (clip == null) {
            return null;
        }
        WeaponPose pose = stripScale(WeaponPose.fromSampledAnimation(AnimationSampler.sample(clip, time)));
        return pose == null || pose.isEmpty() ? null : pose;
    }

    /**
     * Blends the four directional walking clips by the smoothed input.
     *
     * <p>This is the part of the old implementation that is kept verbatim, because it is what this framework's content is
     * authored for: the four clips share one phase, so a diagonal is half of each, and a direction change moves the weights
     * instead of switching a clip. A pack that only authors Point Blank's single {@code walking} clip is supported as a
     * fallback.</p>
     *
     * @param offsetSeconds the sub-tick offset, already scaled to the animation clock
     * @param forwardWeight the longitudinal direction weight, {@code -1} backward to {@code 1} forward
     * @param rightWeight   the lateral direction weight, {@code -1} left to {@code 1} right
     * @return the blended pose, or {@code null} when no walk clip is declared
     */
    private WeaponPose walkingPose(double offsetSeconds, double forwardWeight, double rightWeight) {
        double time = walkPhase + offsetSeconds;
        double forward = Math.abs(forwardWeight) < DIRECTION_EPSILON ? 0.0 : forwardWeight;
        double right = Math.abs(rightWeight) < DIRECTION_EPSILON ? 0.0 : rightWeight;
        if (forward == 0.0 && right == 0.0) {
            // No direction (a player pushing a wall): the single clip, if the pack has one, keeps the bob alive exactly as
            // the reference's walking state does; otherwise there is nothing to sample.
            return sample(WALKING, time);
        }
        double positive = Math.max(0.0, forward);
        double negative = Math.max(0.0, -forward);
        double leftward = Math.max(0.0, -right);
        double rightward = Math.max(0.0, right);
        double longitudinal = positive + negative;
        double lateral = leftward + rightward;
        if (longitudinal + lateral <= DIRECTION_EPSILON) {
            return sample(WALKING, time);
        }
        WeaponPose along = blendDirections(WALK_FORWARD, positive, WALK_BACKWARD, negative, time);
        WeaponPose across = blendDirections(WALK_LEFT, leftward, WALK_RIGHT, rightward, time);
        if (along == null) {
            return across != null ? across : sample(WALKING, time);
        }
        if (across == null) {
            return along;
        }
        float lateralShare = (float) (lateral / (longitudinal + lateral));
        return WeaponPoseBlend.blend(along, across, lateralShare);
    }

    /**
     * Blends one axis of the walking input.
     *
     * @param positive       the suffix used for a positive input
     * @param positiveWeight its weight, may be {@code 0}
     * @param negative       the suffix used for a negative input
     * @param negativeWeight its weight, may be {@code 0}
     * @param time           the shared phase
     * @return the blended pose of that axis, or {@code null} when neither clip exists
     */
    private WeaponPose blendDirections(String positive, double positiveWeight, String negative, double negativeWeight,
                                       double time) {
        WeaponPose positivePose = positiveWeight <= 0.0 ? null : sample(positive, time);
        WeaponPose negativePose = negativeWeight <= 0.0 ? null : sample(negative, time);
        if (positivePose == null) {
            return negativePose;
        }
        if (negativePose == null) {
            return positivePose;
        }
        double total = positiveWeight + negativeWeight;
        if (total <= 0.0) {
            return positivePose;
        }
        return WeaponPoseBlend.blend(positivePose, negativePose, (float) (negativeWeight / total));
    }

    /**
     * @param suffix a clip suffix
     * @return its authored length in seconds, {@code 0} when it is not declared
     */
    private double length(String suffix) {
        BedrockAnimation clip = clips.get(suffix);
        return clip == null ? 0.0 : Math.max(0.0, clip.lengthSeconds());
    }

    /**
     * @param suffix a clip suffix
     * @return whether the clip declares itself as looping
     */
    private boolean isLooping(String suffix) {
        BedrockAnimation clip = clips.get(suffix);
        return clip != null && clip.isLooping();
    }

    /**
     * @param value the reached time
     * @param total the clip's length
     * @return how far into the clip the time is, in {@code [0, 1]}; {@code 1} when the length is unknown
     */
    private static double fraction(double value, double total) {
        return total > 0.0 ? AnimationBlending.clamp01(value / total) : 1.0;
    }

    /**
     * Drops every scale channel of a movement pose.
     *
     * <p>A movement clip is a root motion; a scale channel in it would rescale the whole weapon, which no movement animation
     * is allowed to do. Removing the channel is what makes such a clip harmless instead of destructive.</p>
     *
     * @param pose the sampled pose
     * @return the same pose without scale channels, or {@code null} when nothing but scale was animated
     */
    private static WeaponPose stripScale(WeaponPose pose) {
        if (pose == null || pose.isEmpty()) {
            return null;
        }
        Map<String, SampledBone> bones = new LinkedHashMap<>();
        for (Map.Entry<String, SampledBone> entry : pose.bones().entrySet()) {
            SampledBone bone = entry.getValue();
            if (bone.rotation() == null && bone.position() == null) {
                continue;
            }
            bones.put(entry.getKey(), new SampledBone(entry.getKey(), bone.rotation(), bone.position(), null));
        }
        if (bones.isEmpty()) {
            return null;
        }
        return new WeaponPose(pose.animationName(), pose.timeSeconds(), bones, pose.issues());
    }

    /** @return the vertical response as this tick resolved it, in {@code [0, 1]}; used by tests and diagnostics. */
    public double verticalResponse() {
        return verticalResponse;
    }

    /**
     * @return the layer's fade weight as this tick resolved it, in {@code [0, 1]}; the renderer uses
     *         {@link #weightAt(double)} so the fade is evaluated at the frame's own timestamp
     */
    public float weight() {
        // A directional walk is a *weighted sum* of up to four clips, and the sum of its weights is the input magnitude: at
        // half a stick the bob has to be half as deep, and a reversal has to pass through the rest pose. The multiplier is
        // taken from the smoothed weights, never from the raw input, so it cannot flicker. The run and airborne poses are
        // complete clips and are always applied at the layer's own weight.
        double scale = weight;
        if (state.isWalking() && !(state == LocomotionState.WALKING_AIMING && has(WALK_AIMING))) {
            scale *= Math.min(1.0, Math.sqrt(smoothedForward * smoothedForward + smoothedRight * smoothedRight));
        }
        return (float) AnimationBlending.clamp01(AnimationBlending.smoothstep(clamp01(scale)));
    }

    /** @return the current locomotion state, for diagnostics and tests. */
    public LocomotionState state() {
        return state;
    }

    /** @return the shared walking phase in seconds, for diagnostics and tests. */
    public double walkPhase() {
        return walkPhase;
    }

    /** @return the prepare-running clip's playback time in seconds, for diagnostics and tests. */
    public double prepareTime() {
        return prepareTime;
    }

    /** @return the running clip's playback time in seconds, for diagnostics and tests. */
    public double runTime() {
        return runTime;
    }

    /** @return the complete-running clip's playback time in seconds, for diagnostics and tests. */
    public double completeTime() {
        return completeTime;
    }

    /** @return the airborne clock in seconds, for diagnostics and tests. */
    public double airTime() {
        return airTime;
    }

    /** @return the smoothed playback-rate multiplier, for diagnostics and tests. */
    public double speedScale() {
        return speedScale;
    }

    /** @return whether the layer currently contributes anything. */
    public boolean active() {
        return !clips.isEmpty() && weight > 0.0 && pose(0.0) != null;
    }
}









