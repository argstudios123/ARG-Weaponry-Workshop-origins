package com.arg_studios.arg_weaponry.weapon.equip;

import net.minecraft.world.item.ItemStack;

/**
 * The first-draw flag of one stack, and the rule that combines it with the client-side guard.
 *
 * <p>The flag is per <em>stack</em>, not per weapon: two M100 DMRs drawn for the first time each play {@code
 * draw_first} once, and a stack that has been drawn before never plays it again - not even after a relog, because the flag
 * is persistent. The authoritative value lives in {@link StackEquipState}; this class is the only place that reads or
 * writes it.</p>
 *
 * <p>Writing happens on the server, when a first draw starts. Reading happens on the client, at the moment the clip is
 * selected, which is why the decision also consults {@link FirstDrawGuard}: the server's write reaches the client one
 * round trip later, and a fast holster/draw cycle must not replay the clip in between.</p>
 */
public final class FirstDrawFlag {

    private FirstDrawFlag() {
    }

    /**
     * The pure decision.
     *
     * @param storedDrawn   the persistent flag as this side currently sees it
     * @param drawnLocally  whether this client already played a first draw for this slot and item in this session
     * @return whether the first-draw clip should be used
     */
    public static boolean needsFirstDraw(boolean storedDrawn, boolean drawnLocally) {
        return !storedDrawn && !drawnLocally;
    }

    /**
     * @param stack the held stack, may be {@code null}
     * @return whether the stack has already been drawn; an absent component means "never drawn"
     */
    public static boolean isDrawn(ItemStack stack) {
        StackEquipState state = EquipComponents.get(stack);
        return state != null && state.drawn();
    }

    /**
     * Marks a stack as drawn. Called by the server when a first draw starts.
     *
     * @param stack the stack to mark, may be {@code null}
     * @return {@code true} when the component was written, i.e. when the stack had not been marked before
     */
    public static boolean markDrawn(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (isDrawn(stack)) {
            return false;
        }
        stack.set(EquipComponents.EQUIP_STATE, StackEquipState.DRAWN);
        return true;
    }

    /**
     * Marks a stack as undrawn again; used by diagnostics and tests only, never by gameplay code.
     *
     * @param stack the stack to clear, may be {@code null}
     */
    public static void clear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.set(EquipComponents.EQUIP_STATE, StackEquipState.NOT_DRAWN);
    }

    /**
     * The client's decision for the held stack.
     *
     * @param stack the held stack, may be {@code null}
     * @param slot  the main-hand slot it occupies
     * @param itemId its registered item id
     * @return whether the first-draw clip applies
     */
    public static boolean shouldUseFirstDraw(ItemStack stack, int slot, String itemId) {
        return needsFirstDraw(isDrawn(stack), FirstDrawGuard.hasPlayed(slot, itemId));
    }
}
