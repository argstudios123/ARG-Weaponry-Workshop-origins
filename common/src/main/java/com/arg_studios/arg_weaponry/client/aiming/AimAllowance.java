package com.arg_studios.arg_weaponry.client.aiming;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;


public final class AimAllowance {

    private AimAllowance() {
    }

    public static AimBlockReason evaluate(boolean alive, boolean weaponHeld, boolean inputFocused,
                                          WeaponDefinition.Aiming aiming, boolean modelResolved,
                                          boolean hasAimingBone,
                                          boolean inspecting, boolean reloading, boolean equipping) {
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
        if (inspecting) {
            return AimBlockReason.INSPECTING;
        }
        if (aiming.noAimingOnReload() && reloading) {
            return AimBlockReason.RELOADING;
        }
        if (equipping) {
            // [DrawHolster hook] Aiming is blocked for the whole draw/holster window, unconditionally: the weapon is not in
            // the player's hands yet (or is leaving them), so there is no sight to align.
            return AimBlockReason.EQUIPPING;
        }
        return AimBlockReason.NONE;
    }
}
