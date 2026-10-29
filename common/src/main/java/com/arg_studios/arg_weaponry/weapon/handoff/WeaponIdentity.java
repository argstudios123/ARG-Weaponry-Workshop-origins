package com.arg_studios.arg_weaponry.weapon.handoff;

/**
 * Identity of the weapon a hand holds, used only to answer one question: <em>did the visual weapon really change?</em>
 *
 * <p>The handoff subsystem needs this because a hotbar request alone does not say whether the player asked for a different
 * weapon or for the same one: two slots can hold the same item, and the same slot can hold the same item before and after
 * a request. A transition is started only when the identity differs, and a retarget only replaces the destination when it
 * differs from the current one.</p>
 *
 * <p>The identity is the registry id of the item plus the slot the stack was adopted from. A stack identity token (a
 * persistent per-stack UUID, as the reference implementations carry) is deliberately not part of it: this project has no
 * such component, and inventing one would change the data model. The one consequence is documented on
 * {@code VisualWeapon}: replacing the held stack with an identical stack in the same slot is treated as "the same
 * weapon", which is also what it looks like.</p>
 *
 * @param itemId the item's registry id, or an empty string for "no weapon"
 * @param slot   the slot the stack was taken from, or {@code -1} for "no weapon"
 */
public record WeaponIdentity(String itemId, int slot) {

    /** The identity of a hand that holds no weapon. */
    public static final WeaponIdentity NONE = new WeaponIdentity("", -1);

    public WeaponIdentity {
        if (itemId == null) {
            throw new IllegalArgumentException("A weapon identity must name its item");
        }
    }

    /** @return whether this identity describes an actual weapon. */
    public boolean isPresent() {
        return !itemId.isEmpty() && slot >= 0;
    }

    /**
     * @param other the identity to compare with, may be {@code null}
     * @return {@code true} when both describe the same weapon in the same slot
     */
    public boolean sameAs(WeaponIdentity other) {
        return other != null && equals(other);
    }
}
