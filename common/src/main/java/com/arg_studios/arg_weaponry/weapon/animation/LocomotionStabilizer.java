package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * Turns the raw per-tick locomotion sample of a player into the {@link MovementState} (and {@link LocomotionIntent}) the
 * locomotion layer consumes.
 *
 * <h2>The reference's intent rules, adopted</h2>
 * <p>Vic's Point Blank builds its walking state machine from a context that carries two <em>intent</em> flags rather than the
 * raw input, and the smoothness of its transitions rests on them. This class computes the same two flags with the same grace
 * windows:</p>
 * <ul>
 *   <li><strong>movement intent</strong>: any direction key, a non-zero analog vector, or either within the last
 *   {@value #MOVEMENT_GRACE_MILLIS} ms. A one-tick gap - a released key, a dropped analog value, a wall push - therefore does
 *   not leave the walking state.</li>
 *   <li><strong>sprint intent</strong>: the player is sprinting, the sprint key is down, or either within the last
 *   {@value #SPRINT_GRACE_MILLIS} ms. This is what stops a sprint boundary from bouncing through the run-end clip.</li>
 * </ul>
 *
 * <h2>The airborne gate</h2>
 * <p>The reference also carries {@code canUseOffGroundTransition()}: right after the walking track is reset (a draw, a weapon
 * switch) a player who is moving is <em>not</em> treated as airborne unless the vertical velocity is significant
 * ({@code |vy| > }{@value #OFF_GROUND_VERTICAL_THRESHOLD}), which keeps a draw from starting with a jump pose. On top of that,
 * this implementation requires one tick of confirmation before a small-velocity loss of contact counts, so stepping down a
 * slab does not swap the walking pose for the airborne one for a single frame and back.</p>
 *
 * <h2>Playback speed</h2>
 * <p>{@link #speedScale(double, double, boolean, boolean)} is the reference's walk speed provider:
 * {@code sqrt(clamp(speed / reference, 0.1, 10))}, with the sprint bonus and the slow reduction it applies to the reference
 * speed. It is a static pure function so the mapping itself is testable.</p>
 *
 * <p>Pure: no Minecraft types and no clock - the target's {@code currentTimeMillis} becomes an accumulated {@code double}
 * here, which makes the graces frame-rate independent and reproducible in a test. One instance belongs to one player, because
 * the graces are memory.</p>
 */
public final class LocomotionStabilizer {

    /** How long a lost movement intent is remembered, in milliseconds (the reference's value). */
    public static final long MOVEMENT_GRACE_MILLIS = 120;

    /** How long a lost sprint intent is remembered, in milliseconds (the reference's value). */
    public static final long SPRINT_GRACE_MILLIS = 120;

    /** How long after a reset the startup airborne gate stays closed, in seconds (the reference's 450 ms). */
    public static final double OFF_GROUND_STARTUP_SECONDS = 0.45;

    /** The vertical velocity, in blocks per tick, above which a loss of contact counts immediately. */
    public static final double OFF_GROUND_VERTICAL_THRESHOLD = 0.08;

    /** How long a small-velocity loss of contact must last before it counts as airborne, in seconds (one client tick). */
    public static final double OFF_GROUND_CONFIRM_SECONDS = 0.05;

    /** The lowest playback-rate ratio, matching the reference's clamp of {@code 0.1..10} before the square root. */
    public static final double MIN_SPEED_RATIO = 0.1;

    /** The highest playback-rate ratio before the square root. */
    public static final double MAX_SPEED_RATIO = 10.0;

    /** Longest delta one update may apply, so a stall cannot skip a grace in one step. */
    private static final double MAX_DELTA_SECONDS = 0.1;

    private double secondsSinceMovementIntent = Double.MAX_VALUE;
    private double secondsSinceSprintIntent = Double.MAX_VALUE;
    private double offGroundSeconds;

    /**
     * Advances the stabiliser by one client tick.
     *
     * @param sample       the raw projection of the player, may be {@code null} for a neutral one
     * @param deltaSeconds the time the tick covered
     * @return the stabilised movement state, including the intent, never {@code null}
     */
    public MovementState update(LocomotionSample sample, double deltaSeconds) {
        LocomotionSample raw = sample == null ? LocomotionSample.STILL : sample;
        double delta = Double.isFinite(deltaSeconds) ? Math.max(0.0, Math.min(MAX_DELTA_SECONDS, deltaSeconds)) : 0.0;

        boolean inputMoving = raw.anyDirectionKey()
                || Math.abs(raw.forward()) >= MovementState.DEAD_ZONE
                || Math.abs(raw.right()) >= MovementState.DEAD_ZONE;
        secondsSinceMovementIntent = inputMoving ? 0.0
                : Math.min(Double.MAX_VALUE, secondsSinceMovementIntent + delta);

        boolean inputSprinting = raw.sprinting() || raw.sprintKeyDown();
        secondsSinceSprintIntent = inputSprinting ? 0.0
                : Math.min(Double.MAX_VALUE, secondsSinceSprintIntent + delta);

        boolean movementIntent = inputMoving || secondsSinceMovementIntent * 1000.0 <= MOVEMENT_GRACE_MILLIS;
        boolean sprintIntent = inputSprinting || secondsSinceSprintIntent * 1000.0 <= SPRINT_GRACE_MILLIS;

        if (raw.onGround()) {
            offGroundSeconds = 0.0;
        } else {
            offGroundSeconds = Math.min(Double.MAX_VALUE, offGroundSeconds + delta);
        }

        boolean significantVelocity = Math.abs(raw.verticalVelocity()) > OFF_GROUND_VERTICAL_THRESHOLD;
        boolean startupClosed = raw.secondsSinceReset() <= OFF_GROUND_STARTUP_SECONDS && movementIntent;
        boolean offGroundAllowed = significantVelocity
                || (!startupClosed && offGroundSeconds > OFF_GROUND_CONFIRM_SECONDS);
        boolean airborne = !raw.onGround() && offGroundAllowed;

        LocomotionIntent intent = new LocomotionIntent(movementIntent, sprintIntent,
                raw.forwardKey(), raw.backwardKey(), raw.leftKey(), raw.rightKey(), raw.slow(),
                offGroundAllowed,
                speedScale(raw.speed(), raw.referenceSpeed(), raw.sprinting(), raw.slow()));

        return MovementState.of(raw.forward(), raw.right(), raw.sprinting(), airborne, intent, raw.verticalVelocity());
    }

    /**
     * The reference's walk speed provider: how fast a locomotion clip should play for a player moving at {@code speed}.
     *
     * <pre>
     *   reference = referenceSpeed * (sprinting ? 1.3 : 1.0)
     *   ratio     = speed / reference
     *   ratio    *= slow ? 0.6 : 1.0
     *   scale     = sqrt(clamp(ratio, 0.1, 10))
     * </pre>
     *
     * @param speed          the player's horizontal speed, in blocks per tick
     * @param referenceSpeed the player's walking speed attribute, in blocks per tick
     * @param sprinting      whether the player is sprinting
     * @param slow           whether the player is moving slowly in vanilla's sense
     * @return the playback-rate multiplier, inside {@link LocomotionIntent#MIN_SPEED_SCALE} and
     *         {@link LocomotionIntent#MAX_SPEED_SCALE}
     */
    public static double speedScale(double speed, double referenceSpeed, boolean sprinting, boolean slow) {
        double reference = Double.isFinite(referenceSpeed) && referenceSpeed > 0.0 ? referenceSpeed : 0.13;
        if (sprinting) {
            reference += reference * 0.3;
        }
        double ratio = (Double.isFinite(speed) ? Math.max(0.0, speed) : 0.0) / reference;
        if (slow) {
            ratio *= 0.6;
        }
        return Math.sqrt(Math.max(MIN_SPEED_RATIO, Math.min(MAX_SPEED_RATIO, ratio)));
    }

    /** Forgets every grace; used when the player, the world or the camera perspective changes. */
    public void reset() {
        secondsSinceMovementIntent = Double.MAX_VALUE;
        secondsSinceSprintIntent = Double.MAX_VALUE;
        offGroundSeconds = 0.0;
    }

    /** @return how long the movement intent has been gone, in seconds, for diagnostics and tests. */
    public double secondsSinceMovementIntent() {
        return secondsSinceMovementIntent;
    }

    /** @return how long the sprint intent has been gone, in seconds, for diagnostics and tests. */
    public double secondsSinceSprintIntent() {
        return secondsSinceSprintIntent;
    }

    /** @return how long the player has been off the ground, in seconds, for diagnostics and tests. */
    public double offGroundSeconds() {
        return offGroundSeconds;
    }
}
