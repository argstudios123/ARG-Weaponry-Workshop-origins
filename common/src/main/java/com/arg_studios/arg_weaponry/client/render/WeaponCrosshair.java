package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.client.aiming.WeaponAiming;
import com.arg_studios.arg_weaponry.client.input.WeaponInspectInput;
import com.arg_studios.arg_weaponry.client.input.WeaponReloadInput;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The crosshair rule for the weapon framework: the central crosshair is hidden while the held weapon is inspecting,
 * reloading or while the player is aiming down its sights.
 *
 * <h2>Why it reads state, not animation</h2>
 * <p>Both actions are owned by authoritative client state - the accepted inspection session and the aim transition -
 * and neither is derived from the pose that happens to be on screen. A cross-fade, a finished clip, a paused clip or a frame
 * that draws twice therefore cannot leave the crosshair hidden, and nothing has to be restored when the action ends: the
 * very next frame simply asks the state again and the crosshair is back.</p>
 *
 * <p>Reloading follows the same rule through {@code WeaponReloadInput}: it is true for the whole accepted reload, including
 * a staged sequence, and false again as soon as the server reports completion or cancellation. A reload animation that is
 * merely present, finished or paused never decides anything on its own.</p>
 *
 * <h2>Aiming</h2>
 * <p>{@link WeaponAiming#isAiming()} is true on the tick the aim is requested (in toggle <em>and</em> hold mode) and
 * stays true until the transition has blended fully back out, so the crosshair disappears with the first frame of the
 * aim and only returns once the weapon is back at hip fire. The rule is a pure boolean function of the two states, which
 * is what lets it be unit tested without a running game.</p>
 *
 * <p>A player who is not holding an ARG weapon is never affected, and an inspection hides the crosshair exactly as
 * before.</p>
 */
public final class WeaponCrosshair {

    private WeaponCrosshair() {
    }

    /**
     * @return whether the vanilla crosshair must be hidden for this frame
     */
    public static boolean isHidden() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            return false;
        }
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        boolean weaponHeld = stack.getItem() instanceof WeaponItem;
        if (!weaponHeld) {
            return false;
        }
        boolean inspecting = WeaponInspectInput.isInspecting(InteractionHand.MAIN_HAND);
        boolean reloading = WeaponReloadInput.isReloading(InteractionHand.MAIN_HAND);
        return shouldHide(inspecting, true, WeaponAiming.isAiming(), reloading);
    }

    /**
     * The pure rule behind {@link #isHidden()}.
     *
     * @param inspecting whether an inspection is running in the main hand
     * @param weaponHeld whether that hand holds an ARG weapon
     * @return whether the crosshair must be hidden by the inspection alone
     */
    public static boolean shouldHide(boolean inspecting, boolean weaponHeld) {
        return shouldHide(inspecting, weaponHeld, false, false);
    }

    /**
     * The pure rule behind {@link #isHidden()}, including aiming.
     *
     * @param inspecting whether an inspection is running in the main hand
     * @param weaponHeld whether that hand holds an ARG weapon
     * @param aiming     whether the player is aiming the held weapon right now, see {@link WeaponAiming#isAiming()}
     * @return whether the crosshair must be hidden
     */
    public static boolean shouldHide(boolean inspecting, boolean weaponHeld, boolean aiming) {
        return shouldHide(inspecting, weaponHeld, aiming, false);
    }

    /**
     * The pure rule behind {@link #isHidden()}, including aiming and reloading.
     *
     * <p>Reloading is the authoritative client-side reload state from {@link WeaponReloadInput}, not the presence of a
     * reload animation: it becomes true when the server accepts a reload and false when that reload completes, is
     * cancelled or refused. A staged reload therefore hides the crosshair for the whole sequence, because the tracker
     * stays in flight across its stages, and the crosshair is restored on the very next frame after the terminal
     * outcome. An unrelated item in the main hand still short-circuits before any of this is consulted.</p>
     *
     * @param inspecting whether an inspection is running in the main hand
     * @param weaponHeld whether that hand holds an ARG weapon
     * @param aiming     whether the player is aiming the held weapon right now
     * @param reloading  whether the main-hand weapon has an accepted reload in flight
     * @return whether the crosshair must be hidden
     */
    public static boolean shouldHide(boolean inspecting, boolean weaponHeld, boolean aiming, boolean reloading) {
        return weaponHeld && (inspecting || aiming || reloading);
    }
}
