package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.render.WeaponCrosshair;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla crosshair while an ARG weapon is being inspected or aimed.
 *
 * <p>{@code Hud#extractCrosshair} is the single place vanilla submits the crosshair sprite, so cancelling it there hides
 * nothing else - the hotbar, the effects and every other overlay keep their vanilla behaviour. The decision is the pure
 * rule in {@link WeaponCrosshair}: a running inspection in a hand that holds an ARG weapon, or an aim transition that is
 * still anywhere between hip fire and full aim.</p>
 *
 * <p>It is deliberately not tied to an animation: the rule reads the accepted inspection session and the aiming state, so
 * it starts and ends with the action itself. A cross-fade, a finished clip or a paused clip therefore cannot leave the
 * crosshair hidden, and the crosshair comes back on the first frame after the aim is released.</p>
 */
@Mixin(Hud.class)
public abstract class HudCrosshairMixin {

    /**
     * Hides the crosshair for one frame while the held weapon is being inspected or aimed.
     *
     * @param graphics the GUI extractor
     * @param delta    the frame delta
     * @param callback the injection callback
     */
    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$hideCrosshairWhileInspecting(GuiGraphicsExtractor graphics, DeltaTracker delta,
                                                          CallbackInfo callback) {
        if (WeaponCrosshair.isHidden()) {
            callback.cancel();
        }
    }
}
