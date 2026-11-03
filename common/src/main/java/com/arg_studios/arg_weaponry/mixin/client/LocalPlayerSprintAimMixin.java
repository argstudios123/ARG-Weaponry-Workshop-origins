package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.aiming.WeaponAiming;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses the <em>decision</em> to sprint while the local player is aiming.
 *
 * <h2>Why the gate is at the decision and not only at the setter</h2>
 * <p>{@code LocalPlayer#aiStep} asks {@code canStartSprinting()} before it calls {@code setSprinting(true)}, from two
 * branches: the sprint key and the double-tap sprint window. Refusing the decision there means vanilla never even tries, so
 * no {@code START_SPRINTING} command can reach the server on the tick the player aims - which is the one-tick window the
 * {@code setSprinting} guard alone cannot close, because vanilla sends that command as soon as the flag differs from the
 * previous tick.</p>
 *
 * <p>The setter guard ({@link EntitySprintAimMixin}) stays in place as the backstop for every other caller, and the
 * controller stops an already running sprint on the tick aiming starts. Together they cover hold-to-aim and toggle aiming
 * without duplicating a single vanilla condition: this injection only adds one reason to an existing negative decision.</p>
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSprintAimMixin {

    /**
     * Cancels the sprint decision while aiming.
     *
     * @param callback the injection callback
     */
    @Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$refuseSprintStartWhileAiming(CallbackInfoReturnable<Boolean> callback) {
        if (WeaponAiming.blocksSprint()) {
            callback.setReturnValue(false);
        }
    }
}
