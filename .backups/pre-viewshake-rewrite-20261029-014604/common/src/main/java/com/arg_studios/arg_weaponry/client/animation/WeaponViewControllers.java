package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.client.camera.FirstPersonCamera;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The transient registry of first-person weapon view controllers.
 *
 * <p>One controller exists per {@link WeaponViewKey} — a player and a hand — and only while that hand
 * actually holds a weapon. Nothing here is persisted; the map is rebuilt from the held item every time
 * the client renders a hand.</p>
 *
 * <h2>Ownership changes</h2>
 * <p>A controller is re-bound whenever the {@link WeaponViewController.Owner} of the held stack changes:
 * a different hotbar slot or a different item. Re-binding resets the old playback first, so a controller
 * can never keep animating a weapon it no longer belongs to.</p>
 * <p>Mutable weapon state is deliberately not part of ownership. Ammunition is handed to the controller
 * on every frame as an animation-selection input, so a magazine change re-evaluates the idle selection
 * without restarting playback, and a fire-mode change does not reach the controller at all. Two different
 * stacks of the same item in the same slot cannot be told apart without a persistent stack UUID, which
 * this project does not have; that limitation is documented on the controller.</p>
 *
 * <h2>World and player changes</h2>
 * <p>The registry is cleared when the client level instance changes (new world, dimension change or
 * respawn) and when the player changes. Comparing the level <em>reference</em> is how vanilla represents
 * "the current client world"; it is not weapon identity and is never used as such.</p>
 *
 * <h2>Resource reloads</h2>
 * <p>{@link #reset()} drops every controller, so no controller can keep an animation document or a pose
 * from a previous resource generation. {@code WeaponRenderer.invalidateCaches()} calls it together with
 * the other caches.</p>
 */
public final class WeaponViewControllers {

    private static final WeaponViewControllers INSTANCE = new WeaponViewControllers();

    private final Map<WeaponViewKey, WeaponViewController> controllers = new HashMap<>();

    private UUID lastPlayerId;
    private Level lastLevel;
    private long lastTickNanos;

    private WeaponViewControllers() {
    }

    /** @return the shared registry. */
    public static WeaponViewControllers instance() {
        return INSTANCE;
    }

    /**
     * Returns the pose of the held weapon's controller, binding it first when ownership changed.
     *
     * <p>This is the only method that advances gameplay playback, and it is called once per hand per
     * rendered frame from the first-person hook.</p>
     *
     * @param hand  the hand being rendered
     * @param item  the weapon item held in that hand
     * @param stack the exact stack held in that hand
     * @return the pose to render, or {@code null} when the weapon has no idle animation to show
     */
    /**
     * Samples the pose of one hand for a rendered frame, without advancing playback.
     *
     * <p>The renderer calls this at most once per hand per frame, which is why the name says what it does: playback time
     * belongs to the client tick ({@link #tickHeldWeapons()}), and a render pass may only look at it. The partial tick is
     * used to sample slightly ahead of the last tick, so a 20 Hz timeline still looks smooth at any frame rate.</p>
     *
     * @param hand        the hand being rendered
     * @param item        the weapon item held in that hand
     * @param stack       the exact stack held in that hand
     * @param partialTick the fraction of a client tick elapsed, {@code 0} for no interpolation
     * @return the pose to render, or {@code null} when the weapon has no animation to show
     */
    public synchronized WeaponPose poseFor(InteractionHand hand, WeaponItem item, ItemStack stack,
                                           float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            reset();
            return null;
        }
        WeaponViewController controller = bind(player, hand, item, stack);
        if (controller == null) {
            return null;
        }
        return controller.poseFor(item.pack(), item.definition(), item.animationAmmoCount(stack), partialTick);
    }

    /**
     * Samples the main hand's pose for a rendered frame, without advancing anything.
     *
     * <p>This is what the camera-bone animation reads, so the level camera and the weapon model are always evaluated from the
     * <em>same</em> animation timestamp and the same partial tick. Sampling twice in one frame returns the same pose (the
     * controller caches it), so calling this from the camera hook as well as from the weapon pass costs nothing and cannot
     * produce two different answers.</p>
     *
     * @param partialTick the frame's partial tick
     * @return the pose, or {@code null} when the main hand holds no weapon or nothing is playing
     */
    public synchronized WeaponPose mainHandPoseForFrame(float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(stack.getItem() instanceof WeaponItem item)) {
            return null;
        }
        WeaponViewController controller = bind(player, InteractionHand.MAIN_HAND, item, stack);
        if (controller == null) {
            return null;
        }
        return controller.poseFor(item.pack(), item.definition(), item.animationAmmoCount(stack), partialTick);
    }

    /**
     * Advances every held weapon's animation by one client tick.
     *
     * <p>This is the single owner of playback time. It runs from both loaders' client tick, so a reload or an inspection
     * keeps progressing while the first-person renderer is not drawing at all - third-person view, an open screen, a
     * hidden hand - and returning to first person samples the same timeline instead of restarting or resuming a frozen
     * one.</p>
     *
     * <p>The elapsed time is measured here rather than taken from a tick constant, so a lagging client still advances a
     * clip by the time that really passed (clamped to a quarter second). Controllers of hands that no longer hold a
     * weapon are dropped, which is what ends an action's animation when the player switches items.</p>
     */
    public synchronized void tickHeldWeapons() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            reset();
            return;
        }
        double deltaSeconds = elapsedSinceLastTick();
        WeaponViewController mainHand = null;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof WeaponItem item)) {
                releaseIfPresent(player, hand);
                continue;
            }
            WeaponViewController controller = bind(player, hand, item, stack);
            if (controller == null) {
                continue;
            }
            controller.tick(item.pack(), item.definition(), item.animationAmmoCount(stack), deltaSeconds);
            if (hand == InteractionHand.MAIN_HAND) {
                mainHand = controller;
            }
        }
        // The camera-bone animation only records which weapon owns it here; the transform itself is sampled per frame from
        // the same pose the renderer draws (see FirstPersonCamera). Reading the player's view angles on the tick is what
        // froze the camera to 20 Hz in 10S, so the tick no longer has any say in the camera's orientation.
        ItemStack mainStack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (mainStack.getItem() instanceof WeaponItem mainItem) {
            FirstPersonCamera.update(mainItem);
        } else {
            FirstPersonCamera.clear();
        }
    }

    /**
     * Binds (or rebinds) the controller of one hand to the weapon it currently holds.
     *
     * <p>A controller is re-bound whenever its owner changes - a different hotbar slot or a different item - and the old
     * playback is dropped first, so a controller can never keep animating a weapon it no longer belongs to. Ammunition is
     * not part of ownership: it is handed in on every call as an animation-selection input.</p>
     *
     * @param player the local player
     * @param hand   the hand
     * @param item   the weapon item held there
     * @param stack  the exact stack held there
     * @return the bound controller, or {@code null} when the player changed in a way that reset the registry
     */
    private WeaponViewController bind(LocalPlayer player, InteractionHand hand, WeaponItem item, ItemStack stack) {
        Level level = player.level();
        UUID playerId = player.getUUID();
        boolean playerChanged = lastPlayerId != null && !lastPlayerId.equals(playerId);
        if (playerChanged || level != lastLevel) {
            reset();
        }
        lastPlayerId = playerId;
        lastLevel = level;

        int slot = hand == InteractionHand.MAIN_HAND
                ? player.getInventory().getSelectedSlot()
                : Inventory.SLOT_OFFHAND;
        Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
        WeaponViewController.Owner owner = new WeaponViewController.Owner(slot, String.valueOf(itemId));

        WeaponViewController controller = controllers.computeIfAbsent(
                new WeaponViewKey(playerId, hand), key -> new WeaponViewController());
        int ammoCount = item.animationAmmoCount(stack);
        if (!controller.owns(owner)) {
            controller.activate(owner, item.pack(), item.definition(), ammoCount);
        }
        return controller;
    }

    /**
     * Drops the controller of a hand that no longer holds a weapon.
     *
     * @param player the local player
     * @param hand   the hand to check
     */
    private void releaseIfPresent(LocalPlayer player, InteractionHand hand) {
        WeaponViewController removed = controllers.remove(new WeaponViewKey(player.getUUID(), hand));
        if (removed != null) {
            removed.reset();
        }
    }

    /**
     * @return the seconds elapsed since the previous tick, clamped to a quarter second so a stall cannot skip a whole
     *         clip in one step
     */
    private double elapsedSinceLastTick() {
        long now = System.nanoTime();
        long previous = lastTickNanos;
        lastTickNanos = now;
        if (previous == 0L) {
            return WeaponViewController.SECONDS_PER_TICK;
        }
        double delta = (now - previous) / 1_000_000_000.0;
        if (!(delta > 0.0)) {
            return WeaponViewController.SECONDS_PER_TICK;
        }
        return Math.min(delta, 0.25);
    }

    /**
     * Starts the firing animation of an accepted shot for one hand.
     *
     * <p>Called from the networking layer when the server confirms a shot, never from the input path: a
     * rejected request therefore produces no animation. The controller resolves the clip from the weapon's own
     * {@code fire_anims} through the existing display and animation caches, so nothing about the M100 DMR — or
     * any other weapon — is hard-coded here.</p>
     *
     * <p>If the hand has no controller yet (the weapon was never rendered), the shot animation is skipped: the
     * first rendered frame will bind the controller and start the idle clip, which is a better outcome than
     * animating a weapon that is not on screen.</p>
     *
     * @param hand the hand the shot came from
     * @return {@code true} when a firing animation started
     */
    public synchronized boolean playFireAnimation(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || hand == null) {
            return false;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem item)) {
            return false;
        }
        WeaponViewController controller = controllers.get(new WeaponViewKey(player.getUUID(), hand));
        if (controller == null) {
            return false;
        }
        // The ammunition count after the shot selects the clip, matching the declaration's own vocabulary. For an
        // unlimited weapon that count is never zero, so the "last round" clip is never selected.
        return controller.playFire(item.pack(), item.definition(), item.animationAmmoCount(stack));
    }

    /**
     * Starts the reload animation the server accepted for one hand.
     *
     * <p>The clip name and duration are the server's choice (it selected them from the weapon's
     * {@code reload_sequences}); this method only hands them to the controller of the held weapon. When the
     * hand has no controller yet — the weapon was never rendered — nothing is animated, and the first rendered
     * frame binds the controller as usual.</p>
     *
     * @param hand            the hand the reload belongs to
     * @param animation       the declared reload animation, may be {@code null}
     * @param durationSeconds the server-side duration in seconds
     * @return {@code true} when a reload animation started
     */
    public synchronized boolean playReloadAnimation(InteractionHand hand, String animation, double durationSeconds) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || hand == null || animation == null || animation.isBlank()) {
            return false;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem item)) {
            return false;
        }
        WeaponViewController controller = controllers.get(new WeaponViewKey(player.getUUID(), hand));
        if (controller == null) {
            return false;
        }
        return controller.playReload(item.pack(), item.definition(), animation, durationSeconds);
    }

    /**
     * Starts the inspection animation the server accepted for one hand.
     *
     * <p>Like {@link #playReloadAnimation}, the clip name and duration are the server's choice; this method only hands them
     * to the controller of the held weapon. When the hand has no controller yet nothing is animated, which cannot happen
     * in practice because the first-person pass binds it every frame the weapon is visible.</p>
     *
     * @param hand            the hand the inspection belongs to
     * @param animation       the declared inspection animation, may be {@code null}
     * @param durationSeconds the server-side duration in seconds
     * @return {@code true} when an inspection animation started
     */
    public synchronized boolean playInspectAnimation(InteractionHand hand, String animation,
                                                     double durationSeconds) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || hand == null || animation == null || animation.isBlank()) {
            return false;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem item)) {
            return false;
        }
        WeaponViewController controller = controllers.get(new WeaponViewKey(player.getUUID(), hand));
        if (controller == null) {
            return false;
        }
        return controller.playInspect(item.pack(), item.definition(), animation, durationSeconds);
    }

    /**
     * Ends the inspection animation of one hand and returns playback to idle.
     *
     * @param hand the hand whose inspection ended
     */
    public synchronized void finishInspectAnimation(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || hand == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem item)) {
            return;
        }
        WeaponViewController controller = controllers.get(new WeaponViewKey(player.getUUID(), hand));
        if (controller != null) {
            controller.finishInspect(item.pack(), item.definition());
        }
    }

    /**
     * Ends the reload animation of one hand and returns playback to idle.
     *
     * <p>Called when the server reports completion or cancellation, and when the client stops waiting for an
     * outcome. Safe when no reload is animating.</p>
     *
     * @param hand the hand whose reload ended
     */
    public synchronized void finishReloadAnimation(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || hand == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem item)) {
            return;
        }
        WeaponViewController controller = controllers.get(new WeaponViewKey(player.getUUID(), hand));
        if (controller != null) {
            controller.finishReload(item.pack(), item.definition());
        }
    }

    /**
     * Drops the controller of one hand, used when that hand no longer holds a weapon.
     *
     * @param hand the hand whose controller must be released
     */
    public synchronized void release(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            reset();
            return;
        }
        WeaponViewController removed = controllers.remove(new WeaponViewKey(player.getUUID(), hand));
        if (removed != null) {
            removed.reset();
        }
    }

    /** Drops every controller. Used by resource reloads and when the client world or player changes. */
    public synchronized void reset() {
        for (WeaponViewController controller : controllers.values()) {
            controller.reset();
        }
        controllers.clear();
        lastPlayerId = null;
        lastLevel = null;
        lastTickNanos = 0L;
    }
}
