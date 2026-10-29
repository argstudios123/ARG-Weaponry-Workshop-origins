package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The rendering-facing face of the handoff subsystem: the one question the first-person pass and the camera hook ask.
 *
 * <p>Before the rewrite this class did not exist, and every renderer read {@code player.getItemInHand(hand)} directly. That
 * single line was the structural reason the outgoing weapon could not be drawn: the moment the player's slot changed, the
 * renderer had nothing left that described the weapon it was supposed to be playing the holster of. The visual weapon
 * registry answers the question instead, so the renderer keeps drawing the outgoing weapon until the handoff boundary and
 * the incoming weapon from the boundary on.</p>
 *
 * <p>Every method is a pure read of state that the client tick owns; nothing here ticks, adopts or mutates anything except
 * {@link #release(InteractionHand)}, which is the lifecycle call a hand that stops holding a weapon makes.</p>
 */
public final class FirstPersonVisualWeapons {

    private FirstPersonVisualWeapons() {
    }

    /**
     * @param hand the hand being rendered
     * @return the stack the renderer must draw for that hand: the visual weapon's snapshot, or the live stack when that hand
     *         draws no visual weapon
     */
    public static ItemStack stackFor(InteractionHand hand) {
        return VisualWeapons.instance().stackFor(hand);
    }

    /**
     * @param hand        the hand being rendered
     * @param partialTick the frame's partial tick
     * @return the pose the visual weapon must be drawn with, or {@code null} when it animates nothing
     */
    public static WeaponPose poseFor(InteractionHand hand, float partialTick) {
        return VisualWeapons.instance().poseFor(hand, partialTick);
    }

    /**
     * @param hand   the hand being rendered
     * @param stack  the stack the renderer is about to draw
     * @return whether that stack is a weapon this mod draws itself
     */
    public static boolean isWeaponStack(InteractionHand hand, ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof WeaponItem;
    }

    /**
     * Drops the visual weapon of a hand that no longer holds one, so the next frame falls back to the live hand.
     *
     * @param hand the hand
     */
    public static void release(InteractionHand hand) {
        VisualWeapons.instance().release(hand);
    }
}
