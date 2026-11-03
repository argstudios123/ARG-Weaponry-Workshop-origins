package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.client.aiming.WeaponAiming;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.SwayState;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;

import java.util.EnumMap;
import java.util.Map;

/**
 * Applies {@link SwayState} to the first-person pose stack. The client-side half of the sway feature.
 *
 * <h2>Where the input comes from</h2>
 * <p>The camera's interpolated yaw and pitch of two consecutive frames give exactly the mouse movement of that frame - the
 * same numbers the camera itself turned by, so a mouse that does not move produces a delta of zero and no sway. The spring is
 * per hand, so the main hand and the off hand each settle on their own, and a frame time of zero (the second sample of one
 * frame) is ignored instead of being integrated twice.</p>
 *
 * <h2>Where the output goes</h2>
 * <p>Onto the pose stack, never into the pose: the translation and the three rotations are multiplied in after the camera-bone
 * animation and the recoil kickback and before the weapon's own placement, so sway is additive to everything else. The pose
 * stack is the single master-bone/root stage of the model, so every child bone inherits one transform; no bone is rotated
 * independently and nothing in this class can overwrite an authored frame. A model that names neither {@code main} nor
 * {@code root} simply has no sway transform and keeps rendering normally.</p>
 */
public final class WeaponSway {

    /** One hand's spring and the frame history needed to measure the mouse movement. */
    private static final class Frame {

        private final SwayState spring = new SwayState();
        private WeaponDefinition definition;
        private double lastYaw;
        private double lastPitch;
        private long lastNanos;
    }

    private static final Map<InteractionHand, Frame> FRAMES = new EnumMap<>(InteractionHand.class);

    private WeaponSway() {
    }

    /**
     * Advances the sway of one hand and applies it to the pose stack.
     *
     * @param poseStack   the camera-space pose stack of the weapon being drawn
     * @param hand        the hand being drawn
     * @param item        the weapon drawn there
     * @param partialTick the frame's partial tick, used to interpolate the camera angles
     */
    public static void apply(PoseStack poseStack, InteractionHand hand, WeaponItem item, float partialTick) {
        if (poseStack == null || hand == null || item == null) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        WeaponDefinition definition = item.definition();
        WeaponDefinition.Sway settings = definition == null || definition.sway() == null
                ? WeaponDefinition.Sway.DEFAULT : definition.sway();
        Frame frame = FRAMES.computeIfAbsent(hand, key -> new Frame());
        if (frame.definition != definition) {
            // A different weapon: its sway starts from rest instead of inheriting the previous weapon's lag.
            frame.definition = definition;
            frame.spring.reset();
            frame.lastNanos = 0L;
        }
        if (!settings.active()) {
            frame.spring.reset();
            return;
        }
        // The pose stack is the master-bone stage: the rotation is applied once, to the whole model hierarchy, before
        // the weapon's placement. A model that names neither `main` nor `root` is left exactly as it was rather than
        // crashing or inventing a bone; the weapon still renders, it simply has no master transform to sway.
        WeaponModel model = WeaponAiming.modelFor(item);
        if (model != null && ModelBoneNames.masterBone(model) == null) {
            frame.spring.reset();
            return;
        }

        double yaw = Mth.lerp(partialTick, player.yRotO, player.getYRot());
        double pitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());
        long now = System.nanoTime();
        double deltaSeconds = frame.lastNanos == 0L ? 0.0 : (now - frame.lastNanos) / 1.0E9;
        double yawDelta = frame.lastNanos == 0L ? 0.0 : Math.toRadians(yaw - frame.lastYaw);
        double pitchDelta = frame.lastNanos == 0L ? 0.0 : Math.toRadians(pitch - frame.lastPitch);
        frame.lastYaw = yaw;
        frame.lastPitch = pitch;
        frame.lastNanos = now;

        float aiming = WeaponAiming.easedProgress(partialTick);
        double aimScale = 1.0 - aiming * (1.0 - settings.aimMultiplier());
        frame.spring.update(yawDelta, pitchDelta, deltaSeconds, settings, aimScale);
        if (frame.spring.atRest()) {
            return;
        }
        poseStack.translate(frame.spring.positionX(), frame.spring.positionY(), frame.spring.positionZ());
        poseStack.rotateDegrees(Axis.YP, (float) frame.spring.rotationY());
        poseStack.rotateDegrees(Axis.XP, (float) frame.spring.rotationX());
        poseStack.rotateDegrees(Axis.ZP, (float) frame.spring.rotationZ());
    }

    /**
     * Forgets every spring, e.g. when the player leaves the world.
     */
    public static void reset() {
        FRAMES.clear();
    }
}
