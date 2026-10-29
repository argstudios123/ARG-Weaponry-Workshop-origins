package com.arg_studios.arg_weaponry.neoforge;

import com.arg_studios.arg_weaponry.ArgWeaponry;
import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.item.CreativeTabContents;
import com.arg_studios.arg_weaponry.neoforge.network.NeoForgeWeaponNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.List;

/**
 * NeoForge entry point.
 *
 * <p>Only forwards to the shared bootstrap so both loaders run exactly the same framework code.</p>
 */
@Mod(Constants.MOD_ID)
public class ArgWeaponryNeoForge {

    public ArgWeaponryNeoForge(IEventBus eventBus) {
        ArgWeaponry.init();
        eventBus.addListener(ArgWeaponryNeoForge::onRegister);
        eventBus.addListener(ArgWeaponryNeoForge::onBuildTabContents);

        // Payload registration, the configuration-phase handshake task and the connection lifecycle cleanup.
        NeoForgeWeaponNetworking.register(eventBus);

        // Client-only setup is isolated in its own class so a dedicated server never loads it.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            NeoForgeClientSetup.register(eventBus);
        }
    }

    /**
     * Registers the weapon data component, the weapon items and the creative tabs from NeoForge's registry
     * events, which is the documented point at which a built-in registry is ready to accept modded objects.
     *
     * <p>NeoForge fires {@code minecraft:data_component_type} before {@code minecraft:item} (its registration
     * order places data component types second, before the vanilla registry order), so the component exists
     * before any weapon item declares it as its default.</p>
     */
    private static void onRegister(RegisterEvent event) {
        if (Registries.DATA_COMPONENT_TYPE.equals(event.getRegistryKey())) {
            ArgWeaponry.registerComponents();
        } else if (Registries.ITEM.equals(event.getRegistryKey())) {
            ArgWeaponry.registerItems();
        } else if (Registries.CREATIVE_MODE_TAB.equals(event.getRegistryKey())) {
            ArgWeaponry.registerTabs();
        }
    }

    /**
     * Adds the planned weapon items to ARG's creative tabs. NeoForge fires this for every tab whose
     * contents are built, which is where mods add entries in Minecraft 26.3.
     */
    private static void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
        List<Item> items = CreativeTabContents.items().get(event.getTabKey().identifier());
        if (items == null) {
            return;
        }
        // Stacks are created here, when the tab contents are built, because an ItemStack needs the
        // item components to be bound, which is only true after the data-pack load.
        for (Item item : items) {
            event.accept(new ItemStack(item));
        }
    }
}
