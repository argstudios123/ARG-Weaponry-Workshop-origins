package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.aiming.WeaponAiming;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses to <em>start</em> a sprint while the local player is aiming.
 *
 * <h2>Why the setter and not only the input handler</h2>
 * <p>Vanilla decides to sprint in {@code LocalPlayer#aiStep}, from the sprint key, the forward impulse and a handful of
 * conditions, and then calls {@code setSprinting(true)}. Cancelling that call is the only place that covers every caller -
 * the key, a controller, another mod, or vanilla's own "keep sprinting" path - without duplicating vanilla's conditions and
 * without racing it. Clearing a running sprint is the aiming controller's job
 * ({@code WeaponAiming#clientTick} calls {@code setSprinting(false)} on the tick aiming is held), which this injection never
 * blocks: only {@code true} is refused.</p>
 *
 * <p>The decision itself is refused earlier as well, by {@link LocalPlayerSprintAimMixin}, so vanilla does not even build a
 * {@code START_SPRINTING} command while aiming; this injection remains the backstop for every path that is not the local
 * player's own input handling.</p>
 *
 * <p>The check is limited to the local player, so no other entity's sprinting is affected, and the mixin is listed in the
 * client mixin configuration, so a dedicated server never loads it.</p>
 */
@Mixin(Entity.class)
public abstract class EntitySprintAimMixin {

    /**
     * Cancels a sprint start while aiming is held.
     *
     * @param sprinting the requested state
     * @param callback  the injection callback
     */
    @Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$refuseSprintWhileAiming(boolean sprinting, CallbackInfo callback) {
        if (!sprinting) {
            return;
        }
        Entity entity = (Entity) (Object) this;
        if (!(entity instanceof LocalPlayer)) {
            return;
        }
        if (WeaponAiming.blocksSprint()) {
            callback.cancel();
        }
    }
}
