package com.arg_studios.arg_weaponry.weapon.ammo;

import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The index of every accepted ammunition definition, and the single place that turns a weapon's
 * {@code ammo_to_use} entries into real item ids.
 *
 * <h2>Resolution rule</h2>
 * <p>For one authored entry, in order:</p>
 * <ol>
 *   <li>an ammunition definition whose {@code ammo_name} equals the entry exactly wins, and contributes its
 *       {@code ammo_id}. This is the intended form and how {@code "dmr_ammo"} and {@code "creative_ammo"}
 *       resolve;</li>
 *   <li>otherwise, a value that is already a valid {@code namespace:path} identifier is treated as a direct
 *       Minecraft item id, which is the behaviour milestone 10K established for packs that never declared
 *       ammunition definitions;</li>
 *   <li>otherwise the entry resolves to nothing and is reported by the content-pack diagnostics. A bare name
 *       is never guessed into a registry id.</li>
 * </ol>
 * <p>Results are de-duplicated in authored order, so two entries that resolve to the same item can never be
 * counted twice.</p>
 *
 * <h2>Names are global, packs are not</h2>
 * <p>{@code ammo_to_use} carries no namespace, so an ammunition name is a global compatibility identity.
 * {@link AmmunitionDefinitionLoader} rejects a second definition claiming a name that is already taken, which
 * keeps this index unambiguous. The owning pack is still recorded, because display resources and creative tabs
 * are per pack.</p>
 *
 * <h2>When it is built</h2>
 * <p>During {@code ArgWeaponry.init()}, from definitions discovered from the same content packs as the
 * weapons, and <em>before</em> weapon definitions are validated, so weapon diagnostics can already resolve
 * names. Nothing here touches a Minecraft registry: registered {@code Item} instances live in
 * {@code AmmunitionRegistration}.</p>
 */
public final class AmmunitionRegistry {

    private static volatile List<AmmunitionLoadResult.LoadedAmmunition> entries = List.of();
    private static volatile Map<String, AmmunitionLoadResult.LoadedAmmunition> byName = Map.of();
    private static volatile Map<ResourceId, AmmunitionLoadResult.LoadedAmmunition> byItemId = Map.of();

    private AmmunitionRegistry() {
    }

    /**
     * Replaces the index with the definitions of one load.
     *
     * <p>Rebuilding is safe and idempotent: the order of {@link AmmunitionLoadResult#definitions()} is the
     * deterministic discovery order.</p>
     *
     * @param result the loaded ammunition definitions, may be {@code null}
     */
    public static void build(AmmunitionLoadResult result) {
        List<AmmunitionLoadResult.LoadedAmmunition> loaded =
                result == null ? List.of() : List.copyOf(result.definitions());
        Map<String, AmmunitionLoadResult.LoadedAmmunition> names = new LinkedHashMap<>();
        Map<ResourceId, AmmunitionLoadResult.LoadedAmmunition> itemIds = new LinkedHashMap<>();
        for (AmmunitionLoadResult.LoadedAmmunition entry : loaded) {
            names.putIfAbsent(entry.definition().ammoName(), entry);
            itemIds.putIfAbsent(entry.definition().ammoId(), entry);
        }
        entries = loaded;
        byName = Map.copyOf(names);
        byItemId = Map.copyOf(itemIds);
    }

    /** Empties the index; used by tests and by a fresh initialisation. */
    public static void clear() {
        build(null);
    }

    /** @return every accepted definition, in discovery order. */
    public static List<AmmunitionLoadResult.LoadedAmmunition> all() {
        return entries;
    }

    /**
     * @param ammoName a logical ammunition name, may be {@code null}
     * @return the definition that claims exactly that name, or {@code null}
     */
    public static AmmunitionLoadResult.LoadedAmmunition byName(String ammoName) {
        return ammoName == null ? null : byName.get(ammoName);
    }

    /**
     * @param ammoId a Minecraft item id, may be {@code null}
     * @return the definition registered under that id, or empty
     */
    public static Optional<AmmunitionLoadResult.LoadedAmmunition> byItemId(ResourceId ammoId) {
        return ammoId == null ? Optional.empty() : Optional.ofNullable(byItemId.get(ammoId));
    }

    /** @return the number of indexed definitions, for diagnostics. */
    public static int size() {
        return entries.size();
    }

    /** @return the indexed item ids, in discovery order. */
    public static List<ResourceId> itemIds() {
        List<ResourceId> ids = new ArrayList<>(entries.size());
        for (AmmunitionLoadResult.LoadedAmmunition entry : entries) {
            ids.add(entry.definition().ammoId());
        }
        return List.copyOf(ids);
    }

    /** @return every {@code ammo_name} to {@code ammo_id} pair, for diagnostics and tests. */
    public static Map<String, ResourceId> itemIdsByName() {
        Map<String, ResourceId> itemIds = new LinkedHashMap<>();
        for (Map.Entry<String, AmmunitionLoadResult.LoadedAmmunition> entry : byName.entrySet()) {
            itemIds.put(entry.getKey(), entry.getValue().definition().ammoId());
        }
        return Map.copyOf(itemIds);
    }

    /** @return the packs that provided at least one accepted definition, in discovery order. */
    public static List<ContentPack> packs() {
        Set<ContentPack> packs = new LinkedHashSet<>();
        for (AmmunitionLoadResult.LoadedAmmunition entry : entries) {
            packs.add(entry.pack());
        }
        return List.copyOf(packs);
    }

    /**
     * Resolves a weapon's existing {@code ammo_to_use} entries into Minecraft item ids.
     *
     * @param definition the weapon definition, may be {@code null}
     * @return the compatible item ids, in authored order, without duplicates; empty when nothing resolves
     */
    public static List<ResourceId> compatibleItemIds(WeaponDefinition definition) {
        if (definition == null || definition.ammoToUse().isEmpty()) {
            return List.of();
        }
        Set<ResourceId> itemIds = new LinkedHashSet<>();
        for (String authored : definition.ammoToUse()) {
            if (authored == null || authored.isBlank()) {
                continue;
            }
            AmmunitionLoadResult.LoadedAmmunition ammunition = byName.get(authored);
            if (ammunition != null) {
                itemIds.add(ammunition.definition().ammoId());
                continue;
            }
            ResourceId.Result direct = ResourceId.tryParse(authored);
            if (direct.isValid()) {
                itemIds.add(direct.id());
            }
        }
        return List.copyOf(itemIds);
    }

    /**
     * Classifies a weapon's entries against this index, for diagnostics.
     *
     * @param definition the weapon definition, may be {@code null}
     * @return the classified entries, in authored order
     */
    public static List<AmmunitionEntries> entriesOf(WeaponDefinition definition) {
        return AmmunitionEntries.of(definition, itemIdsByName());
    }
}
