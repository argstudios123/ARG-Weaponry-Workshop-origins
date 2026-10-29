package com.arg_studios.arg_weaponry.platform.services;

import java.nio.file.Path;

/**
 * Provides the game directory, so the framework can install and discover content packs in a stable
 * location on the client and on a dedicated server.
 *
 * <p>On the client this is usually {@code .minecraft}; on a dedicated server it is the server's
 * working directory.</p>
 */
public interface IGameDirectory {

    /**
     * @return the game directory, never {@code null}
     */
    Path getGameDirectory();
}
