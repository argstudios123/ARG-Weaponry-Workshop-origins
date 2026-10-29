package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.aiming.WeaponAiming;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the aim-down-sights zoom to the world field of view.
 *
 * <h2>Where, and why there</h2>
 * <p>{@code Camera#calculateFov} is the single place vanilla computes the field of view it then hands to
 * {@code setupPerspective}, so multiplying its result zooms the world projection and nothing else. The result also feeds
 * vanilla's own culling frustum and near-plane maths, which is why the zoom is applied here rather than by scaling the
 * projection matrix afterwards: culling stays correct at every zoom level.</p>
 *
 * <p>{@code calculateHudFov} is deliberately <em>not</em> touched. It is the field of view the first-person hand and the
 * weapon are drawn with, so leaving it alone means aiming changes how much of the world the player sees without distorting
 * the weapon they are looking down.</p>
 *
 * <p>The multiplier is {@code 1.0} whenever the player is not aiming, so a weapon with aiming disabled, a refused aim (a
 * running reload or inspection, a missing {@code aiming_view} bone) and every non-weapon situation leave the field of view
 * exactly as vanilla computed it.</p>
 */
@Mixin(Camera.class)
public abstract class CameraFovMixin {

    /**
     * Multiplies the world field of view by the current aim zoom.
     *
     * @param partialTicks   the frame's partial tick, also used to interpolate the transition
     * @param callbackInfo   the injection callback
     */
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void argWeaponry$applyAimZoom(float partialTicks, CallbackInfoReturnable<Float> callbackInfo) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null
                || !minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }
        float multiplier = WeaponAiming.fovMultiplier(partialTicks);
        if (multiplier == 1.0F) {
            return;
        }
        float fov = callbackInfo.getReturnValueF() * multiplier;
        callbackInfo.setReturnValue(fov);
    }
}
