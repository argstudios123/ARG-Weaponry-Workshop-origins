package com.arg_studios.arg_weaponry.weapon.ammo;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classifies the entries of a weapon's existing {@code ammo_to_use} array.
 *
 * <p>{@code ammo_to_use} is the weapon's ammunition-compatibility contract and is authored as a list of
 * strings, for example {@code ["dmr_ammo", "creative_ammo"]}. This class decides what each authored string
 * resolves to, without changing, rewriting or reinterpreting any of them:</p>
 * <ul>
 *   <li>{@link Kind#AMMUNITION} - the entry equals an ammunition definition's {@code ammo_name}, so it
 *       contributes that definition's {@code ammo_id}. This is the intended form: {@code "dmr_ammo"} becomes
 *       {@code arg_pack:dmr_ammo} through the definition, never by guessing;</li>
 *   <li>{@link Kind#ITEM_ID} - no definition claims the entry, but it already is a valid
 *       {@code namespace:path} identifier, so it is read as a direct Minecraft item id. This preserves the
 *       behaviour milestone 10K established for packs that never declared ammunition definitions;</li>
 *   <li>{@link Kind#UNRESOLVED_NAME} - a bare name that no definition claims. It contributes no ammunition
 *       and is never replaced by an unrelated item. The special-looking names {@code creative_ammo} and
 *       {@code unlimited} are deliberately not special-cased: {@code creative_ammo} resolves only because a
 *       definition declares it, and {@code unlimited} stays unresolved because none does.</li>
 * </ul>
 *
 * <p>Classification needs an ammunition name-to-item-id map, which {@link AmmunitionRegistry} supplies. The
 * overloads without a map behave as if no ammunition definitions existed, which is exactly what 10K did, and
 * keep the class testable without building an index.</p>
 *
 * @param authored       the value exactly as the content pack wrote it
 * @param kind           what the value resolved to
 * @param itemId         the item identity for {@link Kind#AMMUNITION} and {@link Kind#ITEM_ID}, else {@code null}
 * @param ammunitionName the matching definition's {@code ammo_name}, or {@code null} when none matched
 */
public record AmmunitionEntries(String authored, Kind kind, ResourceId itemId, String ammunitionName) {

    /** What an {@code ammo_to_use} entry resolved to. */
    public enum Kind {

        /** Matched an ammunition definition and contributes that definition's {@code ammo_id}. */
        AMMUNITION,

        /** Written as {@code namespace:path} and not claimed by a definition: a direct item reference. */
        ITEM_ID,

        /** A bare name that neither a definition nor the identifier grammar can resolve. */
        UNRESOLVED_NAME
    }

    public AmmunitionEntries {
        if (authored == null || authored.isBlank()) {
            throw new IllegalArgumentException("An ammunition entry must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("An ammunition entry must have a kind");
        }
        boolean resolved = kind != Kind.UNRESOLVED_NAME;
        if (resolved && itemId == null) {
            throw new IllegalArgumentException("A resolved ammunition entry must carry its item id");
        }
        if (!resolved && itemId != null) {
            throw new IllegalArgumentException("An unresolved ammunition name must not carry an item id");
        }
        if ((kind == Kind.AMMUNITION) != (ammunitionName != null)) {
            throw new IllegalArgumentException("Only a definition-backed entry carries the definition's name");
        }
    }

    /** @return {@code true} when this entry contributes a Minecraft item. */
    public boolean isItemId() {
        return kind != Kind.UNRESOLVED_NAME;
    }

    /** @return {@code true} when this entry matched an ammunition definition. */
    public boolean isAmmunition() {
        return kind == Kind.AMMUNITION;
    }

    /** @return {@code true} when this build cannot resolve the entry to an item. */
    public boolean isUnresolvedName() {
        return kind == Kind.UNRESOLVED_NAME;
    }

    // --- classification ---

    /**
     * Classifies one authored value against the ammunition definitions of one load.
     *
     * @param authored        the value as written, may be {@code null}
     * @param ammoItemsByName {@code ammo_name} to {@code ammo_id}, may be {@code null} for "no definitions"
     * @return the entry, or {@code null} when the value is {@code null} or blank
     */
    public static AmmunitionEntries of(String authored, Map<String, ResourceId> ammoItemsByName) {
        if (authored == null || authored.isBlank()) {
            return null;
        }
        ResourceId defined = ammoItemsByName == null ? null : ammoItemsByName.get(authored);
        if (defined != null) {
            return new AmmunitionEntries(authored, Kind.AMMUNITION, defined, authored);
        }
        ResourceId.Result direct = ResourceId.tryParse(authored);
        if (direct.isValid()) {
            return new AmmunitionEntries(authored, Kind.ITEM_ID, direct.id(), null);
        }
        return new AmmunitionEntries(authored, Kind.UNRESOLVED_NAME, null, null);
    }

    /**
     * @param authored the value as written, may be {@code null}
     * @return the entry classified as if no ammunition definition existed
     */
    public static AmmunitionEntries of(String authored) {
        return of(authored, Map.of());
    }

    /**
     * Classifies a weapon's {@code ammo_to_use} entries, in authored order.
     *
     * @param definition the weapon definition, may be {@code null}
     * @param ammoItemsByName {@code ammo_name} to {@code ammo_id}, may be {@code null}
     * @return the classified entries; empty when the definition declares none
     */
    public static List<AmmunitionEntries> of(WeaponDefinition definition,
                                            Map<String, ResourceId> ammoItemsByName) {
        return definition == null ? List.of() : of(definition.ammoToUse(), ammoItemsByName);
    }

    /**
     * @param definition the weapon definition, may be {@code null}
     * @return the classified entries as if no ammunition definition existed
     */
    public static List<AmmunitionEntries> of(WeaponDefinition definition) {
        return of(definition, Map.of());
    }

    /**
     * Classifies a list of authored values, in authored order.
     *
     * <p>Order and duplicates are preserved exactly as authored: resolving to a set is the caller's decision,
     * not this one's.</p>
     *
     * @param ammoToUse       the authored values, may be {@code null}
     * @param ammoItemsByName {@code ammo_name} to {@code ammo_id}, may be {@code null}
     * @return the classified entries; blank values are skipped because they cannot resolve to anything
     */
    public static List<AmmunitionEntries> of(List<String> ammoToUse,
                                            Map<String, ResourceId> ammoItemsByName) {
        if (ammoToUse == null || ammoToUse.isEmpty()) {
            return List.of();
        }
        List<AmmunitionEntries> entries = new ArrayList<>(ammoToUse.size());
        for (String authored : ammoToUse) {
            AmmunitionEntries entry = of(authored, ammoItemsByName);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    /**
     * @param ammoToUse the authored values, may be {@code null}
     * @return the classified entries as if no ammunition definition existed
     */
    public static List<AmmunitionEntries> of(List<String> ammoToUse) {
        return of(ammoToUse, Map.of());
    }

    /**
     * @param entries classified entries, may be {@code null}
     * @return the item ids among them, in authored order, without duplicates
     */
    public static List<ResourceId> itemIds(List<AmmunitionEntries> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Set<ResourceId> itemIds = new LinkedHashSet<>();
        for (AmmunitionEntries entry : entries) {
            if (entry.isItemId()) {
                itemIds.add(entry.itemId());
            }
        }
        return List.copyOf(itemIds);
    }

    /**
     * @param entries classified entries, may be {@code null}
     * @return the authored values that resolved to nothing, in authored order, without duplicates
     */
    public static List<String> unresolvedNames(List<AmmunitionEntries> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (AmmunitionEntries entry : entries) {
            if (entry.isUnresolvedName()) {
                names.add(entry.authored());
            }
        }
        return List.copyOf(names);
    }

    /**
     * @param definition the weapon definition, may be {@code null}
     * @return the item ids the definition accepts, as if no ammunition definition existed
     */
    public static List<ResourceId> itemIdsOf(WeaponDefinition definition) {
        return itemIds(of(definition));
    }

    /**
     * @param definition      the weapon definition, may be {@code null}
     * @param ammoItemsByName {@code ammo_name} to {@code ammo_id}, may be {@code null}
     * @return the item ids the definition accepts, in authored order, without duplicates
     */
    public static List<ResourceId> itemIdsOf(WeaponDefinition definition,
                                             Map<String, ResourceId> ammoItemsByName) {
        return itemIds(of(definition, ammoItemsByName));
    }
}
