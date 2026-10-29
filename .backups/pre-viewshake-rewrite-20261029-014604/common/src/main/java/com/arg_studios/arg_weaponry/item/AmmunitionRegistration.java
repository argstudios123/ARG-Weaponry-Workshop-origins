package com.arg_studios.arg_weaponry.item;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionDefinition;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionLoadResult;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies the loaded ammunition definitions to Minecraft's item registry.
 *
 * <p>This is the ammunition counterpart of {@link WeaponRegistration} and deliberately mirrors it: common
 * code performs the registry write, each loader decides <em>when</em> (Fabric during mod initialisation,
 * NeoForge from the {@code RegisterEvent} for the item registry), and registration is idempotent and
 * collision checked.</p>
 *
 * <h2>Registry identity is {@code ammo_id}</h2>
 * <p>The item is registered under the definition's {@code ammo_id}, verbatim. That is a different identity
 * from the logical {@code ammo_name} a weapon references, and different again from the weapon's own item id;
 * none of the three is derived from another.</p>
 *
 * <h2>Why this cannot happen later</h2>
 * <p>Minecraft freezes its item registry after initialisation, so an ammunition item can only be registered
 * where the project already registers weapons. Content packs are therefore discovered and installed during
 * initialisation, which is what makes pack-supplied ammunition registrable at all. A pack that appears while
 * the game is running cannot have its items registered by this milestone; that limit is reported by the pack
 * scanner and is not worked around by touching a frozen registry.</p>
 *
 * <h2>Collisions</h2>
 * <p>An id that is already taken — by another mod, by vanilla, or by a weapon registered earlier — is reported
 * as an error and skipped. Nothing is overwritten and no substitute item is invented.</p>
 */
public final class AmmunitionRegistration {

    private static final List<RegisteredAmmunition> REGISTERED_AMMUNITION = new ArrayList<>();

    private static boolean itemsRegistered;

    private AmmunitionRegistration() {
    }

    /**
     * @param pack       the owning content pack
     * @param definition the parsed definition
     * @param itemId     the Minecraft item id, taken verbatim from {@code ammo_id}
     * @param item       the registered item
     */
    public record RegisteredAmmunition(ContentPack pack, AmmunitionDefinition definition, Identifier itemId,
                                       Item item) {
    }

    /**
     * Registers one item per loaded ammunition definition. Safe to call more than once; only the first call
     * registers.
     *
     * @param report the report to collect diagnostics in
     */
    public static void registerItems(ValidationReport report) {
        if (itemsRegistered) {
            return;
        }
        itemsRegistered = true;

        for (AmmunitionLoadResult.LoadedAmmunition loaded : AmmunitionRegistry.all()) {
            AmmunitionDefinition definition = loaded.definition();
            ContentPack pack = loaded.pack();
            String source = definition.fileIdentity() == null ? "ammunition" : definition.fileIdentity().toString();
            Identifier itemId = toIdentifier(definition.ammoId());

            if (!pack.providesNamespace(itemId.getNamespace())) {
                report.error(pack.id(), source, "The ammunition registry id '" + definition.ammoId()
                        + "' uses namespace '" + itemId.getNamespace() + "', which this content pack does not "
                        + "provide. A pack may only register items in its own namespaces, so this ammunition was "
                        + "not registered");
                continue;
            }
            if (BuiltInRegistries.ITEM.containsKey(itemId)) {
                report.error(pack.id(), source, "The Minecraft item id '" + itemId + "' is already registered; "
                        + "this ammunition was skipped and nothing was overwritten");
                continue;
            }

            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, itemId);
            Item item;
            try {
                item = new AmmunitionItem(pack, definition,
                        new Item.Properties().setId(key).stacksTo(AmmunitionItem.DEFAULT_STACK_SIZE));
                Registry.register(BuiltInRegistries.ITEM, key, item);
            } catch (RuntimeException e) {
                // Defensive: an item registry that refuses the write (frozen, or an id registered behind our
                // back) must produce a diagnostic instead of escaping as an exception during initialisation.
                report.error(pack.id(), source, "Failed to register the ammunition item '" + itemId + "': "
                        + e.getMessage());
                continue;
            }
            REGISTERED_AMMUNITION.add(new RegisteredAmmunition(pack, definition, itemId, item));
        }

        Constants.LOG.info("[ARG] Registered {} ammunition item(s) for {} definition(s)",
                REGISTERED_AMMUNITION.size(), AmmunitionRegistry.size());
    }

    /** @return the registered ammunition items, in registration order. */
    public static List<RegisteredAmmunition> registeredAmmunition() {
        return List.copyOf(REGISTERED_AMMUNITION);
    }

    /**
     * @param itemId an item id, may be {@code null}
     * @return the registered ammunition for that id, or {@code null}
     */
    public static RegisteredAmmunition byItemId(Identifier itemId) {
        if (itemId == null) {
            return null;
        }
        for (RegisteredAmmunition ammunition : REGISTERED_AMMUNITION) {
            if (ammunition.itemId().equals(itemId)) {
                return ammunition;
            }
        }
        return null;
    }

    /**
     * @param packId a content pack id
     * @return the registered ammunition items of that pack, sorted by item id for deterministic tab order
     */
    public static List<Item> itemsOf(String packId) {
        return REGISTERED_AMMUNITION.stream()
                .filter(ammunition -> ammunition.pack().id().equals(packId))
                .sorted(Comparator.comparing(ammunition -> ammunition.itemId().toString()))
                .map(RegisteredAmmunition::item)
                .toList();
    }

    private static Identifier toIdentifier(ResourceId id) {
        return Identifier.fromNamespaceAndPath(id.namespace(), id.path());
    }
}
