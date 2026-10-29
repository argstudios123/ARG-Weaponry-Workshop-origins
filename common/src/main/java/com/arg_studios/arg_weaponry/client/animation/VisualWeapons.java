package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.handoff.WeaponIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The registry of visual weapons: what each hand is actually drawing.
 *
 * <p>This is the concept the previous subsystem did not have. The selected slot and the drawn weapon are two different
 * questions: {@code Inventory#getSelectedSlot} answers the first, this registry answers the second. It holds at most one
 * {@link VisualWeapon} per hand - the invariant the handoff controller relies on - and every change goes through
 * {@link #adopt} or {@link #release}.</p>
 *
 * <h2>Lazy adoption</h2>
 * <p>When a hand is first rendered there is no visual weapon yet, so {@link #active} adopts the live stack silently. That is
 * the "no transition, just start drawing" case. It deliberately does <em>not</em> re-adopt when the live stack changes:
 * during a holster the visual weapon is the outgoing weapon while the hand already holds the incoming one, and that
 * difference is the whole point. Replacing it is the handoff controller's decision, taken at the boundary.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Every weapon's animator is disposed when it stops being the visual weapon, and the registry is dropped when the player
 * or the level changes, on a resource reload and when the feature is switched off. Nothing here is persisted.</p>
 */
public final class VisualWeapons {

    private static final VisualWeapons INSTANCE = new VisualWeapons();

    private final Map<WeaponViewKey, VisualWeapon> weapons = new HashMap<>();

    private UUID lastPlayerId;
    private Level lastLevel;

    private VisualWeapons() {
    }

    /** @return the shared registry. */
    public static VisualWeapons instance() {
        return INSTANCE;
    }

    /**
     * The weapon one hand is drawing, adopting the live stack if this hand has none yet.
     *
     * @param hand the hand
     * @return the visual weapon, or {@code null} when that hand holds no weapon
     */
    public synchronized VisualWeapon active(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || hand == null) {
            reset();
            return null;
        }
        forgetOtherPlayers(player);
        WeaponViewKey key = new WeaponViewKey(player.getUUID(), hand);
        VisualWeapon existing = weapons.get(key);
        if (existing != null) {
            return existing;
        }
        VisualWeapon adopted = create(player, hand, liveStack(player, hand));
        if (adopted != null) {
            weapons.put(key, adopted);
        }
        return adopted;
    }

    /**
     * Makes one weapon the visual weapon of a hand, disposing whatever was drawn there before. This is the handoff
     * boundary.
     *
     * @param hand     the hand
     * @param identity the weapon that becomes visual; {@link WeaponIdentity#NONE} releases the hand
     */
    public synchronized void adopt(InteractionHand hand, WeaponIdentity identity) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || hand == null) {
            reset();
            return;
        }
        forgetOtherPlayers(player);
        WeaponViewKey key = new WeaponViewKey(player.getUUID(), hand);
        VisualWeapon previous = weapons.remove(key);
        if (previous != null) {
            previous.dispose();
        }
        if (identity == null || !identity.isPresent()) {
            return;
        }
        VisualWeapon adopted = create(player, hand, stackForIdentity(player, hand, identity));
        if (adopted == null) {
            Constants.LOG.debug("[ARG] Handoff adopted '{}' in slot {} but that stack is not a weapon any more",
                    identity.itemId(), identity.slot());
            return;
        }
        weapons.put(key, adopted);
    }

    /**
     * Drops the visual weapon of one hand, so rendering falls back to the live stack.
     *
     * @param hand the hand
     */
    public synchronized void release(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        VisualWeapon removed = weapons.remove(new WeaponViewKey(player.getUUID(), hand));
        if (removed != null) {
            removed.dispose();
        }
    }

    /**
     * @param hand the hand
     * @return the identity of the weapon that hand draws, or {@link WeaponIdentity#NONE}
     */
    public synchronized WeaponIdentity identity(InteractionHand hand) {
        VisualWeapon weapon = active(hand);
        return weapon == null ? WeaponIdentity.NONE : weapon.identity();
    }

    /**
     * @param hand the hand
     * @return the stack the renderer must draw: the visual weapon's snapshot, or the live stack when that hand draws no
     *         visual weapon
     */
    public synchronized ItemStack stackFor(InteractionHand hand) {
        VisualWeapon weapon = active(hand);
        if (weapon != null) {
            return weapon.stack();
        }
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : liveStack(player, hand);
    }

    /**
     * @param hand        the hand
     * @param partialTick the frame's partial tick
     * @return the pose of the visual weapon, or {@code null} when it animates nothing
     */
    public synchronized WeaponPose poseFor(InteractionHand hand, float partialTick) {
        VisualWeapon weapon = active(hand);
        if (weapon == null) {
            return null;
        }
        return weapon.poseFor(ammoFor(hand, weapon), partialTick);
    }

    /**
     * Advances every visual weapon's own timeline by one client tick.
     *
     * @param deltaSeconds the time the tick covered
     */
    public synchronized void tick(double deltaSeconds) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            reset();
            return;
        }
        forgetOtherPlayers(player);
        for (Map.Entry<WeaponViewKey, VisualWeapon> entry : weapons.entrySet()) {
            VisualWeapon weapon = entry.getValue();
            weapon.tick(ammoFor(entry.getKey().hand(), weapon), deltaSeconds);
        }
    }

    /**
     * @param hand the hand
     * @return whether that hand currently draws a visual weapon of its own
     */
    public synchronized boolean holdsVisualWeapon(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        return weapons.containsKey(new WeaponViewKey(player.getUUID(), hand));
    }

    /** Drops every visual weapon. Used on death, respawn, disconnect, world changes and resource reloads. */
    public synchronized void reset() {
        for (VisualWeapon weapon : weapons.values()) {
            weapon.dispose();
        }
        weapons.clear();
        lastPlayerId = null;
        lastLevel = null;
    }

    /**
     * @param hand the hand
     * @return the identity of the weapon that hand currently holds according to the inventory, or
     *         {@link WeaponIdentity#NONE}
     */
    public static WeaponIdentity liveIdentity(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || hand == null) {
            return WeaponIdentity.NONE;
        }
        return identityOf(liveStack(player, hand), hand, player);
    }

    // ------------------------------------------------------------------ internals

    private VisualWeapon create(LocalPlayer player, InteractionHand hand, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof WeaponItem item)) {
            return null;
        }
        WeaponIdentity identity = identityOf(stack, hand, player);
        if (!identity.isPresent()) {
            return null;
        }
        return new VisualWeapon(stack, item.pack(), item.definition(), identity, item.animationAmmoCount(stack));
    }

    /**
     * The ammunition count an idle selection must use: the live one while the visual weapon is still the held weapon,
     * otherwise the count it had when it was adopted - an outgoing weapon must not change its idle clip while it leaves.
     */
    private int ammoFor(InteractionHand hand, VisualWeapon weapon) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return weapon.ammoAtAdoption();
        }
        ItemStack live = liveStack(player, hand);
        if (live.getItem() instanceof WeaponItem item && weapon.identity().sameAs(identityOf(live, hand, player))) {
            return item.animationAmmoCount(live);
        }
        return weapon.ammoAtAdoption();
    }

    private static ItemStack liveStack(LocalPlayer player, InteractionHand hand) {
        return player.getItemInHand(hand);
    }

    private static ItemStack stackForIdentity(LocalPlayer player, InteractionHand hand, WeaponIdentity identity) {
        ItemStack live = liveStack(player, hand);
        if (identity.sameAs(identityOf(live, hand, player))) {
            return live;
        }
        Inventory inventory = player.getInventory();
        int slot = identity.slot();
        if (hand == InteractionHand.MAIN_HAND && slot >= 0 && slot < inventory.getContainerSize()) {
            return inventory.getItem(slot);
        }
        return ItemStack.EMPTY;
    }

    private static WeaponIdentity identityOf(ItemStack stack, InteractionHand hand, LocalPlayer player) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof WeaponItem item)) {
            return WeaponIdentity.NONE;
        }
        int slot = hand == InteractionHand.MAIN_HAND
                ? player.getInventory().getSelectedSlot()
                : Inventory.SLOT_OFFHAND;
        return new WeaponIdentity(String.valueOf(BuiltInRegistries.ITEM.getKey(item)), slot);
    }

    private void forgetOtherPlayers(LocalPlayer player) {
        if (player.getUUID().equals(lastPlayerId) && player.level() == lastLevel) {
            return;
        }
        if (lastPlayerId != null || lastLevel != null) {
            reset();
        }
        lastPlayerId = player.getUUID();
        lastLevel = player.level();
    }
}
