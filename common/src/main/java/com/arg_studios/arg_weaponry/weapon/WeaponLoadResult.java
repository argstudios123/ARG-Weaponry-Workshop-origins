package com.arg_studios.arg_weaponry.weapon;

import com.arg_studios.arg_weaponry.pack.ContentPack;

import java.util.List;

/**
 * The outcome of loading weapon definitions from all discovered content packs.
 *
 * <p>One broken definition never stops the others: rejected files are listed separately with the
 * reason, and every accepted definition keeps a reference to the content pack that supplied it, so
 * ownership is never inferred later from an item id.</p>
 *
 * @param weapons  the definitions that could be interpreted safely, in discovery order
 * @param rejected the definitions that were rejected, with a short reason
 */
public record WeaponLoadResult(List<LoadedWeapon> weapons, List<RejectedDefinition> rejected) {

    public WeaponLoadResult {
        weapons = List.copyOf(weapons);
        rejected = List.copyOf(rejected);
    }

    public static WeaponLoadResult empty() {
        return new WeaponLoadResult(List.of(), List.of());
    }

    /** @return how many definition files were found in total. */
    public int total() {
        return weapons.size() + rejected.size();
    }

    /** @return the parsed definitions without their owning packs, for logging and validation. */
    public List<WeaponDefinition> definitions() {
        return weapons.stream().map(LoadedWeapon::definition).toList();
    }

    /**
     * A parsed definition together with the content pack that supplied it.
     *
     * @param pack       the owning content pack
     * @param definition the parsed definition
     */
    public record LoadedWeapon(ContentPack pack, WeaponDefinition definition) {
    }

    /**
     * A weapon definition that could not be interpreted safely.
     *
     * @param packId the content pack identity
     * @param source the file, relative to the content pack
     * @param reason a short human readable reason; the detailed diagnostics are in the report
     */
    public record RejectedDefinition(String packId, String source, String reason) {
    }
}
