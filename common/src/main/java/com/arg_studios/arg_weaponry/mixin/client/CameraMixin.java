package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.camera.CameraComposition;
import com.arg_studios.arg_weaponry.client.camera.CameraTransform;
import com.arg_studios.arg_weaponry.client.camera.FirstPersonCamera;
import com.arg_studios.arg_weaponry.client.diagnostics.TransformDiagnostics;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the weapon view effects to the level camera, in first person only, in a fixed composition order.
 *
 * <h2>Why {@code Camera#update} and why at its end</h2>
 * <p>{@code update} is where vanilla turns the player's view angles and position into the camera's own rotation and position,
 * and everything after it - {@code extractRenderState}, the view matrix, the frustum, the weapon pass - reads the result.
 * Injecting at its tail therefore changes the camera before anything consumes it, once per frame, and without touching the
 * projection or any other piece of camera state.</p>
 *
 * <h2>The composition order</h2>
 * <ol>
 *   <li><strong>Vanilla</strong> has already produced the camera orientation from the player's angles, interpolated with this
 *   frame's partial tick - mouse movement included. That value is the <em>base</em>.</li>
 *   <li><strong>The authored camera-bone animation</strong> is composed onto that base by
 *   {@link CameraComposition#compose(float, float, CameraTransform)}, which takes the base as an argument. Milestone 10S
 *   instead applied an absolute orientation that had been sampled on the client tick, which froze the view to 20 Hz for as
 *   long as an animated clip played; composing against the live base removes that without giving up the tick-owned
 *   timeline.</li>
 * </ol>
 * <p>Nothing here keeps a running total: every frame starts from the base vanilla produced and finishes with that frame's
 * authored animation, so repeating a frame with the same inputs gives the same output, and when the animation is the
 * identity the camera is exactly vanilla's.</p>
 *
 * <h2>No screen shake</h2>
 * <p>There is no per-shot camera offset any more. Firing recoil is applied to the player's own view angles by
 * {@code PlayerRecoilController}, which means vanilla interpolates it like any other look movement - and it means this hook
 * can never add a roll, a wobble or a second pitch impulse that the player's own aim does not contain. The camera this hook
 * leaves behind is therefore always the live orientation plus the authored animation, and the authored roll of that
 * animation survives because the composition is a quaternion multiply rather than a rebuild from yaw and pitch.</p>
 *
 * <h2>Scope</h2>
 * <p>Nothing happens unless {@link FirstPersonCamera} returns a transform, and that only happens when the active perspective
 * is first person, the camera is attached to the local player and the weapon that authored the animation is the one actually
 * held. Third-person rear and front views, spectator cameras, other entities and every other render pass are untouched, because
 * the gate is checked every time this method runs.</p>
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    /** Vanilla's own rotation setter; it also refreshes the camera's facing vectors. */
    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    /** Vanilla's own position setter; it also updates the block position. */
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    /**
     * Applies the camera-bone animation on top of vanilla's live orientation.
     *
     * @param deltaTracker the frame's delta tracker, used for the partial tick of the composition
     * @param callback     the injection callback
     */
    @Inject(method = "update", at = @At("TAIL"))
    private void argWeaponry$applyCameraEffects(DeltaTracker deltaTracker, CallbackInfo callback) {
        Camera camera = (Camera) (Object) this;
        Minecraft minecraft = Minecraft.getInstance();
        TransformDiagnostics.beginFrame();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        boolean ownCamera = !camera.isDetached() && camera.entity() != null && camera.entity() == minecraft.player;
        float partialTick = minecraft.player == null ? 0.0F
                : camera.getCameraEntityPartialTicks(deltaTracker);

        // Layer 1: the authored camera-bone animation, composed onto the live base orientation.
        CameraTransform transform = FirstPersonCamera.transformForCamera(firstPerson, camera.isDetached(), ownCamera,
                partialTick);
        if (transform != null) {
            CameraComposition.ComposedCamera composed = CameraComposition.compose(camera.yRot(), camera.xRot(),
                    transform);
            this.setRotation(composed.yaw(), composed.pitch());
            if (composed.roll() != 0.0F) {
                camera.rotation().mul(new Quaternionf().rotationZ((float) Math.toRadians(composed.roll())));
            }
            if (transform.hasTranslation()) {
                Vector3f offset = camera.rotation().transform(new Vector3f(transform.translationX(),
                        transform.translationY(), transform.translationZ()));
                this.setPosition(camera.position().x + offset.x, camera.position().y + offset.y,
                        camera.position().z + offset.z);
            }
        }

        if (!firstPerson || !ownCamera) {
            return;
        }
        // Diagnostics only. The orientation that leaves this method is vanilla's live orientation with the authored
        // camera-bone animation composed onto it; firing recoil moves the player's own angles instead, so this hook has
        // no second rotation to add and cannot produce a wobble, a roll impulse or a duplicate pitch kick.
        TransformDiagnostics.camera(partialTick, transform, camera.yRot(), camera.xRot());
    }
}