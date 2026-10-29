package com.arg_studios.arg_weaponry.platform.services;

import java.nio.file.Path;
import java.util.List;

/**
 * Provides the content roots of this mod, so bundled data can be read the same way in a development
 * environment (directories on the classpath) and in a packaged environment (an archive).
 *
 * <p>Each returned path is the root of a file tree that contains the mod's resources, which means
 * {@code <root>/assets/arg_weaponry/weapon_packs} is the bundled content pack directory.</p>
 */
public interface IModRoots {

    /**
     * @return the content roots of this mod, in loader defined order; empty if they cannot be resolved.
     */
    List<Path> getContentRootPaths();
}
