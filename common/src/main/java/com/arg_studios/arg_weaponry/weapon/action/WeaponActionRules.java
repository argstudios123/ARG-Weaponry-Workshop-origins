package com.arg_studios.arg_weaponry.weapon.action;

import com.arg_studios.arg_weaponry.weapon.AmmoCapacity;
import com.arg_studios.arg_weaponry.weapon.fire.FiringModes;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import com.arg_studios.arg_weaponry.weapon.state.WeaponState;

import java.util.List;

/**
 * The pure decision rules behind weapon-action validation.
 *
 * <p>Everything here is a function of two already-resolved inputs — the definition-derived magazine
 * capacity and the state the stack carries — and nothing else. There is no player, no level, no inventory,
 * no item and no random source, so the same pair of values always yields the same result. That is what
 * makes the rules testable without a running game, and it is why {@link WeaponActions} exists as a
 * separate, thin server-facing entry point: it performs the player and held-stack lookup and then
 * delegates here, so the lookup and the decision can change independently.</p>
 *
 * <h2>Read-only</h2>
 * <p>Both methods inspect immutable values: the definition is funnelled in as an {@link AmmoCapacity} and
 * {@link WeaponState} is an immutable record. No method creates a replacement state, writes a component,
 * touches an {@code ItemStack}, starts an animation or arms a cooldown; a validation result never implies
 * that anything happened.</p>
 *
 * <h2>Capacity is an input, not a lookup</h2>
 * <p>The capacity is passed in by the caller instead of being derived here, because deriving it means
 * reading {@code WeaponDefinition#ammoCapacity()} through {@code WeaponItem#ammoCapacity()} — and
 * {@code WeaponItem} is a Minecraft item class that cannot be loaded outside a bootstrapped game. Keeping
 * the value as a parameter lets the rules stay pure without duplicating the definition's "no declared
 * capacity means no magazine" rule in a second place. Callers pass
 * {@link com.arg_studios.arg_weaponry.item.WeaponItem#ammoCapacity()} of the held item, which stays the
 * single source of truth and also carries the unlimited-ammunition mode.</p>
 *
 * <h2>Check order</h2>
 * <p>Checks always run in a fixed order: unreadable state, then "does this weapon have a magazine at all",
 * then the ammunition condition. A weapon without a magazine therefore reports
 * {@link WeaponActionResult#UNSUPPORTED} rather than "empty" or "full", and an unreadable state is never
 * reported as a magazine condition.</p>
 *
 * <h2>Persisted ammunition above the current capacity</h2>
 * <p>A content pack can lower {@code ammo_capacity} under a stack that already holds more rounds than the
 * new capacity allows; milestone 10B documents that such a stored count is not clamped on load. These
 * rules treat the value as exactly what it is — a magazine holding more rounds than the definition now
 * says it can — and add no special case on top:</p>
 * <ul>
 *   <li>firing only needs ammunition, so a magazine above capacity may fire;</li>
 *   <li>reloading needs room, and {@link WeaponState#isFull(int)} is {@code ammo >= capacity}, so a
 *       magazine above capacity reports {@link WeaponActionResult#MAGAZINE_FULL}.</li>
 * </ul>
 * <p>Nothing is refilled, clamped or rewritten while validating. Correcting a stored count that exceeds
 * the current capacity is an explicit normalisation or migration operation for a later milestone, and
 * until one exists every stack keeps whatever its component says.</p>
 */
public final class WeaponActionRules {

    private WeaponActionRules() {
    }

    /**
     * Decides whether a firing request against the given state may be executed.
     *
     * <p>Only the conditions a firing request can be judged on without a world are checked: the weapon has a
     * magazine, that magazine holds at least one round, and the stored fire mode is one this build can
     * execute. Damage, cadence, spread and recoil are execution-time concerns handled by the firing
     * operation; the client is never asked about any of them.</p>
     *
     * <p><strong>Unlimited ammunition</strong> (Milestone 10P): a weapon configured with
     * {@code "ammo_capacity": "unlimited"} has no magazine that can be empty, so the ammunition condition is
     * skipped and the stored count may be zero. Nothing else is relaxed - the capacity must still exist and the
     * fire mode is still checked - because unlimited ammunition means ammunition is not consumed, not that the
     * weapon rules are bypassed.</p>
     *
     * <p><strong>Firing-mode selection</strong> (Milestone 10Q): the mode that is actually executed is
     * {@link FiringModes#resolve(List, FireMode)} of the weapon's supported modes and the mode stored on the stack.
     * A stack whose stored mode the definition does not support therefore still fires, in the documented fallback
     * mode, instead of becoming unusable; only a weapon that declares no mode at all reports
     * {@link WeaponActionResult#FIRE_MODE_UNSUPPORTED}. The check runs before the ammunition check, so a weapon that
     * cannot fire at all is never reported as "empty".</p>
     *
     * @param ammoCapacity   the held weapon's definition-derived capacity, {@code null} when it declares none
     * @param state          the state the held stack carries, may be {@code null} when none is readable
     * @param supportedModes the firing modes the held weapon's definition declares, in authored order
     * @return {@link WeaponActionResult#OK} when the request may be executed, otherwise the first condition
     *         that forbids it
     */
    public static WeaponActionResult validateFire(AmmoCapacity ammoCapacity, WeaponState state,
                                                 List<FireMode> supportedModes) {
        if (state == null) {
            return WeaponActionResult.INVALID_WEAPON_STATE;
        }
        if (ammoCapacity == null) {
            return WeaponActionResult.UNSUPPORTED;
        }
        if (FiringModes.resolve(supportedModes, state.fireMode()) == null) {
            return WeaponActionResult.FIRE_MODE_UNSUPPORTED;
        }
        if (ammoCapacity.isFinite() && state.isEmpty()) {
            return WeaponActionResult.MAGAZINE_EMPTY;
        }
        return WeaponActionResult.OK;
    }

    /**
     * The mode a stack actually fires in.
     *
     * @param state          the state the held stack carries, may be {@code null}
     * @param supportedModes the supported modes, in authored order
     * @return the resolved mode, or {@code null} when the weapon declares none
     */
    public static FireMode effectiveFireMode(WeaponState state, List<FireMode> supportedModes) {
        return state == null ? null : FiringModes.resolve(supportedModes, state.fireMode());
    }

    /**
     * Decides whether a reloading request against the given state may be executed.
     *
     * <p>Only the conditions these pure rules can evaluate are checked: the weapon has a finite magazine, that
     * magazine has room, and the caller has already counted compatible reserve ammunition in the real
     * inventory. The count is a parameter rather than a lookup because counting needs a live server player;
     * callers pass {@link com.arg_studios.arg_weaponry.weapon.ammo.ReserveAmmunition#available}, which stays the
     * single source of truth.</p>
     *
     * <p><strong>Unlimited ammunition</strong> (Milestone 10P): a weapon whose ammunition is unlimited never
     * consumes any, so a reload has nothing to do. The request reports
     * {@link WeaponActionResult#RELOAD_NOT_APPLICABLE} and no session is started - before the reserve ammunition
     * is looked at, so nothing can be consumed and no stale session can be created.</p>
     *
     * <p>The result is a real decision now: {@link WeaponActionResult#OK} means a session may start. It still
     * does not mean anything was transferred — the transfer happens when the session completes, and only after
     * it has been revalidated.</p>
     *
     * @param ammoCapacity     the held weapon's definition-derived capacity, {@code null} when it declares none
     * @param state            the state the held stack carries, may be {@code null} when none is readable
     * @param reserveAvailable the compatible rounds actually present in the player's inventory
     * @return {@link WeaponActionResult#OK} when a reload may start, otherwise the first condition that forbids
     *         it
     */
    public static WeaponActionResult validateReload(AmmoCapacity ammoCapacity, WeaponState state,
                                                    int reserveAvailable) {
        if (state == null) {
            return WeaponActionResult.INVALID_WEAPON_STATE;
        }
        if (ammoCapacity == null) {
            return WeaponActionResult.UNSUPPORTED;
        }
        if (ammoCapacity.isUnlimited()) {
            return WeaponActionResult.RELOAD_NOT_APPLICABLE;
        }
        if (state.isFull(ammoCapacity.rounds())) {
            return WeaponActionResult.MAGAZINE_FULL;
        }
        if (reserveAvailable <= 0) {
            return WeaponActionResult.NO_RESERVE_AMMUNITION;
        }
        return WeaponActionResult.OK;
    }
}
