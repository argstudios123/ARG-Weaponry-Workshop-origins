package com.arg_studios.arg_weaponry.pack;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Resolves resource identifiers to files inside a content pack.
 *
 * <p>Content packs are <em>not</em> mounted as vanilla resource packs, so every model, texture and
 * display document is read straight from the pack directory that discovery already found. The
 * namespace of a {@link ResourceId} must be one the pack provides; nothing falls back to vanilla
 * assets.</p>
 *
 * <p>Resolution never guesses: the caller supplies the directory prefix and the accepted suffixes,
 * which mirrors the conventions the reference validator already uses.</p>
 */
public final class ContentPackResources {

    /** Suffixes tried for JSON documents. */
    public static final List<String> JSON_SUFFIXES = List.of(".json");

    /** Suffixes tried for animation documents. */
    public static final List<String> ANIMATION_SUFFIXES = List.of(".json", ".animation.json");

    /** Suffixes tried for textures. */
    public static final List<String> TEXTURE_SUFFIXES = List.of(".png");

    /**
     * Suffixes tried for sound files.
     *
     * <p>Sound files are not resolved by the renderer yet; this list exists so reference validation and
     * resolution share one definition of which sound suffixes a content pack may use.</p>
     */
    public static final List<String> SOUND_SUFFIXES = List.of(".ogg");

    private ContentPackResources() {
    }

    /**
     * A resolved pack resource.
     *
     * @param id           the identifier that was resolved
     * @param relativePath the path below {@code assets/<namespace>}
     * @param file         the file on disk
     * @param size         the file size when it was resolved
     * @param lastModified the file modification time when it was resolved
     */
    public record Resolved(ResourceId id, String relativePath, Path file, long size, long lastModified) {

        /**
         * @return {@code true} when the file still has the size and modification time it had when it
         *         was resolved, which is how caches notice that content changed
         */
        public boolean isUnchanged() {
            try {
                return Files.size(file) == size && Files.getLastModifiedTime(file).toMillis() == lastModified;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /**
     * Resolves a resource.
     *
     * @param pack      the pack to resolve inside
     * @param id        the resource identifier
     * @param directory the directory below {@code assets/<namespace>}, for example {@code models}
     * @param suffixes  the suffixes to try, in order
     * @return the resolved resource, or empty when the pack does not provide it
     */
    public static Optional<Resolved> find(ContentPack pack, ResourceId id, String directory, List<String> suffixes) {
        if (id == null || !pack.providesNamespace(id.namespace())) {
            return Optional.empty();
        }

        for (String suffix : suffixes) {
            String relativePath = directory + "/" + id.path() + suffix;
            Path file = pack.root().resolve("assets").resolve(id.namespace()).resolve(relativePath);
            if (Files.isRegularFile(file)) {
                long size;
                long lastModified;
                try {
                    size = Files.size(file);
                    lastModified = Files.getLastModifiedTime(file).toMillis();
                } catch (IOException e) {
                    return Optional.empty();
                }
                return Optional.of(new Resolved(id, relativePath, file, size, lastModified));
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a resolved resource as UTF-8 text.
     *
     * @param resource the resolved resource
     * @return the text, or empty when it cannot be read
     */
    public static Optional<String> readText(Resolved resource) {
        try {
            return Optional.of(Files.readString(resource.file(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads a resolved resource as bytes.
     *
     * @param resource the resolved resource
     * @return the bytes, or empty when they cannot be read
     */
    public static Optional<byte[]> readBytes(Resolved resource) {
        try {
            return Optional.of(Files.readAllBytes(resource.file()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolves a texture resource, tolerating an identifier that already carries the file extension.
     *
     * <p>Textures are referenced as Minecraft identifiers ({@code arg_pack:textures/gui/hud/slots/x}), but creators also
     * write the path the file actually has ({@code arg_pack:weapons/special/muzzle_flash/flash.png}). Both spellings must
     * resolve to the same file, so the exact form is tried first and the form without a known extension second; nothing
     * else is guessed.</p>
     *
     * @param pack      the pack to resolve inside
     * @param id        the texture identifier
     * @param directory the directory below {@code assets/<namespace>}, for example {@code textures}
     * @return the resolved resource, or empty when the pack provides neither spelling
     */
    public static Optional<Resolved> findTexture(ContentPack pack, ResourceId id, String directory) {
        Optional<Resolved> direct = find(pack, id, directory, TEXTURE_SUFFIXES);
        if (direct.isPresent()) {
            return direct;
        }
        ResourceId withoutSuffix = stripTextureSuffix(id);
        return withoutSuffix == null ? Optional.empty() : find(pack, withoutSuffix, directory, TEXTURE_SUFFIXES);
    }

    /**
     * Removes a known texture extension from an identifier's path.
     *
     * @param id the identifier as written by the creator
     * @return the same identifier without a trailing texture extension, or {@code null} when it has none (or the
     *         remainder would not be a valid path)
     */
    public static ResourceId stripTextureSuffix(ResourceId id) {
        if (id == null) {
            return null;
        }
        for (String suffix : TEXTURE_SUFFIXES) {
            if (!id.path().endsWith(suffix)) {
                continue;
            }
            String trimmed = id.path().substring(0, id.path().length() - suffix.length());
            if (trimmed.isEmpty() || trimmed.endsWith("/")) {
                return null;
            }
            try {
                return new ResourceId(id.namespace(), trimmed);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /** @return the human readable candidate paths, for diagnostics. */
    public static String describeCandidates(ResourceId id, String directory, List<String> suffixes) {
        StringBuilder builder = new StringBuilder();
        for (String suffix : suffixes) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(id.namespace()).append(":").append(directory).append("/").append(id.path()).append(suffix);
        }
        return builder.toString();
    }
}
