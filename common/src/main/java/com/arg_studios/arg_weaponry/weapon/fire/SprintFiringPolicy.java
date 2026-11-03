package com.arg_studios.arg_weaponry.weapon.fire;

/**
 * The one rule behind {@code allow_firing_when_sprinting}.
 *
 * <p>The client and the server both call this method, so a request cannot be accepted on one side and refused on the
 * other because the two wrote the condition differently. It is deliberately a pure boolean function with no Minecraft
 * type: the client passes its local player's sprint flag, the server passes its authoritative player's flag, and the
 * weapon supplies the configured permission.</p>
 *
 * <p>A missing property resolves to {@code true} in
 * {@link com.arg_studios.arg_weaponry.weapon.WeaponDefinition#allowFiringWhenSprinting()}, which preserves every existing
 * pack's behaviour. A weapon that explicitly writes {@code false} blocks semi-automatic, automatic, burst and sustained
 * fire because every firing mode funnels through the same authoritative validation.</p>
 */
public final class SprintFiringPolicy {

    private SprintFiringPolicy() {
    }

    /**
     * @param sprinting                 whether the player is sprinting right now
     * @param allowFiringWhenSprinting  the weapon's configured permission
     * @return {@code true} when a firing request may proceed
     */
    public static boolean allows(boolean sprinting, boolean allowFiringWhenSprinting) {
        return !sprinting || allowFiringWhenSprinting;
    }
}
