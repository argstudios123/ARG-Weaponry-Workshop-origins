package com.arg_studios.arg_weaponry.weapon.reload;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.compat.WeaponCompatibility;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.network.WeaponReloadResultPayload;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.AmmoCapacity;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionRules;
import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireSessions;
import com.arg_studios.arg_weaponry.weapon.inspect.WeaponInspectService;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionRegistry;
import com.arg_studios.arg_weaponry.weapon.ammo.ReserveAmmunition;
import com.arg_studios.arg_weaponry.weapon.state.WeaponComponents;
import com.arg_studios.arg_weaponry.weapon.state.WeaponState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The server-authoritative reload operation.
 *
 * <p>A reload is a <em>session</em>: {@link #request} validates and starts it, the server tick pass
 * ({@link #serverTick}) keeps it alive or cancels it, and the transfer happens when the authored duration has
 * elapsed. Nothing is consumed and the magazine is untouched while the session runs, so an interrupted reload
 * can never leave a half-filled magazine or lost ammunition behind.</p>
 *
 * <h2>What the client may and may not say</h2>
 * <p>The request carries a hand and a sequence number. Capacity, room, reserve amounts, the animation name,
 * the sound, the duration and the consumed slots are all recomputed here from the server's own player,
 * inventory, registry and definitions. A modified client can ask for a reload; it cannot describe one.</p>
 *
 * <h2>Threading</h2>
 * <p>Every method must run on the server thread. The tick pass is registered through the loaders' server tick
 * events, so completions happen inside the normal server tick — never on a background thread.</p>
 */
public final class WeaponReloadService {

    /** The {@code sounds} key used when the magazine was empty at reload start. */
    public static final String SOUND_EMPTY = "reload_empty";

    /** The {@code sounds} key used when the magazine still held rounds at reload start. */
    public static final String SOUND_STANDARD = "reload";

    private WeaponReloadService() {
    }

    /**
     * The outcome of one reload request or of one finished session.
     *
     * @param result        what happened
     * @param animation     the declared reload animation the client should play, or {@code null}
     * @param durationTicks the server-side duration in ticks, {@code 0} for a rejection
     * @param emptyMagazine whether the magazine was empty at reload start, which selects the empty sound
     */
    public record ReloadOutcome(WeaponActionResult result, String animation, int durationTicks,
                                boolean emptyMagazine) {

        /** @return whether this outcome started a session. */
        public boolean started() {
            return result == WeaponActionResult.RELOAD_STARTED;
        }
    }

    /**
     * Handles one reloading request.
     *
     * @param player   the requesting player, resolved by the server
     * @param hand     the hand named by the request
     * @param sequence the request's sequence number, used for replay protection
     * @return the outcome, which has already been sent to the client when the connection can receive it
     */
    public static ReloadOutcome request(ServerPlayer player, InteractionHand hand, int sequence) {
        if (player == null || hand == null || !player.isAlive() || player.isSpectator()) {
            return reject(null, hand, sequence, WeaponActionResult.INVALID_CONTEXT);
        }
        // [DrawHolster hook] Reloading is refused while the weapon is being drawn or holstered.
        if (com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.isEquipBusy(player)) {
            return reject(player, hand, sequence, WeaponActionResult.EQUIPPING);
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return reject(player, hand, sequence, WeaponActionResult.NOT_A_WEAPON);
        }
        if (!WeaponCompatibility.isUsable(player.getUUID())) {
            return reject(player, hand, sequence, WeaponActionResult.CONTENT_MISMATCH);
        }

        WeaponDefinition definition = weapon.definition();
        WeaponState state = weapon.state(stack);
        AmmoCapacity capacity = weapon.ammoCapacity();
        if (capacity != null && capacity.isUnlimited()) {
            // Unlimited ammunition is never reloaded: refuse before anything is counted, started or sent, so no
            // session, animation, sound or inventory change can result from an R press against such a weapon.
            return reject(player, hand, sequence, WeaponActionRules.validateReload(capacity, state, 0));
        }
        int reserve = ReserveAmmunition.available(player, definition);
        WeaponActionResult decision = WeaponActionRules.validateReload(capacity, state, reserve);
        if (!decision.isAllowed()) {
            return reject(player, hand, sequence, decision);
        }

        ReloadSequenceSelector.Selection selection =
                ReloadSequenceSelector.select(definition.reloadSequences(), state.ammo());
        if (selection.hasUnsupportedConditions()) {
            Constants.LOG.debug("[ARG] Reload conditions that cannot be evaluated for weapon '{}': {}",
                    definition.name(), selection.unsupportedConditions());
        }
        if (selection.usedFallback()) {
            Constants.LOG.debug("[ARG] No reload condition matched for weapon '{}'; using its first declared "
                    + "sequence", definition.name());
        }

        // A reload and a firing session are mutually exclusive: the reload owns the weapon from here on, so any
        // automatic or burst session of this hand ends now, before a single tick of the reload elapses.
        WeaponFireSessions.cancel(player.getUUID(), hand);
        // An inspection is mutually exclusive with a reload for the same reason: one playback owner at a time.
        WeaponInspectService.cancel(player.getUUID(), hand, WeaponActionResult.RELOAD_IN_PROGRESS);

        int durationTicks = ReloadRules.ticksFor(ReloadSequenceSelector.durationSeconds(selection));
        long now = player.level().getGameTime();
        boolean emptyMagazine = state.ammo() == 0;
        ReloadSession session = new ReloadSession(player.getUUID(), hand, slotOf(player, hand), itemIdOf(stack),
                sequence, now, now + durationTicks, ReloadSequenceSelector.animationName(selection), durationTicks,
                state.ammo(), emptyMagazine, emptyMagazine ? SOUND_EMPTY : SOUND_STANDARD);

        WeaponReloadRuntime.StartResult start = WeaponReloadRuntime.start(session);
        if (start == WeaponReloadRuntime.StartResult.ALREADY_RELOADING) {
            return reject(player, hand, sequence, WeaponActionResult.RELOAD_IN_PROGRESS);
        }
        if (start == WeaponReloadRuntime.StartResult.STALE_REQUEST) {
            return reject(player, hand, sequence, WeaponActionResult.STALE_REQUEST);
        }

        Constants.LOG.debug("[ARG] {} started a reload of '{}' in {} ({} round(s) loaded, {} tick(s), animation {})",
                player.getName().getString(), definition.name(), hand, state.ammo(), durationTicks,
                session.animation() == null ? "<none>" : session.animation());
        return send(player, session, WeaponActionResult.RELOAD_STARTED);
    }

    /**
     * Advances every active session by one server tick.
     *
     * <p>Cancels a session whose player is gone, ineligible, or no longer holding the weapon it started with;
     * completes one whose duration elapsed. Called from the loaders' server tick events, i.e. on the server
     * thread.</p>
     *
     * @param server the running server, may be {@code null}
     */
    public static void serverTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ReloadSession session : WeaponReloadRuntime.sessions()) {
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId());
            if (player == null) {
                // The player left; the loader's disconnect hook also clears this, this is the safety net.
                WeaponReloadRuntime.remove(session.playerId(), session.hand());
                continue;
            }
            WeaponActionResult invalid = validateSession(player, session);
            if (invalid != null) {
                cancel(player, session, invalid);
                continue;
            }
            if (session.isComplete(player.level().getGameTime())) {
                complete(player, session);
            }
        }
    }

    /**
     * Performs the transfer and ends the session, or cancels it when its preconditions no longer hold.
     *
     * @param player  the player the session belongs to
     * @param session the session that reached its end
     */
    private static void complete(ServerPlayer player, ReloadSession session) {
        ItemStack stack = player.getItemInHand(session.hand());
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            cancel(player, session, WeaponActionResult.NOT_A_WEAPON);
            return;
        }
        WeaponDefinition definition = weapon.definition();
        WeaponState state = weapon.state(stack);
        AmmoCapacity capacity = weapon.ammoCapacity();
        if (capacity == null || capacity.isUnlimited()) {
            // A definition change while a session was running: nothing is transferable any more.
            cancel(player, session, WeaponActionResult.RELOAD_NOT_APPLICABLE);
            return;
        }
        List<ResourceId> compatible = AmmunitionRegistry.compatibleItemIds(definition);
        Inventory inventory = player.getInventory();
        List<ReserveAmmunition.Slot> slots = ReserveAmmunition.slotsOf(inventory);

        int reserve = ReserveAmmunition.count(compatible, slots);
        int rounds = capacity.rounds();
        int amount = ReloadRules.transfer(rounds, state.ammo(), reserve);
        if (amount <= 0) {
            cancel(player, session, ReloadRules.room(rounds, state.ammo()) == 0
                    ? WeaponActionResult.MAGAZINE_FULL : WeaponActionResult.NO_RESERVE_AMMUNITION);
            return;
        }

        ReloadAmmunitionPlan plan = ReloadAmmunitionPlan.plan(compatible,
                ReloadAmmunitionPlan.candidates(slots), amount);
        if (!plan.covers(amount) || !commit(inventory, plan)) {
            // The inventory changed between counting and committing; nothing has been moved.
            cancel(player, session, WeaponActionResult.NO_RESERVE_AMMUNITION);
            return;
        }

        // One write, after the transfer succeeded: the magazine now holds what the plan actually moved.
        stack.set(WeaponComponents.WEAPON_STATE, weapon.stateWithAmmo(stack, state.ammo() + amount));
        inventory.setChanged();
        WeaponReloadRuntime.remove(session.playerId(), session.hand());

        Constants.LOG.debug("[ARG] {} completed a reload of '{}' in {}: {} round(s) loaded, {} left in the magazine",
                player.getName().getString(), definition.name(), session.hand(), amount, state.ammo() + amount);
        send(player, session, WeaponActionResult.RELOAD_COMPLETED);
    }

    /**
     * Applies a validated plan, or changes nothing at all.
     *
     * <p>Two passes: the first re-checks every planned slot against the plan (same item id, still enough
     * items), the second mutates. Because the first pass is complete before the second starts, a stack that
     * changed while the reload ran cancels the whole transfer instead of producing a partial one.</p>
     *
     * @param inventory the player's inventory
     * @param plan      the plan to apply
     * @return {@code true} when every deduction was applied
     */
    private static boolean commit(Inventory inventory, ReloadAmmunitionPlan plan) {
        for (ReloadAmmunitionPlan.Deduction deduction : plan.deductions()) {
            ItemStack slotStack = inventory.getItem(deduction.slot());
            if (slotStack == null || slotStack.isEmpty()) {
                return false;
            }
            if (!itemIdOf(slotStack).equals(deduction.itemId().asString())) {
                return false;
            }
            if (slotStack.getCount() < deduction.quantity()) {
                return false;
            }
        }
        for (ReloadAmmunitionPlan.Deduction deduction : plan.deductions()) {
            ItemStack slotStack = inventory.getItem(deduction.slot());
            slotStack.shrink(deduction.quantity());
            if (slotStack.isEmpty()) {
                // Normal inventory convention: an exhausted stack leaves an empty slot, and the untouched
                // components of every other stack are preserved because shrinking never rewrites them.
                inventory.setItem(deduction.slot(), ItemStack.EMPTY);
            }
        }
        return true;
    }

    /**
     * Cancels one session without transferring anything.
     *
     * @param player  the player the session belongs to, may be {@code null}
     * @param session the session to end
     * @param reason  what forbade completion
     */
    private static void cancel(ServerPlayer player, ReloadSession session, WeaponActionResult reason) {
        WeaponReloadRuntime.remove(session.playerId(), session.hand());
        Constants.LOG.debug("[ARG] Reload in {} was cancelled ({}); no ammunition was transferred",
                session.hand(), reason);
        send(player, session, WeaponActionResult.RELOAD_CANCELLED);
    }

    /**
     * @param player  the player the session belongs to
     * @param session the session to check
     * @return the first condition that forbids completing the session, or {@code null} when it may complete
     */
    private static WeaponActionResult validateSession(ServerPlayer player, ReloadSession session) {
        if (!player.isAlive() || player.isSpectator()) {
            return WeaponActionResult.INVALID_CONTEXT;
        }
        if (!WeaponCompatibility.isUsable(player.getUUID())) {
            return WeaponActionResult.CONTENT_MISMATCH;
        }
        ItemStack stack = player.getItemInHand(session.hand());
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return WeaponActionResult.NOT_A_WEAPON;
        }
        if (!itemIdOf(stack).equals(session.weaponItemId())) {
            // A different weapon now occupies the hand.
            return WeaponActionResult.NOT_A_WEAPON;
        }
        if (slotOf(player, session.hand()) != session.slot()) {
            // The same item moved to another hotbar slot; the reload belonged to the stack that started it.
            return WeaponActionResult.NOT_A_WEAPON;
        }
        if (weapon.state(stack) == null) {
            return WeaponActionResult.INVALID_WEAPON_STATE;
        }
        AmmoCapacity capacity = weapon.ammoCapacity();
        if (capacity == null || capacity.isUnlimited()) {
            // The definition became unloadable for this session (a resource reload can change it mid-flight).
            return WeaponActionResult.RELOAD_NOT_APPLICABLE;
        }
        return null;
    }

    private static ReloadOutcome reject(ServerPlayer player, InteractionHand hand, int sequence,
                                        WeaponActionResult result) {
        ReloadOutcome outcome = new ReloadOutcome(result, null, 0, false);
        if (player != null && hand != null && sequence >= 1) {
            send(player, hand, sequence, outcome);
        }
        return outcome;
    }

    private static ReloadOutcome send(ServerPlayer player, ReloadSession session, WeaponActionResult result) {
        ReloadOutcome outcome = new ReloadOutcome(result, session.animation(), session.durationTicks(),
                session.emptyMagazine());
        send(player, session.hand(), session.sequence(), outcome);
        return outcome;
    }

    private static void send(ServerPlayer player, InteractionHand hand, int sequence, ReloadOutcome outcome) {
        if (player == null || hand == null || !Services.NETWORK.canSendToPlayer(player,
                WeaponReloadResultPayload.TYPE)) {
            return;
        }
        Services.NETWORK.sendToPlayer(player, new WeaponReloadResultPayload(hand, sequence, outcome.result(),
                outcome.animation() == null ? "" : outcome.animation(), outcome.durationTicks(),
                outcome.emptyMagazine()));
    }

    /**
     * @param player the player
     * @param hand   the hand
     * @return the inventory slot the hand resolves to: the selected hotbar slot for the main hand, the
     *         off-hand slot otherwise
     */
    public static int slotOf(ServerPlayer player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND
                ? player.getInventory().getSelectedSlot()
                : Inventory.SLOT_OFFHAND;
    }

    private static String itemIdOf(ItemStack stack) {
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
