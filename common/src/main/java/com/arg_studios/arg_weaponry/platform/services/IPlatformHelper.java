package com.arg_studios.arg_weaponry.platform.services;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/**
 * Loader specific information about the running environment.
 *
 * <p>Implemented by each loader module and looked up through {@link java.util.ServiceLoader}.</p>
 */
public interface IPlatformHelper {

    /**
     * Gets the name of the current platform.
     *
     * @return The name of the current platform.
     */
    String getPlatformName();

    /**
     * Checks if a mod with the given id is loaded.
     *
     * @param modId The mod to check if it is loaded.
     * @return True if the mod is loaded, false otherwise.
     */
    boolean isModLoaded(String modId);

    /**
     * Check if the game is currently in a development environment.
     *
     * @return True in a development environment, false otherwise.
     */
    boolean isDevelopmentEnvironment();

    /**
     * Creates a creative tab at the given position, using whichever builder the loader supports.
     *
     * <p>The tab <em>position</em> is the one genuinely loader-specific part of tab creation in this Minecraft version:
     * vanilla (and therefore Fabric) places a tab with {@code CreativeModeTab.builder(Row, column)}, while NeoForge
     * deprecates that overload in favour of its argument-less {@code builder()} and lays modded tabs out in pages itself.
     * Keeping the choice here means the shared registration code uses a supported API on both loaders instead of a
     * deprecated one on either.</p>
     *
     * @param title  the tab's display name
     * @param icon   the tab's icon
     * @param column the preferred column, in vanilla's tab grid; loaders that page their tabs may ignore it
     * @return the built tab, ready to be registered
     */
    CreativeModeTab createCreativeTab(Component title, Supplier<ItemStack> icon, int column);

    /**
     * Gets the name of the environment type as a string.
     *
     * @return The name of the environment type.
     */
    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
