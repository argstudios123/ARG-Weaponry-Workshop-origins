package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * One tick of raw locomotion input, projected from the local player and handed to {@link LocomotionStabilizer}.
 *
 * <p>This is the boundary between Minecraft and the pure locomotion system: everything above it (the stabiliser, the state
 * machine, the layer) is plain Java with no game types, so the whole chain can be driven from a test.</p>
 *
 * @param forward          the analog forward input, {@code 1} forward and {@code -1} backward
 * @param right            the analog lateral input, {@code 1} right and {@code -1} left
 * @param forwardKey       whether the forward key is held
 * @param backwardKey      whether the backward key is held
 * @param leftKey          whether the left key is held
 * @param rightKey         whether the right key is held
 * @param sprintKeyDown    whether the sprint key is held
 * @param sprinting        whether the player is currently sprinting
 * @param onGround         whether the player is on the ground
 * @param verticalVelocity the player's vertical velocity, in blocks per tick
 * @param slow             vanilla's {@code isMovingSlowly()}
 * @param speed            the player's horizontal speed, in blocks per tick
 * @param referenceSpeed   the player's reference walking speed (the movement-speed attribute), blocks per tick
 * @param secondsSinceReset how long ago the locomotion track was reset (a draw or a weapon switch), in seconds
 */
public record LocomotionSample(double forward, double right, boolean forwardKey, boolean backwardKey,
                               boolean leftKey, boolean rightKey, boolean sprintKeyDown, boolean sprinting,
                               boolean onGround, double verticalVelocity, boolean slow, double speed,
                               double referenceSpeed, double secondsSinceReset) {

    /** A standing player on the ground, used as the neutral sample. */
    public static final LocomotionSample STILL = new LocomotionSample(0.0, 0.0, false, false, false, false, false,
            false, true, 0.0, false, 0.0, 0.13, Double.MAX_VALUE);

    public LocomotionSample {
        forward = Double.isFinite(forward) ? forward : 0.0;
        right = Double.isFinite(right) ? right : 0.0;
        verticalVelocity = Double.isFinite(verticalVelocity) ? verticalVelocity : 0.0;
        speed = Double.isFinite(speed) ? Math.max(0.0, speed) : 0.0;
        referenceSpeed = Double.isFinite(referenceSpeed) && referenceSpeed > 0.0 ? referenceSpeed : 0.13;
        secondsSinceReset = Double.isFinite(secondsSinceReset) ? Math.max(0.0, secondsSinceReset) : Double.MAX_VALUE;
    }

    /** @return whether any of the four direction keys is held. */
    public boolean anyDirectionKey() {
        return forwardKey || backwardKey || leftKey || rightKey;
    }
}
