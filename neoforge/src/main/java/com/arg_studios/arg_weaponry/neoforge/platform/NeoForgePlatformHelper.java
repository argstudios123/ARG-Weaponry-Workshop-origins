package com.arg_studios.arg_weaponry.neoforge.platform;

import com.arg_studios.arg_weaponry.platform.services.IPlatformHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

import java.util.function.Supplier;

/**
 * NeoForge implementation of {@link IPlatformHelper}.
 */
public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.getCurrent().isProduction();
    }

    /**
     * Builds the tab with NeoForge's argument-less builder.
     *
     * <p>NeoForge deprecates {@code CreativeModeTab.builder(Row, int)} and positions modded tabs through its own page
     * layout, so the requested column is ignored here instead of being applied through a deprecated overload.</p>
     */
    @Override
    public CreativeModeTab createCreativeTab(Component title, Supplier<ItemStack> icon, int column) {
        return CreativeModeTab.builder()
                .title(title)
                .icon(icon)
                .build();
    }
}
