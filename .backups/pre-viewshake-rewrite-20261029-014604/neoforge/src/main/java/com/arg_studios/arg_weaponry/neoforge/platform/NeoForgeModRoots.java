package com.arg_studios.arg_weaponry.neoforge.platform;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.platform.services.IModRoots;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * NeoForge implementation of {@link IModRoots}.
 *
 * <p>In NeoForge 26.3 the mod file exposes its file tree through {@code IModFile#getContents()};
 * {@code JarContents#getContentRoots()} is the direct equivalent of Fabric's mod root paths and
 * covers both archives and development output directories.</p>
 */
public class NeoForgeModRoots implements IModRoots {

    @Override
    public List<Path> getContentRootPaths() {
        IModFileInfo fileInfo = ModList.get().getModFileById(Constants.MOD_ID);
        if (fileInfo == null) {
            Constants.LOG.warn("[ARG] NeoForge does not know a mod file for '{}'; content packs cannot be located",
                    Constants.MOD_ID);
            return List.of();
        }
        return List.copyOf(fileInfo.getFile().getContents().getContentRoots());
    }
}
