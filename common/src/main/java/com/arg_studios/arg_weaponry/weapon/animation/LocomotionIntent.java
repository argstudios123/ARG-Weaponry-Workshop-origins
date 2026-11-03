package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * The locomotion facts a weapon's movement layer needs that are not part of the analog movement vector.
 *
 * <h2>Why intent flags and not the raw keys</h2>
 * <p>A state machine that reads the raw input flickers: a key released for a single tick, a step off a half block or a
 * one-frame drop of the analog vector is enough to push it into another state and back. The reference implementation
 * (Vic's Point Blank, {@code FirstPersonWalkingAnimationHandler}) therefore feeds its state machine two <em>intent</em>
 * flags with grace periods instead of the raw signal, and this record carries exactly those:</p>
 * <ul>
 *   <li><strong>movement intent</strong> - a direction key or the analog move vector is non-zero, or was within the last
 *   {@code 120 ms};</li>
 *   <li><strong>sprint intent</strong> - the player is sprinting, the sprint key is down, or either was within the last
 *   {@code 120 ms}. An action that owns the weapon (aiming, reloading, inspecting) also counts as sprint intent, which is
 *   what keeps the walking states from being entered while the weapon is busy.</li>
 * </ul>
 *
 * <h2>The rest of the facts</h2>
 * <ul>
 *   <li>the four direction keys, because the reference machine's walking states are keyed decisions, not analog
 *   weights;</li>
 *   <li>{@code slow} - vanilla's {@code isMovingSlowly()} (sneaking, using an item, a slowed effect);</li>
 *   <li>{@code offGroundAllowed} - the reference's startup gate: a player who has just drawn the weapon is not treated as
 *   airborne for a moment unless the vertical velocity says otherwise, so a draw does not start with a jump pose;</li>
 *   <li>{@code speedScale} - how fast the locomotion clips should play, derived from the player's real speed exactly like
 *   the reference's walk speed provider.</li>
 * </ul>
 *
 * <p>Pure: no Minecraft types, no clock. {@code NONE} is the neutral value used by every caller that only has an analog
 * vector (the older {@code MovementState} constructors, the animation tester, tests).</p>
 *
 * @param movementIntent   whether the player wants to move, with the grace period applied
 * @param sprintIntent     whether the player wants to sprint, with the grace period applied
 * @param forwardKey       whether the forward key is held
 * @param backwardKey      whether the backward key is held
 * @param leftKey          whether the left key is held
 * @param rightKey         whether the right key is held
 * @param slow             whether the player is moving slowly in vanilla's sense
 * @param offGroundAllowed whether an airborne state may be entered at all right now
 * @param speedScale       the playback-rate multiplier of the locomotion clips
 */
public record LocomotionIntent(boolean movementIntent, boolean sprintIntent, boolean forwardKey,
                               boolean backwardKey, boolean leftKey, boolean rightKey, boolean slow,
                               boolean offGroundAllowed, double speedScale) {

    /** The neutral intent: no keys, no intent, full playback rate. */
    public static final LocomotionIntent NONE = new LocomotionIntent(false, false, false, false, false, false,
            false, true, 1.0);

    /** The slowest playback rate a locomotion clip is allowed to run at. */
    public static final double MIN_SPEED_SCALE = 0.25;

    /** The fastest playback rate a locomotion clip is allowed to run at. */
    public static final double MAX_SPEED_SCALE = 3.0;

    public LocomotionIntent {
        speedScale = Double.isFinite(speedScale)
                ? Math.max(MIN_SPEED_SCALE, Math.min(MAX_SPEED_SCALE, speedScale)) : 1.0;
    }

    /** @return whether any direction key is held. */
    public boolean anyDirectionKey() {
        return forwardKey || backwardKey || leftKey || rightKey;
    }

    /**
     * @param forward the analog forward input, {@code 1} forward and {@code -1} backward
     * @param right   the analog lateral input, {@code 1} right and {@code -1} left
     * @return the neutral intent, with the direction keys derived from the analog input
     */
    public static LocomotionIntent ofAnalog(double forward, double right) {
        boolean forwardKey = forward >= MovementState.DEAD_ZONE;
        boolean backwardKey = forward <= -MovementState.DEAD_ZONE;
        boolean rightKey = right >= MovementState.DEAD_ZONE;
        boolean leftKey = right <= -MovementState.DEAD_ZONE;
        boolean moving = forwardKey || backwardKey || leftKey || rightKey;
        return new LocomotionIntent(moving, false, forwardKey, backwardKey, leftKey, rightKey, false, true, 1.0);
    }
}
