package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.client.camera.FirstPersonCamera;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.reload.ReloadRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The gameplay-facing animation API of the held weapons.
 *
 * <p>This class used to own one animation controller per <em>hand</em>, which is the structural fault the handoff rewrite
 * removed: a weapon switch rebound that controller to the new weapon, resetting the outgoing weapon's playback (and its pose)
 * in the same tick the slot changed. It is now a delegating facade over {@link VisualWeapons}, where every visual weapon owns
 * its own animator, so nothing is reset when the player switches.</p>
 *
 * <p>Everything that calls this class keeps working unchanged: the fire, reload and inspection inputs, the camera hook and the
 * animation tester all resolve the animator of the weapon that is actually being drawn. The class holds no state of its own -
 * the only mutable playback state lives in {@link VisualWeapon} and the only transition state lives in
 * {@link WeaponHandoffManager}.</p>
 */
public final class WeaponViewControllers {

    private static final WeaponViewControllers INSTANCE = new WeaponViewControllers();

    private WeaponViewControllers() {
    }

    /** @return the shared facade. */
    public static WeaponViewControllers instance() {
        return INSTANCE;
    }

    /**
     * Samples the pose of one hand for a rendered frame, without advancing playback.
     *
     * @param hand        the hand being rendered
     * @param partialTick the fraction of a client tick elapsed
     * @return the pose to render, or {@code null} when that hand draws no weapon pose
     */
    public synchronized WeaponPose poseFor(InteractionHand hand, float partialTick) {
        return FirstPersonVisualWeapons.poseFor(hand, partialTick);
    }

    /**
     * Samples the main hand's pose for a rendered frame. This is what the camera-bone animation reads, so the level camera
     * and the weapon model are always evaluated from the same animator and the same partial tick.
     *
     * @param partialTick the frame's partial tick
     * @return the pose, or {@code null} when the main hand draws no weapon pose
     */
    public synchronized WeaponPose mainHandPoseForFrame(float partialTick) {
        // The camera hook runs before the first-person pass, so it is often the first code to sample a pose in a frame. A
        // slot change that arrived between two ticks must therefore be observed here as well, or the camera would ask the
        // registry to adopt the live stack (creating an idle clip that was never drawn) before the state machine could start
        // the draw. The observation is idempotent: the second call in the same frame sees no change.
        WeaponHandoffManager.instance().beforeFirstPersonFrame();
        return poseFor(InteractionHand.MAIN_HAND, partialTick);
    }

    /**
     * Advances the subsystem by one client tick: every visual weapon's own timeline, then the handoff state machine, then the
     * camera-bone owner.
     *
     * <p>This is the single owner of playback time. It runs from both loaders' client tick, so a clip keeps progressing while
     * the first-person renderer is not drawing at all - third-person view, an open screen, a hidden hand - and returning to
     * first person samples the same timeline instead of restarting a frozen one.</p>
     */
    public synchronized void tickHeldWeapons() {
        Minecraft minecraft = Minecraft.getInstance();
        // The sprint restriction and the animation-side suppression read the same intent rule: a held aim during a
        // temporary refusal (reload, inspect, draw) must not let a run clip play even though normal ADS is paused.
        WeaponHandoffManager.instance().clientTick(minecraft,
                com.arg_studios.arg_weaponry.client.aiming.WeaponAiming.blocksSprint());
        LocalPlayer player = minecraft.player;
        if (player == null) {
            FirstPersonCamera.clear();
            return;
        }
        // The camera animation belongs to the weapon that is actually being drawn, which is the visual weapon and not
        // necessarily the selected one.
        ItemStack main = FirstPersonVisualWeapons.stackFor(InteractionHand.MAIN_HAND);
        if (main.getItem() instanceof WeaponItem item) {
            FirstPersonCamera.update(item);
        } else {
            FirstPersonCamera.clear();
        }
    }

    /**
     * Starts the firing animation of an accepted shot.
     *
     * @param hand the hand the shot came from
     * @return {@code true} when a firing animation started
     */
    public synchronized boolean playFireAnimation(InteractionHand hand) {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        return weapon != null && weapon.animator().playFire(weapon.pack(), weapon.definition(), ammoOf(hand, weapon));
    }

    /**
     * Starts the reload animation the server accepted.
     *
     * @param hand            the hand the reload belongs to
     * @param animation       the declared reload animation
     * @param durationSeconds the server-side duration
     * @return {@code true} when a reload animation started
     */
    public synchronized boolean playReloadAnimation(InteractionHand hand, String animation, double durationSeconds) {
        return playReloadAnimation(hand, animation, durationSeconds,
                ReloadRules.DEFAULT_TRANSITION_SECONDS);
    }

    /**
     * Starts one staged reload clip at the cross-fade length the entry declared.
     *
     * <p>The fade is a render-side detail: it overlaps the clip that is starting, so the clip still begins at the moment
     * the server scheduled and no insertion, iteration or completion moves by a millisecond.</p>
     *
     * @param hand              the hand the reload belongs to
     * @param animation         the declared reload animation
     * @param durationSeconds   the server-side duration of this clip
     * @param transitionSeconds the cross-fade into it, {@code 0} to switch without blending
     * @return {@code true} when a reload animation started
     */
    public synchronized boolean playReloadAnimation(InteractionHand hand, String animation, double durationSeconds,
                                                    double transitionSeconds) {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        return weapon != null
                && weapon.animator().playReload(weapon.pack(), weapon.definition(), animation, durationSeconds,
                        transitionSeconds);
    }

    /**
     * Ends the reload animation of one hand and returns playback to idle.
     *
     * @param hand the hand whose reload ended
     */
    public synchronized void finishReloadAnimation(InteractionHand hand) {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        if (weapon != null) {
            weapon.animator().finishReload(weapon.pack(), weapon.definition());
        }
    }

    /**
     * Starts the inspection animation the server accepted.
     *
     * @param hand            the hand the inspection belongs to
     * @param animation       the declared inspection animation
     * @param durationSeconds the server-side duration
     * @return {@code true} when an inspection animation started
     */
    public synchronized boolean playInspectAnimation(InteractionHand hand, String animation, double durationSeconds) {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        return weapon != null
                && weapon.animator().playInspect(weapon.pack(), weapon.definition(), animation, durationSeconds);
    }

    /**
     * Ends the inspection animation of one hand and returns playback to idle.
     *
     * @param hand the hand whose inspection ended
     */
    public synchronized void finishInspectAnimation(InteractionHand hand) {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        if (weapon != null) {
            weapon.animator().finishInspect(weapon.pack(), weapon.definition());
        }
    }

    /**
     * Starts a draw or holster clip on the active visual weapon.
     *
     * <p>Kept for the legacy call path only: the handoff subsystem starts its own clips through its visuals port, because it
     * needs the authored clip length back in order to build the transition deadline.</p>
     *
     * @param hand            the hand whose weapon is being drawn or holstered
     * @param animation       the declared clip name
     * @param durationSeconds the authored window
     * @return {@code true} when the clip started
     */
    public synchronized boolean playEquipAnimation(InteractionHand hand, String animation, double durationSeconds) {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        return weapon != null
                && weapon.animator().playEquip(weapon.pack(), weapon.definition(), animation, durationSeconds);
    }

    /**
     * Ends one named draw or holster clip of the active visual weapon.
     *
     * @param hand the hand whose equip clip should end
     * @param clip the clip the caller started, or {@code null} to end whatever is equipping
     */
    public synchronized void finishEquipAnimation(InteractionHand hand, String clip) {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        if (weapon != null) {
            weapon.animator().finishEquip(weapon.pack(), weapon.definition(), clip);
        }
    }

    /**
     * Ends whatever draw or holster clip one hand's visual weapon is playing.
     *
     * @param hand the hand whose equip clip ended
     */
    public synchronized void finishEquipAnimation(InteractionHand hand) {
        finishEquipAnimation(hand, null);
    }

    /**
     * Drops the visual weapon of one hand, used when that hand no longer holds a weapon.
     *
     * @param hand the hand whose visual weapon must be released
     */
    public synchronized void release(InteractionHand hand) {
        FirstPersonVisualWeapons.release(hand);
    }

    /** Drops every session and visual weapon. Used by resource reloads and when the client world or player changes. */
    public synchronized void reset() {
        WeaponHandoffManager.instance().reset();
    }

    private int ammoOf(InteractionHand hand, VisualWeapon weapon) {
        LocalPlayer player = Minecraft.getInstance().player;
        ItemStack live = player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
        if (live.getItem() instanceof WeaponItem item
                && weapon.identity().sameAs(VisualWeapons.liveIdentity(hand))) {
            return item.animationAmmoCount(live);
        }
        return weapon.ammoAtAdoption();
    }
}
