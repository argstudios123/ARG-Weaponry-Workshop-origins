package com.arg_studios.arg_weaponry.weapon.reload;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.ammo.ReserveAmmunition;

import java.util.ArrayList;
import java.util.List;

/**
 * The explicit, validated plan of which inventory stacks a reload will consume.
 *
 * <p>Planning is separate from committing on purpose: the plan can be built, inspected and verified before a
 * single item moves, and the caller commits only when the plan covers the whole transfer. A partial plan is
 * never applied — a reload either moves the amount it computed or nothing at all.</p>
 *
 * <h2>Deterministic consumption order</h2>
 * <ol>
 *   <li>ammunition types in the authored order of {@code ammo_to_use}, so the creator's priority is preserved
 *       ({@code ["dmr_ammo", "creative_ammo"]} spends the dedicated rounds before the universal ones);</li>
 *   <li>within one type, inventory slots in ascending slot order;</li>
 *   <li>a stack is never visited twice and a stack of an incompatible item is never touched.</li>
 * </ol>
 * <p>The order is a property of the plan, not of the inventory iteration that produced the candidates, so
 * headless tests can assert it exactly.</p>
 *
 * @param deductions the stacks to shrink, in consumption order
 * @param total      the number of rounds the plan covers
 */
public record ReloadAmmunitionPlan(List<Deduction> deductions, int total) {

    public ReloadAmmunitionPlan {
        deductions = List.copyOf(deductions);
        if (total < 0) {
            throw new IllegalArgumentException("A reload plan cannot cover a negative amount");
        }
    }

    /**
     * One inventory slot the plan may draw from.
     *
     * @param slot         the inventory slot number
     * @param itemId       the slot's item id, never {@code null}
     * @param count        the stack count currently in the slot
     * @param maxStackSize the item's maximum stack size
     */
    public record Candidate(int slot, ResourceId itemId, int count, int maxStackSize) {
    }

    /**
     * One stack to shrink.
     *
     * @param slot     the inventory slot number
     * @param itemId   the item id the slot must still contain
     * @param quantity how many items to remove, always positive
     */
    public record Deduction(int slot, ResourceId itemId, int quantity) {

        public Deduction {
            if (quantity <= 0) {
                throw new IllegalArgumentException("A deduction must remove at least one item");
            }
        }
    }

    /** @return an empty plan. */
    public static ReloadAmmunitionPlan empty() {
        return new ReloadAmmunitionPlan(List.of(), 0);
    }

    /**
     * @param amount the number of rounds required
     * @return whether this plan covers that amount
     */
    public boolean covers(int amount) {
        return amount > 0 && total >= amount;
    }

    /** @return {@code true} when the plan moves nothing. */
    public boolean isEmpty() {
        return deductions.isEmpty();
    }

    /**
     * Projects the read-only inventory slots of {@link ReserveAmmunition} into plan candidates.
     *
     * <p>The position in the input list is the slot number, because {@code slotsOf} builds one entry per
     * container slot in container order. Empty slots are skipped here; they cannot contribute anything, and
     * keeping their index is what makes a deduction point at the real inventory slot.</p>
     *
     * @param slots the projected inventory, may be {@code null}
     * @return the candidates, in slot order
     */
    public static List<Candidate> candidates(List<ReserveAmmunition.Slot> slots) {
        if (slots == null || slots.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>(slots.size());
        for (int slot = 0; slot < slots.size(); slot++) {
            ReserveAmmunition.Slot current = slots.get(slot);
            if (current == null || current.isEmpty()) {
                continue;
            }
            ResourceId.Result parsed = ResourceId.tryParse(current.itemId());
            if (!parsed.isValid()) {
                continue;
            }
            candidates.add(new Candidate(slot, parsed.id(), current.count(), current.maxStackSize()));
        }
        return List.copyOf(candidates);
    }

    /**
     * Builds the plan for one transfer.
     *
     * @param compatibleItemIds the weapon's compatible ammunition ids in authored order
     * @param candidates        the inventory candidates, in slot order
     * @param amount            the number of rounds the reload needs
     * @return the plan; {@link #covers(int)} reports whether it can satisfy the amount
     */
    public static ReloadAmmunitionPlan plan(List<ResourceId> compatibleItemIds, List<Candidate> candidates,
                                            int amount) {
        if (amount <= 0 || compatibleItemIds == null || compatibleItemIds.isEmpty()
                || candidates == null || candidates.isEmpty()) {
            return empty();
        }

        List<Deduction> deductions = new ArrayList<>();
        int remaining = amount;
        for (ResourceId compatible : compatibleItemIds) {
            if (remaining == 0) {
                break;
            }
            if (compatible == null) {
                continue;
            }
            for (Candidate candidate : candidates) {
                if (remaining == 0) {
                    break;
                }
                if (candidate == null || !compatible.equals(candidate.itemId())) {
                    continue;
                }
                int take = Math.min(remaining, Math.max(0, candidate.count()));
                if (take <= 0) {
                    continue;
                }
                deductions.add(new Deduction(candidate.slot(), candidate.itemId(), take));
                remaining -= take;
            }
        }
        return new ReloadAmmunitionPlan(deductions, amount - remaining);
    }
}
