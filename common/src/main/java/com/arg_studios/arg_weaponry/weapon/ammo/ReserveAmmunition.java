package com.arg_studios.arg_weaponry.weapon.ammo;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts the reserve ammunition a player carries for a weapon.
 *
 * <p>Reserve ammunition is not a value this framework stores. It is a <em>projection</em> of the player's
 * real inventory, computed on demand: every slot whose item is one of the weapon's compatible ammunition
 * items is worth its stack count. Nothing is cached, nothing is written and nothing is reserved, so the
 * count can never drift away from the items that actually exist. Consuming those items is a reload
 * operation and belongs to a later milestone.</p>
 *
 * <h2>Compatibility comes from {@code ammo_to_use}</h2>
 * <p>The weapon definition is the only source of compatibility, through its existing
 * {@code ammo_to_use} array. {@link AmmunitionRegistry#compatibleItemIds(WeaponDefinition)} resolves each
 * entry to a real Minecraft item id — an entry that matches an ammunition definition's {@code ammo_name}
 * contributes that definition's {@code ammo_id}, and a bare name with no definition contributes nothing — so
 * a weapon can accept several kinds of ammunition and a weapon configured for different ammunition never
 * counts another weapon's rounds. Names this build cannot resolve match nothing; they are never replaced by a
 * guessed item and they grant no special behaviour.</p>
 *
 * <h2>Pure core and server adapters</h2>
 * <p>{@link Slot}, {@link #matches(List, Slot)}, {@link #roundsIn(int, int)},
 * {@link #addSaturating(int, int)} and {@link #count(List, List)} import no registry, player or level, so
 * the matching rules can be verified without a running game. {@link #slotsOf(Container)} and
 * {@link #available(ServerPlayer, WeaponDefinition)} are the thin server-side adapters: they read the real
 * inventory, project it into {@link Slot}s and delegate every decision back to the pure methods, so
 * "how many rounds is this worth" is decided in exactly one place.</p>
 *
 * <h2>What is counted</h2>
 * <ul>
 *   <li>only stacks whose item id is one of the definition's compatible ammunition ids;</li>
 *   <li>an item's real stack count, bounded by that stack's own maximum stack size, so a stack written
 *       larger than the item allows cannot inflate the count;</li>
 *   <li>nothing at all for empty stacks, absent definitions, missing {@code ammo_to_use} entries or ids
 *       that name no registered item.</li>
 * </ul>
 *
 * <h2>Server only</h2>
 * <p>{@link #available(ServerPlayer, WeaponDefinition)} takes a {@link ServerPlayer}, so a client cannot
 * call it, and no value in this class is ever supplied by a client. Client-side display of reserve
 * ammunition, if wanted, is a later milestone's concern.</p>
 */
public final class ReserveAmmunition {

    /**
     * One inventory slot as the matching rules see it.
     *
     * <p>Deliberately an identifier plus two numbers rather than an {@link ItemStack}: the rules stay
     * free of item-object identity (two stacks of the same ammunition item are the same ammunition,
     * whatever object they are) and can be exercised headlessly.</p>
     *
     * @param itemId       the slot's item as canonical {@code namespace:path}, or {@code null} when the
     *                     slot holds no identifiable item
     * @param count        the slot's stack count as stored
     * @param maxStackSize the maximum stack size of the slot's item
     */
    public record Slot(String itemId, int count, int maxStackSize) {

        /** @return {@code true} when the slot holds nothing worth counting. */
        public boolean isEmpty() {
            return count <= 0 || itemId == null;
        }
    }

    /**
     * Ammunition ids already reported as unregistered.
     *
     * <p>Diagnostic de-duplication only: it holds no gameplay data, is never read to decide how much
     * ammunition exists, and clearing it changes nothing except how often a warning is logged.</p>
     */
    private static final Set<String> reportedUnregisteredIds = ConcurrentHashMap.newKeySet();

    private ReserveAmmunition() {
    }

    // --- pure rules ---

    /**
     * @param ammoItemIds the weapon's compatible ammunition item ids, may be {@code null}
     * @param slot        the slot to judge, may be {@code null}
     * @return whether the slot holds one of those exact items
     */
    public static boolean matches(List<ResourceId> ammoItemIds, Slot slot) {
        if (ammoItemIds == null || ammoItemIds.isEmpty() || slot == null || slot.itemId() == null) {
            return false;
        }
        for (ResourceId ammoItemId : ammoItemIds) {
            if (ammoItemId != null && ammoItemId.asString().equals(slot.itemId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The number of rounds one slot is worth.
     *
     * <p>The stored count is used, bounded by the item's own stack limit: a count of 0 or less is worth
     * nothing, and a slot written above its limit cannot count for more than the limit. A non-positive
     * limit is treated as "not countable" rather than as "unlimited".</p>
     *
     * @param count        the stored stack count
     * @param maxStackSize the item's maximum stack size
     * @return the rounds this slot contributes, never negative
     */
    public static int roundsIn(int count, int maxStackSize) {
        if (count <= 0 || maxStackSize <= 0) {
            return 0;
        }
        return Math.min(count, maxStackSize);
    }

    /**
     * Adds to a running total without overflowing.
     *
     * <p>A player inventory has a bounded number of slots, so saturation is unreachable in practice; it
     * exists so that a malformed count can never wrap into a negative reserve total.</p>
     *
     * @param total  the total so far, expected to be non-negative
     * @param amount the rounds to add; negative values are ignored
     * @return the sum, saturated at {@link Integer#MAX_VALUE}
     */
    public static int addSaturating(int total, int amount) {
        if (amount <= 0) {
            return total;
        }
        if (total > Integer.MAX_VALUE - amount) {
            return Integer.MAX_VALUE;
        }
        return total + amount;
    }

    /**
     * Counts the compatible rounds among the given slots.
     *
     * @param ammoItemIds the weapon's compatible ammunition item ids, may be {@code null}
     * @param slots       the slots to inspect, may be {@code null}
     * @return the total compatible rounds; {@code 0} when nothing is compatible or configured
     */
    public static int count(List<ResourceId> ammoItemIds, List<Slot> slots) {
        if (ammoItemIds == null || ammoItemIds.isEmpty() || slots == null || slots.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Slot slot : slots) {
            if (!matches(ammoItemIds, slot)) {
                continue;
            }
            total = addSaturating(total, roundsIn(slot.count(), slot.maxStackSize()));
        }
        return total;
    }

    /**
     * @param ammoItemIds the weapon's compatible ammunition item ids, may be {@code null}
     * @param slots       the slots to inspect, may be {@code null}
     * @return whether at least one compatible round is available
     */
    public static boolean hasAny(List<ResourceId> ammoItemIds, List<Slot> slots) {
        if (ammoItemIds == null || ammoItemIds.isEmpty() || slots == null || slots.isEmpty()) {
            return false;
        }
        for (Slot slot : slots) {
            if (matches(ammoItemIds, slot) && roundsIn(slot.count(), slot.maxStackSize()) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The positions of the compatible slots.
     *
     * <p>Positions, not stacks: the caller gets a stable, read-only answer that a later transaction can
     * act on, and this milestone consumes nothing. {@link #slotsOf(Container)} builds the list with one
     * entry per container slot, in container order, so a returned position is the same container slot
     * number.</p>
     *
     * @param ammoItemIds the weapon's compatible ammunition item ids, may be {@code null}
     * @param slots       the slots to inspect, may be {@code null}
     * @return the zero-based positions of compatible slots, in ascending order
     */
    public static List<Integer> compatibleSlots(List<ResourceId> ammoItemIds, List<Slot> slots) {
        if (ammoItemIds == null || ammoItemIds.isEmpty() || slots == null || slots.isEmpty()) {
            return List.of();
        }
        List<Integer> positions = new ArrayList<>();
        for (int position = 0; position < slots.size(); position++) {
            Slot slot = slots.get(position);
            if (matches(ammoItemIds, slot) && roundsIn(slot.count(), slot.maxStackSize()) > 0) {
                positions.add(position);
            }
        }
        return List.copyOf(positions);
    }

    // --- server-side adapters ---

    /**
     * @param definition the weapon definition, may be {@code null}
     * @return whether the definition declares at least one {@code ammo_to_use} entry that resolves to an item
     */
    public static boolean isConfigured(WeaponDefinition definition) {
        return !AmmunitionRegistry.compatibleItemIds(definition).isEmpty();
    }

    /**
     * Projects a container into one {@link Slot} per container slot, in container order.
     *
     * <p>Must be called on the logical server. Empty slots are kept — with no item id and a count of
     * {@code 0} — so a position in the returned list is the container slot number, which is what
     * {@link #compatibleSlots(List, List)} reports. The item id is the canonical identifier Minecraft
     * reports for the stack's item, the same form a content pack writes in {@code ammo_to_use}.</p>
     *
     * @param inventory the container to project, may be {@code null}
     * @return one slot per container slot; empty when the container is {@code null}
     */
    public static List<Slot> slotsOf(Container inventory) {
        if (inventory == null) {
            return List.of();
        }
        int size = inventory.getContainerSize();
        List<Slot> slots = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                slots.add(new Slot(null, 0, 0));
                continue;
            }
            Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            slots.add(new Slot(itemId == null ? null : itemId.toString(), stack.getCount(),
                    stack.getMaxStackSize()));
        }
        return List.copyOf(slots);
    }

    /**
     * Counts the reserve rounds a player carries for one weapon.
     *
     * <p>This is the reusable entry point a reload will validate against: it answers "how many rounds of
     * the kinds this weapon accepts are available" from server state only, and consumes nothing.</p>
     *
     * @param player     the server-side player, may be {@code null}
     * @param definition the weapon definition, may be {@code null}
     * @return the total compatible rounds; {@code 0} when the weapon declares no compatible item id
     */
    public static int available(ServerPlayer player, WeaponDefinition definition) {
        if (player == null || definition == null) {
            return 0;
        }
        List<ResourceId> ammoItemIds = AmmunitionRegistry.compatibleItemIds(definition);
        if (ammoItemIds.isEmpty()) {
            return 0;
        }
        reportUnregistered(ammoItemIds);
        return count(ammoItemIds, slotsOf(player.getInventory()));
    }

    /**
     * @param player     the server-side player, may be {@code null}
     * @param definition the weapon definition, may be {@code null}
     * @return whether at least one compatible reserve round is available
     */
    public static boolean hasAny(ServerPlayer player, WeaponDefinition definition) {
        return available(player, definition) > 0;
    }

    /**
     * The inventory slot numbers that hold compatible reserve ammunition for one weapon.
     *
     * <p>Read-only: nothing is removed, so a reload can decide what to consume and then do it as one
     * transaction in a later milestone.</p>
     *
     * @param player     the server-side player, may be {@code null}
     * @param definition the weapon definition, may be {@code null}
     * @return the compatible slot numbers, in ascending order; empty when nothing is available
     */
    public static List<Integer> compatibleSlots(ServerPlayer player, WeaponDefinition definition) {
        if (player == null || definition == null) {
            return List.of();
        }
        return compatibleSlots(AmmunitionRegistry.compatibleItemIds(definition), slotsOf(player.getInventory()));
    }

    private static void reportUnregistered(List<ResourceId> ammoItemIds) {
        for (ResourceId ammoItemId : ammoItemIds) {
            if (isRegisteredItem(ammoItemId) || !reportedUnregisteredIds.add(ammoItemId.asString())) {
                continue;
            }
            Constants.LOG.warn("[ARG] The ammunition entry '{}' names no registered Minecraft item. An "
                    + "'ammo_to_use' entry only counts as reserve ammunition when it is written as an item id "
                    + "that exists at play time; no rounds of this kind can be counted until then", ammoItemId);
        }
    }

    /**
     * Asks the item registry whether an ammunition id names an item.
     *
     * <p>Only diagnostics need this: matching itself compares the authored id with the id of each stack, so
     * an id that names no item simply never matches. The registry is read at play time and never during
     * content-pack loading, where items from other mods may not be registered yet.</p>
     *
     * <p>{@code BuiltInRegistries.ITEM} is a defaulted registry whose {@code getValue} answers
     * {@code minecraft:air} for unknown ids, hence the {@code containsKey} check: an unregistered id must
     * stay recognisable as unregistered instead of quietly becoming air.</p>
     *
     * @param ammoItemId the configured ammunition item id, may be {@code null}
     * @return whether an item is registered under exactly that id
     */
    private static boolean isRegisteredItem(ResourceId ammoItemId) {
        Identifier identifier = ammoItemId == null ? null : Identifier.tryParse(ammoItemId.asString());
        return identifier != null && BuiltInRegistries.ITEM.containsKey(identifier);
    }
}
