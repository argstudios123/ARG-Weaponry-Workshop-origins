package com.arg_studios.arg_weaponry.item;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponRegistrationPlan;
import com.arg_studios.arg_weaponry.weapon.state.WeaponComponents;
import com.arg_studios.arg_weaponry.weapon.state.WeaponState;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Applies a {@link WeaponRegistrationPlan} to the game registries.
 *
 * <p>This is the only place that writes items and creative tabs. It contains no loader-specific
 * code: the loaders decide <em>when</em> to call it (Fabric: during mod initialisation; NeoForge: from
 * the {@code RegisterEvent} for the item and creative-tab registries), which is always before the
 * registries freeze.</p>
 *
 * <p>Registration is idempotent and collision checked: an id that already exists in the registry is
 * reported and skipped, never overwritten.</p>
 */
public final class WeaponRegistration {

    private static final int TAB_COLUMNS = 7;

    private static final List<RegisteredWeapon> REGISTERED_WEAPONS = new ArrayList<>();
    private static final List<RegisteredTab> REGISTERED_TABS = new ArrayList<>();
    private static Set<Identifier> REGISTERED_WEAPON_IDS = Set.of();

    private static boolean itemsRegistered;
    private static boolean tabsRegistered;

    private WeaponRegistration() {
    }

    /**
     * @param plan   the planned weapon
     * @param itemId the Minecraft item id
     * @param item   the registered item
     */
    public record RegisteredWeapon(WeaponRegistrationPlan.PlannedWeapon plan, Identifier itemId, Item item) {
    }

    /**
     * @param pack  the owning content pack
     * @param tabId the creative tab id
     * @param tab   the registered tab
     */
    public record RegisteredTab(ContentPack pack, Identifier tabId, CreativeModeTab tab) {
    }

    /**
     * Registers the planned weapon items. Safe to call more than once; only the first call registers.
     *
     * @param plan   the registration plan
     * @param report the report to collect diagnostics in
     */
    public static void registerItems(WeaponRegistrationPlan plan, ValidationReport report) {
        if (itemsRegistered) {
            return;
        }
        itemsRegistered = true;

        for (WeaponRegistrationPlan.PlannedWeapon planned : plan.weapons()) {
            Identifier itemId = toIdentifier(planned.itemId());
            if (BuiltInRegistries.ITEM.containsKey(itemId)) {
                report.error(planned.pack().id(), planned.definition().fileIdentity().toString(),
                        "The Minecraft item id '" + itemId + "' is already registered; this weapon was skipped and "
                                + "nothing was overwritten");
                continue;
            }

            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, itemId);
            // Every weapon starts with the state its own definition describes. The value is only bound when
            // Minecraft binds item components, which happens after registry events, so this needs the
            // component type to be registered before that point rather than before item construction.
            WeaponState defaultState = WeaponItem.defaultStateFor(planned.definition());
            Item item = new WeaponItem(planned.pack(), planned.definition(),
                    new Item.Properties().setId(key).stacksTo(1)
                            .component(WeaponComponents.WEAPON_STATE, defaultState));
            try {
                Registry.register(BuiltInRegistries.ITEM, key, item);
            } catch (RuntimeException e) {
                report.error(planned.pack().id(), planned.definition().fileIdentity().toString(),
                        "Failed to register the Minecraft item '" + itemId + "': " + e.getMessage());
                continue;
            }
            REGISTERED_WEAPONS.add(new RegisteredWeapon(planned, itemId, item));
        }

        Constants.LOG.info("[ARG] Registered {} weapon item(s) out of {} planned",
                REGISTERED_WEAPONS.size(), plan.weapons().size());
    }

    // --- creative tabs ---

    /**
     * Registers one creative tab per discovered content pack. Safe to call more than once; only the
     * first call registers.
     *
     * @param plan   the registration plan
     * @param report the report to collect diagnostics in
     */
    public static void registerTabs(WeaponRegistrationPlan plan, ValidationReport report) {
        if (tabsRegistered) {
            return;
        }
        tabsRegistered = true;

        int column = 0;
        for (WeaponRegistrationPlan.PackTab tabPlan : plan.tabs()) {
            Identifier tabId = toIdentifier(tabPlan.tabId());
            if (BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(tabId)) {
                report.error(tabPlan.pack().id(), tabId.toString(),
                        "The creative tab id '" + tabId + "' is already registered; this tab was skipped");
                continue;
            }

            List<RegisteredWeapon> tabWeapons = registeredFor(tabPlan.pack().id());
            Supplier<ItemStack> icon = tabWeapons.isEmpty()
                    ? () -> new ItemStack(Items.GUNPOWDER)
                    : () -> new ItemStack(tabWeapons.getFirst().item());

            // Contents are added by the loader modules: vanilla 26.3 makes CreativeModeTab.Output
            // protected, so a content generator cannot be implemented from common code. NeoForge makes
            // it public and Fabric provides its own entry-modification API.
            CreativeModeTab tab = CreativeModeTab.builder(CreativeModeTab.Row.BOTTOM, column % TAB_COLUMNS)
                    .title(title(tabPlan.pack()))
                    .icon(icon)
                    .build();

            try {
                Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                        ResourceKey.create(Registries.CREATIVE_MODE_TAB, tabId), tab);
            } catch (RuntimeException e) {
                report.error(tabPlan.pack().id(), tabId.toString(),
                        "Failed to register the creative tab '" + tabId + "': " + e.getMessage());
                continue;
            }

            REGISTERED_TABS.add(new RegisteredTab(tabPlan.pack(), tabId, tab));
            column++;
            Constants.LOG.info("[ARG] Creative tab '{}' for pack '{}' registered; it will contain {} item(s)",
                    tabId, tabPlan.pack().id(), tabWeapons.size());

            if (tabWeapons.isEmpty()) {
                report.info(tabPlan.pack().id(), tabId.toString(), "The pack has no registrable weapons, so its "
                        + "creative tab stays empty and Minecraft hides it until it contains items");
            }
        }

        Constants.LOG.info("[ARG] Registered {} creative tab(s) for {} content pack(s)",
                REGISTERED_TABS.size(), plan.tabs().size());
    }

    /** @return the registered weapon items, in registration order. */
    public static List<RegisteredWeapon> registeredWeapons() {
        return List.copyOf(REGISTERED_WEAPONS);
    }

    /**
     * @return the item ids of the registered weapons, as an immutable set that is rebuilt only when the
     *         registration list changes; safe to call from hot paths such as item-model resolution
     */
    public static Set<Identifier> registeredWeaponIds() {
        Set<Identifier> cached = REGISTERED_WEAPON_IDS;
        if (cached.size() != REGISTERED_WEAPONS.size()) {
            Set<Identifier> ids = new java.util.LinkedHashSet<>(REGISTERED_WEAPONS.size());
            for (RegisteredWeapon weapon : REGISTERED_WEAPONS) {
                ids.add(weapon.itemId());
            }
            cached = Collections.unmodifiableSet(ids);
            REGISTERED_WEAPON_IDS = cached;
        }
        return cached;
    }

    /** @return the registered creative tabs, in registration order. */
    public static List<RegisteredTab> registeredTabs() {
        return List.copyOf(REGISTERED_TABS);
    }

    /**
     * The items that belong in each registered tab, keyed by tab id.
     *
     * <p>Vanilla 26.3 does not expose the creative-tab content builder to code outside its own
     * package, so the loader modules add these entries through their platform APIs
     * ({@code CreativeModeTabEvents} on Fabric, {@code BuildCreativeModeTabContentsEvent} on
     * NeoForge).</p>
     *
     * <p>Items are returned, not stacks, on purpose: an {@link ItemStack} needs the item's component
     * map to be bound, which happens during the data-pack load and is therefore <em>later</em> than
     * mod initialisation. Building stacks here made the game crash with "Components not bound yet" as
     * soon as a weapon existed, so each loader creates its stacks inside its own content callback,
     * which runs after binding.</p>
     *
     * @return an immutable map of tab id to its items, in deterministic order
     */
    public static Map<Identifier, List<Item>> tabItems() {
        Map<Identifier, List<Item>> contents = new LinkedHashMap<>();
        for (RegisteredTab tab : REGISTERED_TABS) {
            List<Item> items = registeredFor(tab.pack().id()).stream()
                    .map(RegisteredWeapon::item)
                    .toList();
            contents.put(tab.tabId(), items);
        }
        return Collections.unmodifiableMap(contents);
    }

    private static List<RegisteredWeapon> registeredFor(String packId) {
        return REGISTERED_WEAPONS.stream()
                .filter(weapon -> weapon.plan().pack().id().equals(packId))
                .sorted(Comparator.comparing(weapon -> weapon.itemId().toString()))
                .toList();
    }

    private static Identifier toIdentifier(ResourceId id) {
        return Identifier.fromNamespaceAndPath(id.namespace(), id.path());
    }

    /**
     * Uses the pack's {@code pack_name} as the tab title when it reads like a display name, otherwise
     * the pack id. Content-pack language loading does not exist yet, so a translation key would be
     * shown verbatim and is avoided here.
     */
    private static Component title(ContentPack pack) {
        String name = pack.metadata().nameKey();
        if (name != null && !name.isBlank() && !looksLikeTranslationKey(name)) {
            return Component.literal(name);
        }
        return Component.literal(pack.id());
    }

    private static boolean looksLikeTranslationKey(String value) {
        return value.indexOf('.') >= 0 && value.indexOf(' ') < 0;
    }
}
