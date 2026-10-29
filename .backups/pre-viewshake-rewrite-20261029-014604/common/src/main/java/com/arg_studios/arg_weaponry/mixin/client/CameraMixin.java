package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.camera.CameraComposition;
import com.arg_studios.arg_weaponry.client.camera.CameraTransform;
import com.arg_studios.arg_weaponry.client.camera.FirstPersonCamera;
import com.arg_studios.arg_weaponry.client.diagnostics.TransformDiagnostics;
import com.arg_studios.arg_weaponry.client.recoil.ViewShakeController;
import com.arg_studios.arg_weaponry.client.recoil.WeaponRecoil;
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
 * <h2>The composition order, and the hotfix</h2>
 * <ol>
 *   <li><strong>Vanilla</strong> has already produced the camera orientation from the player's angles, interpolated with this
 *   frame's partial tick - mouse movement included. That value is the <em>base</em>.</li>
 *   <li><strong>The authored camera-bone animation</strong> is composed onto that base by
 *   {@link CameraComposition#compose(float, float, CameraTransform)}, which takes the base as an argument. Milestone 10S
 *   instead applied an absolute orientation that had been sampled on the client tick, which froze the view to 20 Hz for as
 *   long as an animated clip played; composing against the live base removes that without giving up the tick-owned
 *   timeline.</li>
 *   <li><strong>Screen shake</strong> is added afterwards, as a bounded transient offset that is recomputed from scratch every
 *   frame (never accumulated), sampled with the frame's partial tick.</li>
 * </ol>
 * <p>Nothing here keeps a running total: every frame starts from the base vanilla produced and finishes with the two layers
 * of that frame, so repeating a frame with the same inputs gives the same output, and when both layers are zero the camera is
 * exactly vanilla's.</p>
 *
 * <h2>Scope</h2>
 * <p>Nothing happens unless {@link FirstPersonCamera} returns a transform, and that only happens when the active perspective
 * is first person, the camera is attached to the local player and the weapon that authored the animation is the one actually
 * held. Third-person rear and front views, spectator cameras, other entities and every other render pass are untouched, because
 * the gate is checked every time this method runs. Screen shake is gated by the same two facts.</p>
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
     * Applies the camera-bone animation and then the screen shake, both on top of vanilla's live orientation.
     *
     * @param deltaTracker the frame's delta tracker, used for the partial tick of both layers
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

        // Layer 2: screen shake. A separate, bounded response that is recomputed from the current elapsed time every
        // frame, so it cannot accumulate, and it never touches the player's own view rotation. It is composed as a
        // rotation about the camera's own axes *after* the authored animation, so it cannot discard that animation's
        // roll and cannot mix yaw into roll at a non-zero pitch - both of which the previous angle-rewriting version did
        // for every frame the shake was active.
        if (!firstPerson || !ownCamera) {
            return;
        }
        ViewShakeController.ShakeAngles shake = WeaponRecoil.shake(partialTick);
        if (!shake.isZero()) {
            camera.rotation().mul(CameraComposition.shakeRotation(shake));
        }

        TransformDiagnostics.camera(partialTick, transform, camera.yRot(), camera.xRot(), shake);
    }
}
