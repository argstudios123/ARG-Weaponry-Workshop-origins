package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.MovementLayer;
import com.arg_studios.arg_weaponry.weapon.animation.MovementState;
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
     * The pose the weapon this one replaced was last drawn with, or {@code null}.
     *
     * <p>It is the cross-fade source of the armed draw clip, which is what keeps the hand-off continuous: the incoming weapon
     * is first drawn exactly where the outgoing one ended, and the raise eases in from there.</p>
     */
    private final WeaponPose carriedPose;

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
        this(stack, pack, definition, identity, ammoCount, null, null);
    }

    /**
     * Creates a visual weapon, optionally carrying the pose of the weapon it replaces.
     *
     * @param stack       the stack to draw; copied, never stored by reference
     * @param pack        the content pack that owns the weapon
     * @param definition  the weapon definition
     * @param identity    the identity this visual weapon represents
     * @param ammoCount   the stack's ammunition count at adoption
     * @param carriedPose the last drawn pose of the outgoing weapon, may be {@code null}
     */
    VisualWeapon(ItemStack stack, ContentPack pack, WeaponDefinition definition, WeaponIdentity identity, int ammoCount,
                 WeaponPose carriedPose) {
        this(stack, pack, definition, identity, ammoCount, carriedPose, null);
    }

    /**
     * Creates a visual weapon, optionally carrying the pose and the locomotion track of the weapon it replaces.
     *
     * <p>Both are continuations, and they are deliberately different things: {@code carriedPose} is a <em>single</em> base pose
     * the draw clip fades in from, while {@code locomotionTimeline} is the walking/running <em>state</em> - phase, direction
     * weights, playback rate and overlay weight. A player who swaps weapons while walking keeps walking, so the new weapon has
     * to continue that track instead of starting a fresh cycle at phase zero and fading its overlay in from nothing.</p>
     *
     * @param stack              the stack to draw; copied, never stored by reference
     * @param pack               the content pack that owns the weapon
     * @param definition         the weapon definition
     * @param identity           the identity this visual weapon represents
     * @param ammoCount          the stack's ammunition count at adoption
     * @param carriedPose        the last drawn pose of the outgoing weapon, may be {@code null}
     * @param locomotionTimeline the outgoing weapon's locomotion state, may be {@code null}
     */
    VisualWeapon(ItemStack stack, ContentPack pack, WeaponDefinition definition, WeaponIdentity identity, int ammoCount,
                 WeaponPose carriedPose, MovementLayer.Timeline locomotionTimeline) {
        this.stack = stack.copy();
        this.pack = pack;
        this.definition = definition;
        this.identity = identity;
        this.ammoAtAdoption = ammoCount;
        this.carriedPose = carriedPose;
        this.animator = new WeaponViewController();
        this.animator.activate(new WeaponViewController.Owner(identity.slot(), identity.itemId()), pack, definition,
                ammoCount);
        // [Movement] The walk is the player's, not the weapon's: continue the track the outgoing weapon was playing.
        this.animator.carryLocomotionFrom(locomotionTimeline);
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
        animator.tick(pack, definition, ammoCount, deltaSeconds, MovementState.STILL);
    }

    /**
     * Advances this weapon's timeline by one client tick, including its movement overlay.
     *
     * @param ammoCount    the ammunition count to select the idle clip with
     * @param deltaSeconds the time the tick covered
     * @param movement     the local player's movement this tick, never {@code null}
     */
    public void tick(int ammoCount, double deltaSeconds, MovementState movement) {
        animator.tick(pack, definition, ammoCount, deltaSeconds, movement);
    }

    /**
     * Advances this weapon's timeline by one client tick, including its movement overlay and its aiming state.
     *
     * @param ammoCount    the ammunition count to select the idle clip with
     * @param deltaSeconds the time the tick covered
     * @param movement     the local player's movement this tick, never {@code null}
     * @param aiming       whether the player is holding aim right now
     */
    public void tick(int ammoCount, double deltaSeconds, MovementState movement, boolean aiming) {
        animator.tick(pack, definition, ammoCount, deltaSeconds, movement, aiming);
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

    /**
     * Arms the draw clip on this weapon's animator: it is started at its first frame, faded in from the pose the weapon this
     * one replaced was last drawn with, and paused until the weapon is really drawn.
     *
     * @param clipName the resolved clip
     * @param seconds  the authored length of that clip
     * @return {@code true} when the clip was armed
     */
    public boolean armDraw(String clipName, double seconds) {
        return animator.armEquip(pack, definition, clipName, seconds, carriedPose);
    }

    /**
     * Starts playback of an armed draw clip, from the frame in which this weapon is first drawn.
     *
     * @return {@code true} when this call started the playback
     */
    public boolean releaseDrawPlayback() {
        return animator.releaseEquipPlayback();
    }

    /** @return {@code true} while the draw clip is armed and waiting for its first rendered frame. */
    public boolean isAwaitingFirstFrame() {
        return animator.isEquipAwaitingFirstFrame();
    }

    /** @return the pose the last rendered frame of this weapon had, or {@code null}. */
    public WeaponPose lastDisplayedPose() {
        return animator.lastDisplayedPose();
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
