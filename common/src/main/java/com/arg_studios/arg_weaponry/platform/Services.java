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
 *
 * <h2>Why the provider is filtered by loader</h2>
 * <p>The standalone Fabric and NeoForge jars each contain exactly one provider, so plain
 * {@link ServiceLoader} semantics are enough. The universal jar contains both implementations and one merged service
 * file per interface, and instantiating the provider of the loader that is not running would immediately fail on its
 * missing loader classes (or, worse, silently answer for the wrong platform). The provider whose package belongs to the
 * loader that is actually present is therefore selected first; a normal single-loader jar is unaffected because its only
 * provider matches.</p>
 */
public final class Services {

    /** A class that only exists when NeoForge is the running loader. */
    private static final String NEOFORGE_MARKER = "net.neoforged.fml.loading.FMLLoader";

    /** A class that only exists when Fabric Loader is the running loader. */
    private static final String FABRIC_MARKER = "net.fabricmc.loader.api.FabricLoader";

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
        ClassLoader classLoader = Services.class.getClassLoader();
        String preferred = preferredPackage(classLoader);
        java.util.List<ServiceLoader.Provider<T>> providers =
                ServiceLoader.load(clazz, classLoader).stream().toList();
        // Match on the declared provider type first: instantiating the other loader's provider is not only unnecessary,
        // it would initialise a class whose loader API is absent from this process.
        for (ServiceLoader.Provider<T> provider : providers) {
            if (preferred != null && !provider.type().getName().contains(preferred)) {
                continue;
            }
            T loaded = provider.get();
            Constants.LOG.debug("Loaded {} for service {} (provider {}, loader {})", loaded, clazz,
                    provider.type().getName(), preferred == null ? "unknown" : preferred);
            return loaded;
        }
        // Neither loader marker matched (an unusual launcher or a test harness): fall back to the normal order.
        if (!providers.isEmpty()) {
            T loaded = providers.getFirst().get();
            Constants.LOG.debug("Loaded {} for service {} (no loader-specific provider matched)", loaded, clazz);
            return loaded;
        }
        throw new NullPointerException("Failed to load service for " + clazz.getName());
    }

    /**
     * @param classLoader the class loader that loaded this class
     * @return the provider package fragment of the running loader, or {@code null} when neither loader is present
     */
    private static String preferredPackage(ClassLoader classLoader) {
        if (present(NEOFORGE_MARKER, classLoader)) {
            return ".neoforge.";
        }
        if (present(FABRIC_MARKER, classLoader)) {
            return ".fabric.";
        }
        return null;
    }

    private static boolean present(String className, ClassLoader classLoader) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
