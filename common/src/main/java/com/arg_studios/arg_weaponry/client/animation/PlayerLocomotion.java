package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.weapon.animation.LocomotionSample;
import com.arg_studios.arg_weaponry.weapon.animation.LocomotionStabilizer;
import com.arg_studios.arg_weaponry.weapon.animation.MovementState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Projects the local player into the {@link MovementState} every weapon's locomotion layer consumes, once per client tick.
 *
 * <h2>Why one projection for all weapons</h2>
 * <p>A player's movement is one fact, not one per held item: computing it inside each view controller would let two weapons
 * disagree by a tick and every one of them would keep its own grace timers. The projection is therefore done once here and
 * handed to every controller, exactly like the aim flag is.</p>
 *
 * <h2>What is measured</h2>
 * <ul>
 *   <li>the analog move vector <em>and</em> the four direction keys, because the reference state machine's walking decisions
 *   are keyed and its intent grace accepts either;</li>
 *   <li>the player's horizontal speed and the movement-speed attribute, which together give the playback-rate multiplier
 *   ({@link LocomotionStabilizer#speedScale});</li>
 *   <li>the ground contact and the vertical velocity, which {@link LocomotionStabilizer} turns into a stable airborne flag -
 *   a step down a single block must not swap the walking pose for the airborne one for one tick;</li>
 *   <li>how long ago the locomotion track was reset, for the reference's startup airborne gate. The track is reset whenever a
 *   weapon action takes the pose over, which is this framework's equivalent of the reference's per-stack reset.</li>
 * </ul>
 *
 * <p>Nothing here is thread safe on purpose: it is owned by the client tick.</p>
 */
public final class PlayerLocomotion {

    /** The graces belong to the player, so the stabiliser is a singleton like every other client-only state. */
    private static final LocomotionStabilizer STABILIZER = new LocomotionStabilizer();

    /** The reference walking speed used when the attribute cannot be read, in blocks per tick. */
    private static final double DEFAULT_REFERENCE_SPEED = 0.13;

    /** How long a weapon action keeps the locomotion track "just reset", in seconds. */
    private static final double RESET_WINDOW_SECONDS = 1.0;

    private static double secondsSinceReset = Double.MAX_VALUE;

    private PlayerLocomotion() {
    }

    /**
     * Samples the player's locomotion for one client tick.
     *
     * @param player       the local player, may be {@code null}
     * @param deltaSeconds the time the tick covered, in seconds
     * @param actionOwned  whether a weapon action (fire, reload, inspect, draw) currently owns the pose
     * @return the stabilised movement state, never {@code null}
     */
    public static MovementState sample(LocalPlayer player, double deltaSeconds, boolean actionOwned) {
        double delta = Double.isFinite(deltaSeconds) ? Math.max(0.0, Math.min(0.1, deltaSeconds)) : 0.0;
        if (player == null) {
            STABILIZER.reset();
            secondsSinceReset = Double.MAX_VALUE;
            return MovementState.STILL;
        }
        if (actionOwned) {
            // A draw, a shot or a reload releases the locomotion track: the reference resets its walking state then, and its
            // startup airborne gate is what keeps that reset from producing a jump pose.
            secondsSinceReset = 0.0;
        } else {
            secondsSinceReset = Math.min(RESET_WINDOW_SECONDS, secondsSinceReset + delta);
        }

        Vec2 move = player.input == null ? Vec2.ZERO : player.input.getMoveVector();
        Vec3 velocity = player.getDeltaMovement();
        double horizontalSpeed = velocity == null ? 0.0 : Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        // The move vector is (left, forward); the layer wants (forward, right).
        LocomotionSample sample = new LocomotionSample(move.y, -move.x,
                keyForward(player), keyBackward(player), keyLeft(player), keyRight(player),
                keySprint(player), player.isSprinting(), player.onGround(),
                velocity == null ? 0.0 : velocity.y, player.isMovingSlowly(), horizontalSpeed,
                referenceSpeed(player), secondsSinceReset);
        return STABILIZER.update(sample, delta);
    }

    /** Forgets the graces; used when the player, the world or the perspective changes. */
    public static void reset() {
        STABILIZER.reset();
        secondsSinceReset = Double.MAX_VALUE;
    }

    /**
     * @param player the local player
     * @return the movement-speed attribute's base value, or the vanilla default when it cannot be read
     */
    private static double referenceSpeed(LocalPlayer player) {
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        double base = attribute == null ? DEFAULT_REFERENCE_SPEED : attribute.getBaseValue();
        return base > 0.0 ? base : DEFAULT_REFERENCE_SPEED;
    }

    private static boolean keyForward(LocalPlayer player) {
        return player.input != null && player.input.keyPresses.forward();
    }

    private static boolean keyBackward(LocalPlayer player) {
        return player.input != null && player.input.keyPresses.backward();
    }

    private static boolean keyLeft(LocalPlayer player) {
        return player.input != null && player.input.keyPresses.left();
    }

    private static boolean keyRight(LocalPlayer player) {
        return player.input != null && player.input.keyPresses.right();
    }

    private static boolean keySprint(LocalPlayer player) {
        return player.input != null && player.input.keyPresses.sprint();
    }
}
