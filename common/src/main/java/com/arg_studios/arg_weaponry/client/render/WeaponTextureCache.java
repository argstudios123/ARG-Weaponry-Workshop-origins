package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Uploads content-pack textures and keeps the resulting GPU textures.
 *
 * <p>This is the only place where pack pixels become GPU resources, and it keeps that separate from
 * the parsed CPU-side model data. Textures are registered under a stable identifier derived from the
 * pack and the resource, so re-registering after a reload replaces and closes the previous texture
 * instead of leaking it.</p>
 *
 * <p>A resource that is absent is remembered as well, so a missing texture costs one lookup and one
 * warning instead of a filesystem lookup and a rebuilt message on every rendered frame. Remembered
 * misses are dropped by {@link #clear()}, which is what the resource-reload hook calls, so a texture
 * that appears later is picked up after a reload.</p>
 *
 * <p>Uploads only happen on the render thread, because {@link DynamicTexture} creates a GL texture in
 * its constructor.</p>
 */
public final class WeaponTextureCache {

    /** Directory below a namespace that holds textures. */
    public static final String TEXTURE_DIRECTORY = "textures";

    private static final WeaponTextureCache INSTANCE = new WeaponTextureCache();

    /** Entry stored for a resource that was looked up and does not exist; dropped by {@link #clear()}. */
    private static final Entry ABSENT = new Entry(null, null, 0, 0);

    private final Map<String, Entry> entries = new HashMap<>();

    private WeaponTextureCache() {
    }

    /** @return the shared cache instance. */
    public static WeaponTextureCache instance() {
        return INSTANCE;
    }

    /**
     * Returns the registered texture for a resource, uploading it on first use.
     *
     * @param pack the owning content pack
     * @param id   the texture resource identifier
     * @return the registered texture identifier, or {@code null} when it is missing or unusable
     */
    public synchronized Identifier get(ContentPack pack, ResourceId id) {
        Entry entry = entry(pack, id);
        return entry == null ? null : entry.textureId;
    }

    /**
     * Returns the registered texture together with the size of the image it was uploaded from.
     *
     * <p>The size is what makes an animated sprite sheet usable: the frame count is derived from the pixel dimensions, so
     * a caller that draws frames needs the numbers that were actually uploaded rather than a guess. Values are captured
     * during the upload, so asking costs one map lookup.</p>
     *
     * @param pack the owning content pack
     * @param id   the texture resource identifier
     * @return the texture and its pixel size, or {@code null} when it is missing or unusable
     */
    public synchronized Uploaded getUploaded(ContentPack pack, ResourceId id) {
        Entry entry = entry(pack, id);
        if (entry == null || entry.textureId == null) {
            return null;
        }
        return new Uploaded(entry.textureId, entry.width, entry.height);
    }

    /**
     * Resolves, uploads and remembers one texture.
     *
     * @return the cache entry, or {@code null} when the resource is absent or could not be uploaded
     */
    private Entry entry(ContentPack pack, ResourceId id) {
        if (pack == null || id == null) {
            return null;
        }
        if (!RenderSystem.isOnRenderThread()) {
            warnOnce("thread|" + pack.id() + "|" + id.asString(),
                    () -> "Refusing to upload the texture '" + id + "' outside the render thread");
            return null;
        }

        String key = pack.id() + "|" + id.asString();
        Entry entry = entries.get(key);
        if (entry == ABSENT) {
            return null;
        }
        if (entry != null && entry.resource != null && entry.resource.isUnchanged()) {
            return entry.textureId == null ? null : entry;
        }

        // A creator may write either the identifier form or the file name including the extension; both spellings name
        // the same file and are resolved by the same tolerant lookup.
        Optional<ContentPackResources.Resolved> resolved =
                ContentPackResources.findTexture(pack, id, TEXTURE_DIRECTORY);
        if (resolved.isEmpty()) {
            entries.put(key, ABSENT);
            // Built only when the warning is actually emitted, so a remembered miss costs nothing per frame.
            warnOnce("missing|" + key, () -> "The weapon texture '" + id + "' was not found; tried "
                    + ContentPackResources.describeCandidates(id, TEXTURE_DIRECTORY,
                    ContentPackResources.TEXTURE_SUFFIXES));
            return null;
        }

        Entry uploaded = upload(pack, resolved.get());
        entries.put(key, uploaded);
        return uploaded.textureId == null ? null : uploaded;
    }

    private Entry upload(ContentPack pack, ContentPackResources.Resolved resource) {
        Optional<byte[]> bytes = ContentPackResources.readBytes(resource);
        if (bytes.isEmpty()) {
            reportOnce(resource, "The weapon texture could not be read from disk");
            return new Entry(resource, null, 0, 0);
        }

        NativeImage image;
        try {
            image = NativeImage.read(bytes.get());
        } catch (IOException e) {
            reportOnce(resource, "The weapon texture is not a readable image: " + e.getMessage());
            return new Entry(resource, null, 0, 0);
        }

        Identifier textureId = textureId(pack, resource.id());
        int width = image.getWidth();
        int height = image.getHeight();
        try {
            DynamicTexture texture = new DynamicTexture(() -> "ARG " + resource.id(), image);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
        } catch (RuntimeException e) {
            image.close();
            reportOnce(resource, "Failed to upload the weapon texture: " + e.getMessage());
            return new Entry(resource, null, 0, 0);
        }

        Constants.LOG.info("[ARG] Uploaded weapon texture '{}' as {} ({}x{})", resource.id(), textureId, width, height);
        return new Entry(resource, textureId, width, height);
    }

    /**
     * Derives a stable texture identifier from the pack and the resource, so a reload re-registers the
     * same identifier and vanilla closes the previous texture.
     */
    private static Identifier textureId(ContentPack pack, ResourceId id) {
        return Identifier.fromNamespaceAndPath(Constants.MOD_ID,
                "weapon/" + shortHash(pack.id()) + "/" + id.namespace() + "/" + id.path());
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void reportOnce(ContentPackResources.Resolved resource, String message) {
        warnOnce("error|" + resource.relativePath(), () -> message + " (" + resource.relativePath() + ")");
    }

    private void warnOnce(String key, Supplier<String> message) {
        if (reported.add(key)) {
            Constants.LOG.warn("[ARG] {}", message.get());
        }
    }

    /**
     * Drops every cached texture reference, including remembered misses. The textures themselves stay
     * registered under their stable identifiers and are replaced (and closed) by the next upload, so this
     * cannot leak GPU memory and is safe on any thread.
     */
    public synchronized void clear() {
        entries.clear();
        reported.clear();
    }

    /** @return the number of cached entries, including remembered failures. */
    public synchronized int size() {
        return entries.size();
    }

    private static final java.util.Set<String> reported = new java.util.HashSet<>();

    /**
     * @param texture  the registered texture
     * @param width    the uploaded image width in pixels
     * @param height   the uploaded image height in pixels
     */
    public record Uploaded(Identifier texture, int width, int height) {
    }

    /**
     * @param resource  the file the entry came from, or {@code null} when the resource was not found
     * @param textureId the registered texture, or {@code null} when the upload failed
     * @param width     the uploaded image width in pixels, {@code 0} when the upload failed
     * @param height    the uploaded image height in pixels, {@code 0} when the upload failed
     */
    private record Entry(ContentPackResources.Resolved resource, Identifier textureId, int width, int height) {
    }
}

