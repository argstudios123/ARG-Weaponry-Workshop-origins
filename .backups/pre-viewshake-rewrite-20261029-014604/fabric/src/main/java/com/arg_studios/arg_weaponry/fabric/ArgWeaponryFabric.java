package com.arg_studios.arg_weaponry.fabric;

import com.arg_studios.arg_weaponry.ArgWeaponry;
import com.arg_studios.arg_weaponry.fabric.network.FabricWeaponNetworking;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry point.
 *
 * <p>Only forwards to the shared bootstrap so both loaders run exactly the same framework code.</p>
 */
public class ArgWeaponryFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        ArgWeaponry.init();

        // Components first: every weapon item declares the weapon-state default, so the component type must
        // exist before the items that reference it are registered.
        ArgWeaponry.registerComponents();

        // Fabric has no registry event and initialisers run before the built-in registries freeze,
        // so items and creative tabs are registered immediately after discovery.
        ArgWeaponry.registerItems();
        ArgWeaponry.registerTabs();
        FabricCreativeTabContents.register();

        // After item registration, because the manifest is frozen from the registered weapons. The clientbound
        // handler is registered by the client entry point; everything else is shared.
        FabricWeaponNetworking.registerCommon();
    }
}
