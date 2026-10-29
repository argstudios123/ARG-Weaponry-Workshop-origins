package com.arg_studios.arg_weaponry.pack;

import java.nio.file.Path;
import java.util.List;

/**
 * A discovered and successfully parsed content pack.
 *
 * <p>Two identities are kept apart on purpose:</p>
 * <ul>
 *     <li>{@link #id()} is the <em>pack identity</em>. It comes from {@code pack_id.json}'s
 *     {@code pack_id} field when present and falls back to the pack folder name.</li>
 *     <li>{@link #resourceNamespaces()} are the <em>resource namespaces</em> the pack provides,
 *     discovered from its {@code assets/<namespace>} directories. A pack may provide several, and
 *     a resource identifier such as {@code arg_pack:weapons/dmrs/m100_dmr} is always resolved
 *     through the owning pack's namespace.</li>
 * </ul>
 *
 * @param id                the pack identity
 * @param source            where the pack was discovered (installed or bundled)
 * @param metadata          the metadata read from {@code pack.mcmeta} and {@code pack_id.json}
 * @param root              the pack directory
 * @param resourceNamespaces the resource namespaces provided by this pack, sorted and immutable
 */
public record ContentPack(String id, ContentPackSource source, PackMetadata metadata, Path root,
                          List<String> resourceNamespaces) {

    public ContentPack {
        resourceNamespaces = List.copyOf(resourceNamespaces);
    }

    /** @param namespace a resource namespace, for example {@code arg_pack} */
    public boolean providesNamespace(String namespace) {
        return resourceNamespaces.contains(namespace);
    }
}
