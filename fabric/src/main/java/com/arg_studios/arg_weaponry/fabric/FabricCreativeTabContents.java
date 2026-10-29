package com.arg_studios.arg_weaponry.fabric;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.item.CreativeTabContents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Populates ARG creative tabs on Fabric.
 *
 * <p>Vanilla 26.3 keeps {@code CreativeModeTab.Output} protected, so tab contents cannot be supplied
 * from the common module. Fabric's {@code fabric-creative-tab-api-v1} exposes
 * {@link CreativeModeTabEvents} for exactly this purpose.</p>
 */
public final class FabricCreativeTabContents {

    private static final Set<Identifier> LOGGED = ConcurrentHashMap.newKeySet();

    private FabricCreativeTabContents() {
    }

    /** Registers one output modification listener per ARG creative tab. */
    public static void register() {
        for (Map.Entry<Identifier, List<Item>> entry : CreativeTabContents.items().entrySet()) {
            Identifier tabId = entry.getKey();
            List<Item> items = entry.getValue();
            ResourceKey<CreativeModeTab> tabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, tabId);

            CreativeModeTabEvents.modifyOutputEvent(tabKey).register(output -> {
                // prepend inserts at the front, so walk backwards to preserve the planned order.
                // Stacks are created here rather than during initialisation: an ItemStack needs the
                // bound item components, which do not exist yet at mod-init time.
                for (int index = items.size() - 1; index >= 0; index--) {
                    output.prepend(new ItemStack(items.get(index)));
                }
                if (LOGGED.add(tabId)) {
                    Constants.LOG.info("[ARG] Creative tab '{}' was populated with {} item(s)", tabId, items.size());
                }
            });
        }
    }
}
