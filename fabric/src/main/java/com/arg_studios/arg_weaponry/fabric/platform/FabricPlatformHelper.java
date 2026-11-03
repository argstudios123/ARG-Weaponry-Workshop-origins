package com.arg_studios.arg_weaponry.fabric.platform;

import com.arg_studios.arg_weaponry.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/**
 * Fabric implementation of {@link IPlatformHelper}.
 */
public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    /**
     * Builds the tab with vanilla's positioned builder, which is the supported overload on Fabric: NeoForge's
     * deprecation of it is its own page-layout change, not a vanilla one.
     */
    @Override
    public CreativeModeTab createCreativeTab(Component title, Supplier<ItemStack> icon, int column) {
        return CreativeModeTab.builder(CreativeModeTab.Row.BOTTOM, column)
                .title(title)
                .icon(icon)
                .build();
    }
}
