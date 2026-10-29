package com.arg_studios.arg_weaponry.platform;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.network.INetworkService;
import com.arg_studios.arg_weaponry.platform.services.IGameDirectory;
import com.arg_studios.arg_weaponry.platform.services.IModRoots;
import com.arg_studios.arg_weaponry.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/**
 * Service loader backed access to loader specific implementations.
 *
 * <p>The common module cannot reference loader APIs directly, so each loader module provides an
 * implementation of these interfaces and registers it in
 * {@code META-INF/services/<interface name>}.</p>
 */
public final class Services {

    /** Loader specific environment information. */
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    /** Loader specific access to this mod's content roots. */
    public static final IModRoots MOD_ROOTS = load(IModRoots.class);

    /** Loader specific access to the game directory. */
    public static final IGameDirectory GAME_DIRECTORY = load(IGameDirectory.class);

    /**
     * Loader specific custom-payload networking.
     *
     * <p>Implementations must be loadable on either physical side: a dedicated server never sends a
     * serverbound request, but the very same service object is what the shared protocol talks to.</p>
     */
    public static final INetworkService NETWORK = load(INetworkService.class);

    private Services() {
    }

    private static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
