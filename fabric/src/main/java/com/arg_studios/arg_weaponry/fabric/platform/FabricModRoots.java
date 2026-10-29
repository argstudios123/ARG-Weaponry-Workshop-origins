package com.arg_studios.arg_weaponry.fabric.platform;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.platform.services.IModRoots;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;
import java.util.List;

/**
 * Fabric implementation of {@link IModRoots}.
 *
 * <p>Fabric reports a {@link Path} per content root, both for a mod loaded from an archive and for a
 * mod loaded from development output directories, so no special casing is required here.</p>
 */
public class FabricModRoots implements IModRoots {

    @Override
    public List<Path> getContentRootPaths() {
        return FabricLoader.getInstance().getModContainer(Constants.MOD_ID)
                .map(ModContainer::getRootPaths)
                .map(List::copyOf)
                .orElseGet(List::of);
    }
}
