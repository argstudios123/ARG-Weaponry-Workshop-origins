package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.input.WeaponFireInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns the vanilla attack key into weapon firing while a weapon is held.
 *
 * <h2>Where this hooks in, and why</h2>
 * <p>In this Minecraft version the attack key reaches the world through exactly two methods of
 * {@link Minecraft}:</p>
 * <ul>
 *   <li>{@code startAttack()} — called once per discrete press, from the
 *       {@code while (options.keyAttack.consumeClick())} loop in {@code handleKeybinds()}. It is what attacks an
 *       entity, starts breaking a block, or starts a spectator teleport.</li>
 *   <li>{@code continueAttack(boolean down)} — called once per client tick with {@code down} true while the
 *       button is held and the mouse is grabbed; it is what keeps a block break progressing.</li>
 * </ul>
 * <p>Cancelling {@code startAttack} while a weapon is held therefore suppresses the entity attack, the block
 * break start and the spectator action in one place, and cancelling {@code continueAttack} only while
 * {@code down} is true suppresses the held-button mining path without removing vanilla's stop-destroy cleanup
 * (which runs when {@code down} is false). Nothing is injected into {@code MultiPlayerGameMode} or into entity
 * or block targeting, so attacks with every other item, and attacks against any target chosen by vanilla, are
 * untouched.</p>
 *
 * <p>Only the main hand is considered, because that is the hand the attack key attacks with. A weapon in the
 * off-hand does not change what a left click does.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {

    /**
     * Requests one shot, or opens one trigger session, instead of starting a vanilla attack.
     *
     * <p>Runs once per discrete press, so a held button cannot generate a stream of requests. The vanilla path is
     * cancelled even when nothing is sent (an unsupported fire mode), because the player is holding a weapon and must not
     * punch with it.</p>
     *
     * <p>Which request is sent depends on the weapon's resolved mode: a semi-automatic weapon sends one firing request
     * per press, while an automatic or burst weapon opens a server-owned trigger session that the server paces itself.</p>
     *
     * @param callback the vanilla return value; a cancel returns {@code false}, i.e. "nothing was attacked"
     */
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$requestWeaponFire(CallbackInfoReturnable<Boolean> callback) {
        Minecraft minecraft = (Minecraft) (Object) this;
        LocalPlayer player = minecraft.player;
        if (player == null || !WeaponFireInput.holdsWeapon(player, InteractionHand.MAIN_HAND)) {
            return;
        }
        // Firing itself is driven by the dedicated fire key (WeaponFireKey), so this injection only suppresses vanilla's
        // attack. Processing the same click here as well would fire two shots per press.
        callback.setReturnValue(false);
    }

    /**
     * Suppresses the held-button mining path while a weapon is held.
     *
     * <p>Vanilla calls this once per client tick with {@code down} reflecting the physical attack button, the mouse
     * grab state and the open screen, which is exactly the information an automatic weapon needs: {@code down == true}
     * feeds the heartbeat, {@code down == false} is the release edge. The vanilla branch for {@code down == false} is
     * deliberately left alone - it resets the miss timer and stops an in-progress break.</p>
     *
     * @param down     whether the attack button is held with the mouse grabbed and no screen open
     * @param callback the injection callback
     */
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$suppressHeldAttack(boolean down, CallbackInfo callback) {
        if (!down) {
            // Vanilla uses this branch to stop an in-progress break and to reset the miss timer; leave it alone. The
            // trigger is released by the fire key's own tick handler.
            return;
        }
        Minecraft minecraft = (Minecraft) (Object) this;
        LocalPlayer player = minecraft.player;
        if (player != null && WeaponFireInput.holdsWeapon(player, InteractionHand.MAIN_HAND)) {
            callback.cancel();
        }
    }
}
