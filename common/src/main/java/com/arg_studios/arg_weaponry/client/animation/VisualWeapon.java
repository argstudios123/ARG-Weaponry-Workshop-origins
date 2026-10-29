package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.handoff.WeaponIdentity;
import net.minecraft.world.item.ItemStack;

/**
 * One weapon that is being drawn in a hand: a stack snapshot, its definition, and <strong>its own animator</strong>.
 *
 * <p>This is the central difference from the subsystem it replaces. Previously there was one animator per hand, so a weapon
 * switch reset the animator that belonged to the outgoing weapon - which both destroyed its final pose and forced the
 * incoming weapon to start from a clip that had never been rendered. Here every visual weapon owns an animator for as long as
 * it is the visual weapon, so:</p>
 * <ul>
 *     <li>the outgoing weapon keeps animating (and its holster clip keeps playing) while the player already asked for a
 *     different slot;</li>
 *     <li>the incoming weapon gets a brand new animator, whose idle clip has never been sampled, so its draw clip starts at its
 *     authored first frame instead of fading in from an idle pose that was invented in the same tick;</li>
 *     <li>losing either weapon simply discards that one animator; nothing is shared, so nothing can be reset from under
 *     another weapon.</li>
 * </ul>
 *
 * <p>The stack is a <em>snapshot</em>. The outgoing weapon therefore survives its stack being replaced or removed from the
 * inventory for the length of its holster, which is exactly what the renderer needs.</p>
 */
public final class VisualWeapon {

    private final ItemStack stack;
    private final ContentPack pack;
    private final WeaponDefinition definition;
    private final WeaponIdentity identity;
    private final WeaponViewController animator;
    private final int ammoAtAdoption;

    /**
     * Creates a visual weapon and starts its idle playback immediately, so the weapon is renderable from the very first frame
     * after it is adopted.
     *
     * @param stack      the stack to draw; copied, never stored by reference
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition
     * @param identity   the identity this visual weapon represents
     * @param ammoCount  the stack's ammunition count at adoption
     */
    VisualWeapon(ItemStack stack, ContentPack pack, WeaponDefinition definition, WeaponIdentity identity, int ammoCount) {
        this.stack = stack.copy();
        this.pack = pack;
        this.definition = definition;
        this.identity = identity;
        this.ammoAtAdoption = ammoCount;
        this.animator = new WeaponViewController();
        this.animator.activate(new WeaponViewController.Owner(identity.slot(), identity.itemId()), pack, definition,
                ammoCount);
    }

    /** @return the snapshot the renderer must draw. Never mutated. */
    public ItemStack stack() {
        return stack;
    }

    /** @return the content pack that owns the weapon. */
    public ContentPack pack() {
        return pack;
    }

    /** @return the weapon definition. */
    public WeaponDefinition definition() {
        return definition;
    }

    /** @return the identity this visual weapon represents. */
    public WeaponIdentity identity() {
        return identity;
    }

    /** @return the ammunition count the weapon had when it was adopted. */
    public int ammoAtAdoption() {
        return ammoAtAdoption;
    }

    /** @return the animator that belongs to this weapon alone. */
    public WeaponViewController animator() {
        return animator;
    }

    /**
     * @param ammoCount    the ammunition count to select the idle clip with
     * @param deltaSeconds the time this tick covered
     */
    public void tick(int ammoCount, double deltaSeconds) {
        animator.tick(pack, definition, ammoCount, deltaSeconds);
    }

    /**
     * @param ammoCount   the ammunition count to select the idle clip with
     * @param partialTick the frame's partial tick
     * @return the pose this weapon must be drawn with, or {@code null} when it animates nothing
     */
    public WeaponPose poseFor(int ammoCount, float partialTick) {
        return animator.poseFor(pack, definition, ammoCount, partialTick);
    }

    /**
     * Starts a draw or holster clip on this weapon's animator, at its authored speed.
     *
     * @param clipName the resolved clip
     * @param seconds  the authored length of that clip, used as the window so the playback rate is exactly one
     * @return {@code true} when the clip started
     */
    public boolean playEquip(String clipName, double seconds) {
        return animator.playEquip(pack, definition, clipName, seconds);
    }

    /** @return {@code true} when the running equip clip reached its end. */
    public boolean equipClipFinished() {
        return animator.isEquipClipFinished();
    }

    /** Ends the running equip clip and continues this weapon's idle playback. */
    public void finishEquip() {
        animator.finishEquip(pack, definition);
    }

    /** Stops playback; the weapon is no longer drawn after this call. */
    public void dispose() {
        animator.reset();
    }
}
