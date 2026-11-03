package com.arg_studios.arg_weaponry.fabric;

import com.arg_studios.arg_weaponry.ArgWeaponry;
import com.arg_studios.arg_weaponry.fabric.network.FabricWeaponNetworking;
import net.fabricmc.api.ModInitializer;


public class ArgWeaponryFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        ArgWeaponry.init();
        ArgWeaponry.registerComponents();
        ArgWeaponry.registerItems();
        ArgWeaponry.registerTabs();
        FabricCreativeTabContents.register();
        FabricWeaponNetworking.registerCommon();
    }
}
