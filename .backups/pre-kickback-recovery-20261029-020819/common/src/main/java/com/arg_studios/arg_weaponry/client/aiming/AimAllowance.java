package com.arg_studios.arg_weaponry.client.aiming;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The pure rule that decides whether aim-down-sights may be entered.
 *
 * <p>Everything the decision needs is a plain flag, so the whole truth table is testable without a running game:
 * the player's liveness and input focus, whether a weapon is held, the weapon's own configuration, whether its geometry
 * has the {@code aiming_view} bone, and whether an action that forbids aiming is running.</p>
 *
 * <h2>Order matters</h2>
 * <p>The checks run from the most general to the most specific, so the reported reason is the first one that actually
 * applies: a player with no weapon gets {@code NO_WEAPON} even while reloading, and a weapon that forbids aiming while
 * inspecting reports {@code INSPECTING} rather than a generic refusal. The first reason is also the one logged once per
 * weapon, which is what makes a missing aiming bone easy to diagnose.</p>
 *
 * <h2>Defaults</h2>
 * <p>A missing {@code aiming} block means {@code DISABLED}, and a missing restriction property means "do not restrict":
 * a weapon that never asked for a restriction cannot gain one from this milestone.</p>
 */
public final class AimAllowance {

    private AimAllowance() {
    }

    /**
     * Decides whether aiming may start or continue.
     *
     * @param alive         whether the player is alive
     * @param weaponHeld    whether a weapon is in the main hand
     * @param inputFocused  whether world input is being read (no screen, mouse grabbed)
     * @param aiming        the weapon's {@code aiming} block, may be {@code null}
     * @param modelResolved whether the weapon's model could be resolved at all
     * @param hasAimingBone whether the weapon's geometry declares {@code aiming_view}
     * @param inspecting    whether an inspection is running
     * @param reloading     whether a reload is running
     * @return the reason aiming is refused, or {@link AimBlockReason#NONE}
     */
    public static AimBlockReason evaluate(boolean alive, boolean weaponHeld, boolean inputFocused,
                                          WeaponDefinition.Aiming aiming, boolean modelResolved,
                                          boolean hasAimingBone,
                                          boolean inspecting, boolean reloading) {
        if (!alive) {
            return AimBlockReason.NOT_ALIVE;
        }
        if (!weaponHeld) {
            return AimBlockReason.NO_WEAPON;
        }
        if (!inputFocused) {
            return AimBlockReason.NO_INPUT_FOCUS;
        }
        if (aiming == null || !aiming.enabled()) {
            return AimBlockReason.DISABLED;
        }
        if (!modelResolved) {
            return AimBlockReason.NO_MODEL;
        }
        if (!hasAimingBone) {
            return AimBlockReason.NO_AIMING_BONE;
        }
        if (aiming.noAimingOnInspect() && inspecting) {
            return AimBlockReason.INSPECTING;
        }
        if (aiming.noAimingOnReload() && reloading) {
            return AimBlockReason.RELOADING;
        }
        return AimBlockReason.NONE;
    }
}
