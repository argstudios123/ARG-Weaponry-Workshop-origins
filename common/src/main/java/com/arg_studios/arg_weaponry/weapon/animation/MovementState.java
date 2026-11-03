package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * The player's movement as the animation layer needs it: two normalised direction components, the sprint state and
 * whether the player is off the ground.
 *
 * <p>Deliberately free of Minecraft types: the client projects {@code LocalPlayer} into this value once per tick, which is
 * what lets the whole movement layer - the walk blending, the diagonal weights and the run state machine - be verified
 * headlessly.</p>
 *
 * <p>The {@code airborne} flag is what separates "sprinting into a wall" from "jumping while sprinting": both stop
 * advancing, but only the airborne case is a reason to hold the running pose instead of returning to the ground cycle. It
 * also covers creative flight and elytra flight, so those never restart a running clip on every tick either.</p>
 *
 * <p>{@code verticalVelocity} is the player's own vertical movement for the tick, in blocks per tick. It is not used to
 * decide <em>whether</em> the player is airborne - that is the stabiliser's job, because it has the grace windows - but it
 * is what lets the animation layer react to the jump's actual arc instead of to a clock: rising, at the apex and falling
 * are three different visual states, and a weapon held in one raised pose for all three is what reads as "floating".</p>
 *
 * @param forward          movement along the view axis, {@code 1} forward and {@code -1} backward
 * @param right            movement to the player's right, {@code 1} right and {@code -1} left
 * @param sprinting        whether the player is sprinting, which is what selects the running clips
 * @param airborne         whether the player is off the ground: jumping, falling, flying or gliding
 * @param intent           the locomotion state machine's guards, see {@link LocomotionIntent};
 *                         {@link LocomotionIntent#NONE} for a caller that only has an analog vector
 * @param verticalVelocity the player's vertical velocity for the tick, in blocks per tick
 */
public record MovementState(double forward, double right, boolean sprinting, boolean airborne,
                            LocomotionIntent intent, double verticalVelocity) {

    /** No movement input. */
    public static final MovementState STILL = new MovementState(0.0, 0.0, false, false);

    /** Below this the input is treated as standing still, so a leftover analogue value cannot start a walk cycle. */
    public static final double DEAD_ZONE = 0.05;

    /**
     * The four-component form used by every caller that has no reason to pass an intent or a vertical velocity.
     *
     * @param forward   movement along the view axis
     * @param right     movement to the player's right
     * @param sprinting whether the player is sprinting
     * @param airborne  whether the player is off the ground
     */
    public MovementState(double forward, double right, boolean sprinting, boolean airborne) {
        this(forward, right, sprinting, airborne, LocomotionIntent.NONE, 0.0);
    }

    /**
     * The five-component form used by callers that know the intent but have no vertical velocity of their own.
     *
     * @param forward   movement along the view axis
     * @param right     movement to the player's right
     * @param sprinting whether the player is sprinting
     * @param airborne  whether the player is off the ground
     * @param intent    the locomotion state machine's guards
     */
    public MovementState(double forward, double right, boolean sprinting, boolean airborne, LocomotionIntent intent) {
        this(forward, right, sprinting, airborne, intent, 0.0);
    }

    /**
     * The three-component form used by every caller that has no reason to distinguish an airborne player.
     *
     * @param forward   movement along the view axis
     * @param right     movement to the player's right
     * @param sprinting whether the player is sprinting
     */
    public MovementState(double forward, double right, boolean sprinting) {
        this(forward, right, sprinting, false, LocomotionIntent.NONE, 0.0);
    }

    public MovementState {
        forward = Double.isFinite(forward) ? Math.max(-1.0, Math.min(1.0, forward)) : 0.0;
        right = Double.isFinite(right) ? Math.max(-1.0, Math.min(1.0, right)) : 0.0;
        intent = intent == null ? LocomotionIntent.NONE : intent;
        verticalVelocity = Double.isFinite(verticalVelocity) ? verticalVelocity : 0.0;
    }

    /**
     * @param forward   raw forward impulse, may be any finite number
     * @param right     raw right impulse, may be any finite number
     * @param sprinting whether the player is sprinting
     * @return the normalised state, with the dead zone applied
     */
    public static MovementState of(double forward, double right, boolean sprinting) {
        return of(forward, right, sprinting, false);
    }

    /**
     * @param forward   raw forward impulse, may be any finite number
     * @param right     raw right impulse, may be any finite number
     * @param sprinting whether the player is sprinting
     * @param airborne  whether the player is off the ground
     * @return the normalised state, with the dead zone applied
     */
    public static MovementState of(double forward, double right, boolean sprinting, boolean airborne) {
        return of(forward, right, sprinting, airborne, LocomotionIntent.NONE);
    }

    /**
     * @param forward   raw forward impulse, may be any finite number
     * @param right     raw right impulse, may be any finite number
     * @param sprinting whether the player is sprinting
     * @param airborne  whether the player is off the ground
     * @param intent    the locomotion state machine's guards, may be {@code null}
     * @return the normalised state, with the dead zone applied
     */
    public static MovementState of(double forward, double right, boolean sprinting, boolean airborne,
                                   LocomotionIntent intent) {
        return of(forward, right, sprinting, airborne, intent, 0.0);
    }

    /**
     * @param forward          raw forward impulse, may be any finite number
     * @param right            raw right impulse, may be any finite number
     * @param sprinting        whether the player is sprinting
     * @param airborne         whether the player is off the ground
     * @param intent           the locomotion state machine's guards, may be {@code null}
     * @param verticalVelocity the player's vertical velocity, in blocks per tick
     * @return the normalised state, with the dead zone applied
     */
    public static MovementState of(double forward, double right, boolean sprinting, boolean airborne,
                                   LocomotionIntent intent, double verticalVelocity) {
        double f = Math.abs(forward) < DEAD_ZONE ? 0.0 : Math.max(-1.0, Math.min(1.0, forward));
        double r = Math.abs(right) < DEAD_ZONE ? 0.0 : Math.max(-1.0, Math.min(1.0, right));
        // A diagonal input is longer than one axis; normalising keeps the walk speed independent of the direction without
        // inventing a movement vector the player did not give.
        double length = Math.sqrt(f * f + r * r);
        if (length > 1.0) {
            f /= length;
            r /= length;
        }
        return new MovementState(f, r, sprinting, airborne, intent, verticalVelocity);
    }

    /** @return whether the player is moving in any direction. */
    public boolean moving() {
        return Math.abs(forward) >= DEAD_ZONE || Math.abs(right) >= DEAD_ZONE;
    }

    /**
     * @return whether the player can advance on the ground: holding a direction and not in the air
     */
    public boolean advancing() {
        return moving() && !airborne;
    }

    /** @return whether a running animation applies: sprinting <em>and</em> actually moving, or transitioning out of it. */
    public boolean running() {
        return sprinting;
    }

    /** @return the horizontal magnitude of the input, in {@code [0, 1]}. */
    public double magnitude() {
        return Math.min(1.0, Math.sqrt(forward * forward + right * right));
    }
}
