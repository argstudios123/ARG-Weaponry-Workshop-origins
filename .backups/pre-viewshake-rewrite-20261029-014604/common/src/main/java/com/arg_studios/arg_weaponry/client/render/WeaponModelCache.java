package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.model.BedrockGeometryParser;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Parses weapon geometry once and keeps the CPU-side result.
 *
 * <p>Cache entries remember the size and modification time of the file they came from, so an edited
 * pack is noticed without restarting the game, and a failed parse is not retried every frame. A
 * resource that is <em>absent</em> is remembered too: a missing model costs one lookup and one warning,
 * not a filesystem lookup and a rebuilt message on every rendered frame. An absent resource is retried
 * only after {@link #clear()}, which is what the resource-reload hook calls. The cache holds no GPU
 * resources; clearing it is always safe.</p>
 */
public final class WeaponModelCache {

    /** Directory below a namespace that holds geometry documents. */
    public static final String MODEL_DIRECTORY = "models";

    private static final WeaponModelCache INSTANCE = new WeaponModelCache();

    /**
     * Entry stored for a resource that was looked up and does not exist. {@link #clear()} forgets it, so a
     * file that appears later is picked up on the next lookup instead of being re-checked every frame.
     */
    private static final Entry ABSENT = new Entry(null, null);

    private final Map<String, Entry> entries = new HashMap<>();

    private WeaponModelCache() {
    }

    /** @return the shared cache instance. */
    public static WeaponModelCache instance() {
        return INSTANCE;
    }

    /**
     * Returns the parsed model for a resource, parsing it on first use.
     *
     * @param pack the owning content pack
     * @param id   the geometry resource identifier
     * @return the model, or {@code null} when it is missing or cannot be parsed
     */
    public synchronized WeaponModel get(ContentPack pack, ResourceId id) {
        if (pack == null || id == null) {
            return null;
        }

        String key = pack.id() + "|" + id.asString();
        Entry entry = entries.get(key);
        if (entry == ABSENT) {
            return null;
        }
        if (entry != null && entry.resource != null && entry.resource.isUnchanged()) {
            return entry.model;
        }

        Optional<ContentPackResources.Resolved> resolved =
                ContentPackResources.find(pack, id, MODEL_DIRECTORY, ContentPackResources.JSON_SUFFIXES);
        if (resolved.isEmpty()) {
            entries.put(key, ABSENT);
            // Built only when the warning is actually emitted, so a remembered miss costs nothing per frame.
            warnOnce(key, () -> "The weapon model '" + id + "' was not found; tried "
                    + ContentPackResources.describeCandidates(id, MODEL_DIRECTORY,
                    ContentPackResources.JSON_SUFFIXES));
            return null;
        }

        WeaponModel model = parse(pack, resolved.get());
        entries.put(key, new Entry(resolved.get(), model));
        return model;
    }

    private WeaponModel parse(ContentPack pack, ContentPackResources.Resolved resource) {
        ValidationReport report = new ValidationReport();
        Optional<String> text = ContentPackResources.readText(resource);
        if (text.isEmpty()) {
            report.error(pack.id(), resource.relativePath(), "The weapon model could not be read from disk");
            report.logAll();
            return null;
        }

        JsonElement root;
        try {
            root = JsoncReader.parse(text.get());
        } catch (JsoncReader.JsoncException e) {
            report.error(pack.id(), resource.relativePath(), e.describeLocation(),
                    "Invalid JSON in the weapon model: " + e.getMessage());
            report.logAll();
            return null;
        }

        if (!(root instanceof JsonObject document)) {
            report.error(pack.id(), resource.relativePath(), "$",
                    "Expected a JSON object at the root of the weapon model");
            report.logAll();
            return null;
        }

        WeaponModel model = BedrockGeometryParser.parse(pack.id(), resource.id(), resource.relativePath(), document,
                report);
        report.logAll();
        return model;
    }

    private void warnOnce(String key, Supplier<String> message) {
        if (reported.add(key)) {
            Constants.LOG.warn("[ARG] {}", message.get());
        }
    }

    private static final java.util.Set<String> reported = new java.util.HashSet<>();

    /** Drops every cached model, including remembered misses, so the next frame re-reads the pack. */
    public synchronized void clear() {
        entries.clear();
        reported.clear();
    }

    /** @return the number of cached entries, including remembered failures. */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * @param resource the file the entry came from, or {@code null} when the resource was not found
     * @param model    the parsed model, or {@code null} when parsing failed
     */
    private record Entry(ContentPackResources.Resolved resource, WeaponModel model) {
    }
}
