package com.arg_studios.arg_weaponry.weapon;

/**
 * How a weapon's {@code ammo_capacity} is configured: a finite magazine or unlimited ammunition.
 *
 * <p>The schema has always carried a positive integer. Milestone 10P adds exactly one reserved string value,
 * {@value #UNLIMITED_TOKEN}, which means "this weapon does not consume ammunition": accepted shots leave the stored
 * magazine count untouched, no reserve ammunition is needed, and a reload is not applicable. It is a
 * <em>capacity mode</em>, never an ammunition item name - no ammunition definition or item called
 * {@code unlimited} is created, and {@code ammo_to_use} is unrelated to it.</p>
 *
 * <h2>Why a value type instead of an {@code int} plus a flag</h2>
 * <p>Every decision that depends on capacity - firing validation, reload validation, the reload transfer arithmetic,
 * the numeric bound used to clamp a stored count, the canonical definition digest and the ammunition count the
 * animation selectors see - has to know which mode the weapon is in. Keeping that knowledge in one immutable value
 * means none of those call sites compares strings, and a finite capacity can never be confused with "unlimited" by an
 * {@code int} sentinel such as {@code -1}, {@code 0} or {@link Integer#MAX_VALUE}.</p>
 *
 * <h2>Stored state</h2>
 * <p>Nothing about {@link com.arg_studios.arg_weaponry.weapon.state.WeaponState} changes. An unlimited weapon simply
 * keeps whatever ammunition count it was created with (fresh stacks start at zero) and no code path decrements it,
 * so no schema migration is needed and finite weapons keep their per-stack state exactly as before.</p>
 */
public final class AmmoCapacity {

    /**
     * The reserved {@code ammo_capacity} string that selects unlimited ammunition.
     *
     * <p>Reserved: it is never registered as an ammunition item and is never read as an ammunition name.</p>
     */
    public static final String UNLIMITED_TOKEN = "unlimited";

    /**
     * The ammunition count an animation condition sees for an unlimited weapon.
     *
     * <p>{@code idle_anims} and {@code fire_anims} are selected by {@code ammoCount <op> <n>} expressions, and an
     * unlimited weapon is never empty, so it must report a value that satisfies {@code ammoCount > 0} and never
     * satisfies {@code ammoCount == 0}. The largest reachable value keeps every existing expression's meaning for
     * finite weapons untouched and cannot be reached by a finite magazine.</p>
     */
    public static final int UNLIMITED_ANIMATION_COUNT = Integer.MAX_VALUE;

    private static final AmmoCapacity UNLIMITED = new AmmoCapacity(null);

    /** The finite round count, or {@code null} for unlimited. */
    private final Integer rounds;

    private AmmoCapacity(Integer rounds) {
        this.rounds = rounds;
    }

    /**
     * @param rounds the finite magazine capacity; must be greater than zero
     * @return the finite capacity
     */
    public static AmmoCapacity of(int rounds) {
        if (rounds <= 0) {
            throw new IllegalArgumentException("A finite ammo_capacity must be greater than 0 but is " + rounds);
        }
        return new AmmoCapacity(rounds);
    }

    /** @return the unlimited capacity, the same immutable instance every time */
    public static AmmoCapacity unlimited() {
        return UNLIMITED;
    }

    /** @return {@code true} when ammunition is not consumed */
    public boolean isUnlimited() {
        return rounds == null;
    }

    /** @return {@code true} when the weapon has a numeric magazine */
    public boolean isFinite() {
        return rounds != null;
    }

    /**
     * @return the finite magazine capacity
     * @throws IllegalStateException when this capacity is unlimited; callers must test {@link #isFinite()} first
     */
    public int rounds() {
        if (rounds == null) {
            throw new IllegalStateException("An unlimited ammo_capacity has no round count");
        }
        return rounds;
    }

    /**
     * @param storedRounds the ammunition count the stack carries
     * @return the count an animation condition should see: {@code storedRounds} for a finite weapon, or
     *         {@link #UNLIMITED_ANIMATION_COUNT} when ammunition is unlimited
     */
    public int animationCount(int storedRounds) {
        return rounds == null ? UNLIMITED_ANIMATION_COUNT : storedRounds;
    }

    /**
     * The numeric bound a stored count may be clamped into.
     *
     * <p>Unlimited weapons have no upper bound in principle; the clamp exists so that a stored count stays a valid
     * {@code int}, and nothing relies on this value to decide whether firing or reloading is allowed.</p>
     *
     * @return the finite capacity, or {@link Integer#MAX_VALUE} for unlimited
     */
    public int clampBound() {
        return rounds == null ? Integer.MAX_VALUE : rounds;
    }

    /**
     * The value written into the canonical gameplay-definition digest.
     *
     * <p>Deterministic and unambiguous: a finite capacity is its decimal number and an unlimited capacity is the
     * reserved token, so the two can never hash the same way and neither is serialised as a numeric fallback.</p>
     *
     * @return {@value #UNLIMITED_TOKEN} or the decimal round count
     */
    public String canonicalForm() {
        return rounds == null ? UNLIMITED_TOKEN : Integer.toString(rounds);
    }

    /**
     * @param value a raw {@code ammo_capacity} string
     * @return {@code true} when the value is the reserved unlimited token, matched case-insensitively
     */
    public static boolean isUnlimitedToken(String value) {
        return value != null && UNLIMITED_TOKEN.equalsIgnoreCase(value.trim());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AmmoCapacity capacity
                && (rounds == null ? capacity.rounds == null : rounds.equals(capacity.rounds));
    }

    @Override
    public int hashCode() {
        return rounds == null ? 0 : rounds.hashCode();
    }

    @Override
    public String toString() {
        return canonicalForm();
    }
}
