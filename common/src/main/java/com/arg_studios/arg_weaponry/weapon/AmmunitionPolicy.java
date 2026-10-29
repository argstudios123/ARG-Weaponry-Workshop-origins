package com.arg_studios.arg_weaponry.weapon;

import com.arg_studios.arg_weaponry.weapon.state.WeaponState;

/**
 * The one place that turns a weapon definition's {@code ammo_capacity} into the numbers the rest of the mod uses.
 *
 * <p>Deliberately free of Minecraft types (only {@link WeaponState}, itself a plain record with codecs), so the rules
 * that depend on the capacity mode can be verified without a running game - {@code WeaponItem} is an {@code Item}
 * subclass whose class initialisation needs a bootstrapped registry, and none of this arithmetic does.</p>
 *
 * <h2>What it centralises</h2>
 * <ul>
 *   <li>{@link #capacityOf(WeaponDefinition)} - the declared capacity, or {@code null} when the definition declares
 *       none (a melee weapon, for example);</li>
 *   <li>{@link #clampBound(WeaponDefinition)} - the numeric bound a stored count is clamped into;</li>
 *   <li>{@link #initialRounds(WeaponDefinition)} - how many rounds a fresh stack starts with: a full magazine for a
 *       finite weapon, zero for an unlimited one (it needs none, and zero is the only value that cannot be mistaken
 *       for a finite magazine state);</li>
 *   <li>{@link #animationCount(WeaponDefinition, WeaponState)} - the value the {@code ammoCount} animation conditions
 *       see, which is "never empty" for an unlimited weapon;</li>
 *   <li>{@link #consumesAmmunition(WeaponDefinition)} - whether an accepted shot decrements the stored count.</li>
 * </ul>
 */
public final class AmmunitionPolicy {

    private AmmunitionPolicy() {
    }

    /**
     * @param definition a parsed definition, may be {@code null}
     * @return the declared capacity - finite or unlimited - or {@code null} when none is declared
     */
    public static AmmoCapacity capacityOf(WeaponDefinition definition) {
        return definition == null ? null : definition.ammoCapacity();
    }

    /**
     * @param definition a parsed definition, may be {@code null}
     * @return the numeric bound a stored ammunition count is clamped into: the magazine size for a finite weapon,
     *         {@link Integer#MAX_VALUE} for an unlimited one, and {@code 0} when no capacity is declared
     */
    public static int clampBound(WeaponDefinition definition) {
        AmmoCapacity capacity = capacityOf(definition);
        return capacity == null ? 0 : capacity.clampBound();
    }

    /**
     * @param definition a parsed definition, may be {@code null}
     * @return the ammunition count a fresh stack starts with
     */
    public static int initialRounds(WeaponDefinition definition) {
        AmmoCapacity capacity = capacityOf(definition);
        return capacity != null && capacity.isFinite() ? capacity.rounds() : 0;
    }

    /**
     * @param definition a parsed definition, may be {@code null}
     * @return the state a fresh stack starts with
     */
    public static WeaponState initialState(WeaponDefinition definition) {
        return WeaponState.initial(initialRounds(definition));
    }

    /**
     * The ammunition count an animation condition must see.
     *
     * <p>A finite weapon reports its stored count, so {@code ammoCount == 0} keeps selecting the empty clips. An
     * unlimited weapon reports {@link AmmoCapacity#UNLIMITED_ANIMATION_COUNT}, so it selects the non-empty clips and can
     * never be treated as empty merely because its stored count is zero.</p>
     *
     * @param definition a parsed definition, may be {@code null}
     * @param state      the stored state, may be {@code null}
     * @return the count to hand to {@code IdleAnimationSelector}
     */
    public static int animationCount(WeaponDefinition definition, WeaponState state) {
        int stored = state == null ? 0 : state.ammo();
        AmmoCapacity capacity = capacityOf(definition);
        return capacity == null ? stored : capacity.animationCount(stored);
    }

    /**
     * @param definition a parsed definition, may be {@code null}
     * @return {@code false} when an accepted shot must not decrement the stored ammunition count, i.e. for a weapon
     *         configured with {@code "ammo_capacity": "unlimited"}
     */
    public static boolean consumesAmmunition(WeaponDefinition definition) {
        AmmoCapacity capacity = capacityOf(definition);
        return capacity == null || capacity.isFinite();
    }
}
