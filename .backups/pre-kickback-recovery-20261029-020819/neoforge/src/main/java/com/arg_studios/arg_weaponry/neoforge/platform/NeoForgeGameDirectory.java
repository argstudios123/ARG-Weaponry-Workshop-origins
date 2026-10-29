package com.arg_studios.arg_weaponry.neoforge.platform;

import com.arg_studios.arg_weaponry.platform.services.IGameDirectory;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge implementation of {@link IGameDirectory}.
 */
public class NeoForgeGameDirectory implements IGameDirectory {

    @Override
    public Path getGameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }
}
