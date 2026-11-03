package com.arg_studios.arg_weaponry.weapon.ammo;

/**
 * The Creative-mode ammunition rule.
 *
 * <h2>What it decides, and why it is a separate value</h2>
 * <p>A weapon lists its ammunition in {@code ammo_to_use}, for example {@code ["dmr_ammo", "creative_ammo"]}. The
 * dedicated rounds are the authored priority and are always spent first; {@code creative_ammo} is the universal fallback a
 * pack ships so that a Creative player can test the weapon without farming rounds. Nothing in the framework special-cased
 * the name, so a Creative player whose dedicated stack ran out (or who carried none) was simply told there is no reserve
 * ammunition - the reload refused to start even though the weapon declares the fallback.</p>
 *
 * <p>The rule below is deliberately tiny, pure and independent of Minecraft types:</p>
 * <ul>
 *   <li>the fallback applies only in Creative <em>and</em> only when the weapon really declares the {@code creative_ammo}
 *   entry, so a Survival reload is bit-for-bit the behaviour it had before, and a weapon that does not declare the
 *   fallback never becomes free;</li>
 *   <li>when it applies, the reserve the reload validates against is raised to at least one full magazine, so the reload
 *   always starts;</li>
 *   <li>{@link #consumable(int, int)} keeps the dedicated rounds first: only the part of the transfer that the real
 *   inventory can cover is taken from it, and the remainder is supplied by the Creative fallback without consuming an
 *   item. A Creative player therefore never loses their dedicated ammunition to a reload that the fallback could have
 *   covered, and the reload is never blocked by an empty inventory.</li>
 * </ul>
 *
 * <h2>Why the fallback is not consumed</h2>
 * <p>{@code creative_ammo} exists to make a weapon testable in Creative, and Creative mode itself is the mode in which
 * the framework treats ammunition as a testing resource rather than an economy. Deducting the rounds would make the
 * fallback run out after one magazine and put the player back into the "cannot reload" state this rule removes, so the
 * Creative supply is unlimited by design. Outside Creative nothing changes: every round is taken from a real stack.</p>
 */
public final class CreativeAmmunition {

    /** The {@code ammo_to_use} entry that names the Creative fallback. */
    public static final String FALLBACK_NAME = "creative_ammo";

    private CreativeAmmunition() {
    }

    /**
     * @param entry a classified {@code ammo_to_use} entry, may be {@code null}
     * @return whether that entry is the Creative fallback
     */
    public static boolean isFallback(AmmunitionEntries entry) {
        return entry != null && FALLBACK_NAME.equals(entry.authored());
    }

    /**
     * @param creative          whether the player is in Creative mode
     * @param fallbackDeclared  whether the weapon declares the Creative fallback
     * @return whether the Creative supply covers this reload
     */
    public static boolean grantsUnlimited(boolean creative, boolean fallbackDeclared) {
        return creative && fallbackDeclared;
    }

    /**
     * The reserve a reload validates against.
     *
     * <p>With the Creative supply the reserve is raised to a full magazine, which is everything a reload can ever need; a
     * larger real reserve is kept so the validation result stays identical to Survival when the player does carry more.</p>
     *
     * @param reserve          the rounds the player really carries
     * @param magazineRounds   the weapon's magazine size
     * @param unlimited        whether the Creative supply applies
     * @return the reserve to validate with
     */
    public static int reserveFor(int reserve, int magazineRounds, boolean unlimited) {
        if (!unlimited) {
            return Math.max(0, reserve);
        }
        return Math.max(Math.max(0, reserve), Math.max(0, magazineRounds));
    }

    /**
     * How many of the rounds a reload transfers must be taken from the inventory.
     *
     * @param amount  the rounds the reload transfers
     * @param reserve the rounds the player really carries
     * @return the part of {@code amount} the inventory has to supply, never negative
     */
    public static int consumable(int amount, int reserve) {
        return Math.max(0, Math.min(amount, Math.max(0, reserve)));
    }
}
