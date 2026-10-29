package com.arg_studios.arg_weaponry.fabric.platform;

import com.arg_studios.arg_weaponry.platform.services.IGameDirectory;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Fabric implementation of {@link IGameDirectory}.
 */
public class FabricGameDirectory implements IGameDirectory {

    @Override
    public Path getGameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }
}
