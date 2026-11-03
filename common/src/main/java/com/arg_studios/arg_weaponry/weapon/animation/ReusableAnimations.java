package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves {@code reusable_default_animations} and merges their clips into a weapon's animation document.
 *
 * <h2>What a reusable library is</h2>
 * <p>A library is an ordinary Bedrock animation document below
 * {@code assets/<namespace>/default_animations/}. The weapon lists pack-relative paths in
 * {@code reusable_default_animations}; the framework resolves each path in the weapon's own namespace first (then in
 * every other namespace the pack provides), loads it through {@link WeaponAnimationCache#loadLibrary(ContentPack,
 * ResourceId)} and merges its animations into the weapon's own document.</p>
 *
 * <h2>Precedence</h2>
 * <p>The weapon's own animation document always wins. A reusable library only fills names the weapon does not declare,
 * and the first declared library wins over a later one. That is the same precedence the reference implementation gets
 * from GeckoLib's primary-resource-then-fallbacks chain: a pack can override one shared clip without forking the whole
 * library, and a duplicate can never make playback ambiguous. Duplicate declarations of the same library are resolved
 * once.</p>
 *
 * <h2>Failure policy</h2>
 * <p>A missing, invalid or unreadable library is reported once and skipped; the weapon keeps all of its own clips. It is
 * never an error that rejects the weapon, because reusable animations are optional presentation data. A library path with
 * no namespace is a pack-relative path, which is the schema this project ships; a {@code namespace:path} form is accepted
 * as a forward-compatible convenience.</p>
 */
public final class ReusableAnimations {

    /** Prefix the schema uses for library paths, removed before resolution below the library directory. */
    public static final String LIBRARY_PREFIX = "default_animations/";

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private ReusableAnimations() {
    }

    /**
     * Resolves the library identifiers a weapon declares.
     *
     * @param pack       the owning content pack
     * @param definition the weapon definition
     * @param primary    the weapon's own animation resource; its namespace is preferred for bare paths
     * @return the resolved identifiers, in declaration order, never {@code null}
     */
    public static List<ResourceId> resolveLibraries(ContentPack pack, WeaponDefinition definition, ResourceId primary) {
        if (pack == null || definition == null) {
            return List.of();
        }
        List<String> declared = definition.reusableDefaultAnimations();
        if (declared.isEmpty()) {
            return List.of();
        }

        Set<ResourceId> resolved = new LinkedHashSet<>();
        for (int index = 0; index < declared.size(); index++) {
            String raw = declared.get(index);
            ParsedEntry entry = parse(raw);
            if (entry == null) {
                warnOnce("invalid|" + pack.id() + "|" + definition.name() + "|" + raw,
                        "Ignoring reusable animation library '" + raw + "' of weapon '" + definition.name()
                                + "': it is not a valid pack-relative animation path");
                continue;
            }

            ResourceId found = find(pack, entry, primary);
            if (found == null) {
                List<String> tried = namespaces(pack, entry, primary);
                String candidates = tried.isEmpty() ? "(the pack provides no namespace)"
                        : ContentPackResources.describeCandidates(new ResourceId(tried.getFirst(), entry.path()),
                        WeaponAnimationCache.LIBRARY_DIRECTORY, ContentPackResources.ANIMATION_SUFFIXES);
                warnOnce("missing|" + pack.id() + "|" + definition.name() + "|" + entry.normalised(),
                        "The reusable animation library '" + entry.normalised() + "' of weapon '" + definition.name()
                                + "' was not found in namespace(s) " + tried + "; tried " + candidates);
                continue;
            }
            if (!resolved.add(found)) {
                warnOnce("duplicate|" + pack.id() + "|" + found,
                        "Reusable animation library '" + found + "' is declared more than once; the first declaration is "
                                + "used");
                continue;
            }
            Constants.LOG.debug("[ARG] Weapon '{}' resolved reusable animation library '{}' to {}", definition.name(),
                    raw, found);
        }
        return List.copyOf(resolved);
    }

    /**
     * Loads and merges a weapon's animation document with its reusable libraries.
     *
     * @param pack       the owning content pack
     * @param definition the weapon definition
     * @param primary    the weapon's own animation resource, may be {@code null}
     * @return the merged document, or empty when neither the weapon nor any library could be loaded
     */
    public static Optional<AnimationFile> resolve(ContentPack pack, WeaponDefinition definition, ResourceId primary) {
        AnimationFile own = primary == null ? null
                : WeaponAnimationCache.instance().load(pack, primary).orElse(null);
        List<AnimationFile> libraries = new ArrayList<>();
        for (ResourceId library : resolveLibraries(pack, definition, primary)) {
            WeaponAnimationCache.instance().loadLibrary(pack, library).ifPresent(libraries::add);
        }
        if (own == null && libraries.isEmpty()) {
            return Optional.empty();
        }

        AnimationFile merged = merge(own, libraries);
        String weaponName = definition == null ? "<unknown>" : definition.name();
        String key = "resolved|" + pack.id() + "|" + weaponName + "|" + merged.id().asString();
        if (REPORTED.add(key)) {
            int ownClips = own == null ? 0 : own.animationCount();
            Constants.LOG.info("[ARG] Weapon '{}' animation set: {} clip(s) ({} own + {} reusable) from '{}' ({})",
                    weaponName, merged.animationCount(), ownClips, merged.animationCount() - ownClips,
                    merged.id().asString(),
                    libraries.isEmpty() ? "no reusable library" : String.join(", ",
                            libraries.stream().map(file -> file.id().asString()).toList()));
        }
        return Optional.of(merged);
    }

    /**
     * Merges a weapon's own document with reusable libraries.
     *
     * @param primary   the weapon's own document, may be {@code null}
     * @param libraries the libraries in declaration order
     * @return the merged document, or {@code null} when there is nothing to merge
     */
    public static AnimationFile merge(AnimationFile primary, List<AnimationFile> libraries) {
        if (primary == null && (libraries == null || libraries.isEmpty())) {
            return null;
        }
        if (libraries == null || libraries.isEmpty()) {
            return primary;
        }

        AnimationFile base = primary != null ? primary : libraries.getFirst();
        Map<String, BedrockAnimation> merged = new LinkedHashMap<>(base.animations());
        int added = 0;
        int conflicts = 0;
        for (AnimationFile library : libraries) {
            for (Map.Entry<String, BedrockAnimation> entry : library.animations().entrySet()) {
                if (merged.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                    added++;
                } else {
                    conflicts++;
                }
            }
        }
        if (added == 0 && primary != null) {
            return primary;
        }
        if (conflicts > 0) {
            Constants.LOG.debug("[ARG] {} reusable animation name(s) were overridden by a higher-precedence resource",
                    conflicts);
        }
        return new AnimationFile(base.id(), base.formatVersion(), merged);
    }

    /**
     * Resolves one animation from a weapon's merged animation set.
     *
     * @param pack       the owning content pack
     * @param definition the weapon definition
     * @param primary    the weapon's own animation resource
     * @param name       the animation name
     * @return the animation, or empty when no source declares it
     */
    public static Optional<BedrockAnimation> animation(ContentPack pack, WeaponDefinition definition,
                                                       ResourceId primary, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return resolve(pack, definition, primary).map(file -> file.animation(name))
                .filter(animation -> animation != null);
    }

    /** Allows diagnostics to be reported again after a resource reload. */
    public static synchronized void resetDiagnostics() {
        REPORTED.clear();
    }

    // ---------------------------------------------------------------- path resolution

    /**
     * @param raw the declared value
     * @return the parsed path, or {@code null} when it is not a valid library path
     */
    private static ParsedEntry parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        int colon = value.indexOf(':');
        String namespace = null;
        String path = value;
        if (colon >= 0) {
            if (value.indexOf(':', colon + 1) >= 0) {
                return null;
            }
            namespace = value.substring(0, colon).trim();
            path = value.substring(colon + 1).trim();
            if (namespace.isEmpty()) {
                return null;
            }
        }
        path = path.replace('\\', '/');
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.startsWith(LIBRARY_PREFIX)) {
            path = path.substring(LIBRARY_PREFIX.length());
        }
        if (path.endsWith(".animation.json")) {
            path = path.substring(0, path.length() - ".animation.json".length());
        } else if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - ".json".length());
        }
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/") || path.contains("//")
                || path.contains("..")) {
            return null;
        }
        return new ParsedEntry(namespace, path);
    }

    /**
     * @return the namespaces to try, in priority order; the primary document's namespace comes first for bare paths
     */
    private static List<String> namespaces(ContentPack pack, ParsedEntry entry, ResourceId primary) {
        if (entry.namespace() != null) {
            return List.of(entry.namespace());
        }
        LinkedHashSet<String> namespaces = new LinkedHashSet<>();
        if (primary != null && pack.providesNamespace(primary.namespace())) {
            namespaces.add(primary.namespace());
        }
        namespaces.addAll(pack.resourceNamespaces());
        return List.copyOf(namespaces);
    }

    private static ResourceId find(ContentPack pack, ParsedEntry entry, ResourceId primary) {
        for (String namespace : namespaces(pack, entry, primary)) {
            if (!pack.providesNamespace(namespace)) {
                continue;
            }
            ResourceId candidate = new ResourceId(namespace, entry.path());
            if (ContentPackResources.find(pack, candidate, WeaponAnimationCache.LIBRARY_DIRECTORY,
                    ContentPackResources.ANIMATION_SUFFIXES).isPresent()) {
                return candidate;
            }
        }
        return null;
    }

    private static synchronized void warnOnce(String key, String message) {
        if (REPORTED.add("warn|" + key)) {
            Constants.LOG.warn("[ARG] {}", message);
        }
    }

    /**
     * @param namespace the optional explicit namespace, or {@code null} for a pack-relative path
     * @param path      the path below {@code default_animations}, without a suffix
     */
    private record ParsedEntry(String namespace, String path) {
        private String normalised() {
            return namespace == null ? path : namespace + ":" + path;
        }
    }
}
