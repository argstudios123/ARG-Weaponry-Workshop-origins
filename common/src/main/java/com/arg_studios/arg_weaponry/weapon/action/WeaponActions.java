package com.arg_studios.arg_weaponry.weapon.action;

import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.AmmoCapacity;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionRegistry;
import com.arg_studios.arg_weaponry.weapon.ammo.CreativeAmmunition;
import com.arg_studios.arg_weaponry.weapon.ammo.ReserveAmmunition;
import com.arg_studios.arg_weaponry.weapon.state.WeaponState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The server-side entry point for validating weapon actions against the authoritative held stack.
 *
 * <p>Each method answers one question — "may this player perform this action with this hand right now?" —
 * and answers it from the server's own data: the {@link ServerPlayer}, its inventory and the weapon
 * definition the held item was registered from. It never looks at a client-supplied value, so a request
 * cannot influence damage, cadence, capacity or ammunition by claiming a different number: the only
 * parameters are the player and the hand, and everything else is derived from what the server already
 * holds.</p>
 *
 * <p>The class is thin on purpose. It performs the two things that need a live game — the player-context
 * check and the held-stack lookup — and delegates the decision itself to {@link WeaponActionRules}, which
 * is pure and testable without a bootstrapped game. This split is what lets the rules be verified headlessly
 * while the lookup stays one short, obvious method per action.</p>
 *
 * <h2>Validation only</h2>
 * <p>Nothing in this class mutates: no component is written, no {@code ItemStack} is replaced, no
 * ammunition is consumed, no damage is dealt, no animation or cooldown is started, and no packet is sent.
 * An {@link WeaponActionResult#OK} means "a future authoritative operation may proceed", never "something
 * happened".</p>
 *
 * <h2>Boundary for later operations</h2>
 * <p>Firing, reloading and fire-mode selection are not implemented and this class deliberately does not
 * pretend otherwise: there is no {@code WeaponItem#use} override and no gameplay hook that calls these
 * methods yet. When those operations arrive they are expected to call the matching validation first and
 * only then perform their side effect, so the conditions live here (through the rules) once instead of
 * being repeated inside each input or networking handler.</p>
 *
 * <h2>What is reachable today</h2>
 * <p>{@link WeaponActionResult#INVALID_WEAPON_STATE} is defensive on this path: a {@link WeaponItem} always
 * declares its definition-derived default, so {@code WeaponItem#state(ItemStack)} never returns
 * {@code null} for a weapon stack, and a non-weapon stack is rejected as
 * {@link WeaponActionResult#NOT_A_WEAPON} before any state is read. The rules still treat a null state as
 * an error, so a future item whose default cannot be resolved degrades to a refusal instead of a crash;
 * that branch is exercised directly by the headless rule tests.</p>
 *
 * <h2>Server only</h2>
 * <p>The parameter type is {@link ServerPlayer}, which cannot exist on a client, so a call on the logical
 * client is a compile-time impossibility rather than a runtime check. Client input handling and any
 * networking around it are a later milestone and stay out of this class.</p>
 */
public final class WeaponActions {

    private WeaponActions() {
    }

    /**
     * Validates a firing request.
     *
     * <p>Checks, in order: the player is alive and not a spectator; the requested hand holds a registered
     * {@link WeaponItem}; the stack has a readable {@link WeaponState} (a weapon stack always resolves its
     * definition default, so this only fails for a state this build cannot read); the definition declares a
     * magazine; and that magazine holds at least one round.</p>
     *
     * @param player the server-side player making the request
     * @param hand   the hand the request refers to
     * @return {@link WeaponActionResult#OK} when the request may be executed by a later milestone,
     *         otherwise the first condition that forbids it
     */
    public static WeaponActionResult validateFire(ServerPlayer player, InteractionHand hand) {
        if (!isValidContext(player, hand)) {
            return WeaponActionResult.INVALID_CONTEXT;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return WeaponActionResult.NOT_A_WEAPON;
        }
        WeaponState state = weapon.state(stack);
        return WeaponActionRules.validateFire(weapon.ammoCapacity(), state, weapon.definition().firingModes(),
                weapon.definition().allowFiringWhenSprinting(), player.isSprinting());
    }

    /**
     * Validates a reload request.
     *
     * <p>Checks, in order: the player is alive and not a spectator; the requested hand holds a registered
     * {@link WeaponItem}; the stack has a readable {@link WeaponState}; the definition declares a magazine; that
     * magazine has room for at least one more round; and the player carries at least one compatible reserve
     * round of the kinds the weapon's {@code ammo_to_use} resolves to.</p>
     *
     * <p>{@link WeaponActionResult#OK} means a reload <em>may start</em>. It does not mean anything was
     * transferred: the transfer happens when the session completes, and only after that completion has been
     * revalidated against the then-current inventory. Whether a reload is already running, and whether the
     * request's sequence number is new, is decided by the reload service, which owns the session registry.</p>
     *
     * @param player the server-side player making the request
     * @param hand   the hand the request refers to
     * @return {@link WeaponActionResult#OK} when a reload may start, otherwise the first condition that forbids
     *         it
     */
    public static WeaponActionResult validateReload(ServerPlayer player, InteractionHand hand) {
        if (!isValidContext(player, hand)) {
            return WeaponActionResult.INVALID_CONTEXT;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return WeaponActionResult.NOT_A_WEAPON;
        }
        WeaponState state = weapon.state(stack);
        AmmoCapacity capacity = weapon.ammoCapacity();
        if (capacity != null && capacity.isUnlimited()) {
            // Nothing to reload and nothing to count: answering here keeps the inventory scan out of the path.
            return WeaponActionRules.validateReload(capacity, state, 0);
        }
        int reserve = ReserveAmmunition.available(player, weapon.definition());
        // The same Creative supply the reload service uses, so the pre-check and the real transfer cannot disagree.
        boolean creativeSupply = CreativeAmmunition.grantsUnlimited(player.hasInfiniteMaterials(),
                AmmunitionRegistry.declaresCreativeFallback(weapon.definition()));
        int effectiveReserve = CreativeAmmunition.reserveFor(reserve, capacity == null ? 0 : capacity.rounds(),
                creativeSupply);
        return WeaponActionRules.validateReload(capacity, state, effectiveReserve);
    }

    /**
     * Decides whether the player may perform weapon actions at all.
     *
     * <p>A missing player or hand is a caller error and is answered with
     * {@link WeaponActionResult#INVALID_CONTEXT} rather than an exception, because the value is already the
     * "nothing about the stack can be judged" answer. A dead player and a spectator are rejected because
     * neither may act on the world; every other player state (sneaking, swimming, riding, in a container)
     * is allowed, since no rule this build can check depends on it. Because a {@link ServerPlayer} only
     * exists on the logical server, no side check is needed here.</p>
     *
     * @param player the player to judge, may be {@code null}
     * @param hand   the hand to judge, may be {@code null}
     * @return {@code true} when the context is usable for validation
     */
    private static boolean isValidContext(ServerPlayer player, InteractionHand hand) {
        return player != null && hand != null && player.isAlive() && !player.isSpectator();
    }
}
