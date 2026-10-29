package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves, parses and remembers animation documents from content packs.
 *
 * <p>Parsing a Bedrock animation document costs a JSON read of up to a few hundred kilobytes, so it must
 * happen once per resource and not once per frame. This cache does exactly that and nothing else: it holds
 * immutable {@link AnimationFile} values, never playback state, timers, poses or rendering objects, so a
 * cached document can be shared by every weapon that references it without one instance affecting another.</p>
 *
 * <h2>Identity</h2>
 * <p>An entry is keyed by the <em>owning content pack</em> plus the <em>animation resource identifier</em>,
 * using the same {@code packId|namespace:path} convention the model cache uses. The animation name alone is
 * never a key: {@code anim.model.fire} can exist in several packs and several files, and two packs may ship
 * the same resource path with different content. Resources are resolved through
 * {@link ContentPackResources} exactly like models and textures; content packs are never mounted as vanilla
 * resource packs.</p>
 *
 * <h2>Change detection</h2>
 * <p>Every entry remembers the file size and modification time reported by the resolver, and re-validates
 * them with {@link ContentPackResources.Resolved#isUnchanged()} on each lookup. An edited file on disk is
 * therefore noticed without a reload, at the cost of one {@code stat} per lookup. Deleted files fail the
 * same check and are re-resolved. Resource reloads call {@link #clear()} so that even a change the file
 * system metadata cannot show (for example a pack replaced with identical size and timestamp) is picked
 * up.</p>
 *
 * <h2>Failures</h2>
 * <p>A missing, unreadable or malformed resource is reported once per key and <strong>not cached</strong>:
 * the next lookup retries, so fixing a file and reloading (or even touching it) recovers without a restart.
 * This deliberately differs from the model cache, which remembers failures to keep a broken model from
 * flooding the log every frame; animation lookups are driven by playback state, not by every frame, and a
 * missing animation must not stay missing for the rest of the session.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@link #load} is {@code synchronized}: resolve, read, parse and store all happen under the cache's own
 * monitor, so two concurrent first lookups parse once, no partially built entry is ever visible, and a
 * lookup can never repopulate the cache with data read before an invalidation that has already completed.
 * Nothing else in the animation pipeline is synchronized: sampling and rendering stay lock free, and the
 * monitor is never held while rendering. The hit, miss and parse counters are session totals used for
 * diagnostics and tests; they are deliberately not reset by {@link #clear()}, while {@link #size()} and
 * {@link #generation()} always describe the current cache state.</p>
 */
public final class WeaponAnimationCache {

    /** Directory below a namespace that holds animation documents. */
    public static final String ANIMATION_DIRECTORY = "animations";

    private static final WeaponAnimationCache INSTANCE = new WeaponAnimationCache();

    private final Map<String, Entry> entries = new HashMap<>();
    private final Set<String> reportedFailures = new HashSet<>();
    private long generation;
    private int hits;
    private int misses;
    private int parses;

    private WeaponAnimationCache() {
    }

    /** @return the shared cache instance. */
    public static WeaponAnimationCache instance() {
        return INSTANCE;
    }

    /**
     * Returns the parsed animation document for a resource, parsing it on first use.
     *
     * @param pack the owning content pack
     * @param id   the animation resource identifier, for example
     *             {@code arg_pack:weapons/dmrs/m100_dmr/m100_dmr}
     * @return the document, or empty when it is missing, unreadable or malformed
     */
    public synchronized Optional<AnimationFile> load(ContentPack pack, ResourceId id) {
        if (pack == null || id == null) {
            return Optional.empty();
        }

        String key = key(pack, id);
        Entry entry = entries.get(key);
        if (entry != null && entry.resource().isUnchanged()) {
            hits++;
            return Optional.of(entry.file());
        }

        misses++;
        Optional<ContentPackResources.Resolved> resolved = ContentPackResources.find(pack, id,
                ANIMATION_DIRECTORY, ContentPackResources.ANIMATION_SUFFIXES);
        if (resolved.isEmpty()) {
            entries.remove(key);
            warnOnce(key, "The animation resource '" + id.asString() + "' of pack '" + pack.id()
                    + "' was not found; tried " + ContentPackResources.describeCandidates(id, ANIMATION_DIRECTORY,
                    ContentPackResources.ANIMATION_SUFFIXES));
            return Optional.empty();
        }

        AnimationFile file = parse(pack, resolved.get(), key);
        if (file == null) {
            entries.remove(key);
            return Optional.empty();
        }
        entries.put(key, new Entry(resolved.get(), file));
        return Optional.of(file);
    }

    /**
     * Returns one named animation of a resource, loading the document through the cache.
     *
     * @param pack          the owning content pack
     * @param id            the animation resource identifier
     * @param animationName the animation name exactly as written in the file, for example
     *                      {@code anim.model.fire}
     * @return the animation, or empty when the document cannot be loaded or does not declare that name
     */
    public Optional<BedrockAnimation> animation(ContentPack pack, ResourceId id, String animationName) {
        if (animationName == null || animationName.isBlank()) {
            return Optional.empty();
        }
        Optional<AnimationFile> file = load(pack, id);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        BedrockAnimation animation = file.get().animation(animationName);
        if (animation == null) {
            synchronized (this) {
                warnOnce(key(pack, id) + "|" + animationName, "The animation resource '" + id.asString()
                        + "' of pack '" + pack.id() + "' does not declare '" + animationName + "'");
            }
            return Optional.empty();
        }
        return Optional.of(animation);
    }

    private AnimationFile parse(ContentPack pack, ContentPackResources.Resolved resource, String key) {
        parses++;
        ValidationReport report = new ValidationReport();
        Optional<String> text = ContentPackResources.readText(resource);
        if (text.isEmpty()) {
            warnOnce(key, "The animation resource '" + resource.id().asString() + "' could not be read from "
                    + resource.file());
            return null;
        }

        JsonElement root;
        try {
            root = JsoncReader.parse(text.get());
        } catch (JsoncReader.JsoncException exception) {
            warnOnce(key, "Invalid JSON in the animation resource '" + resource.relativePath() + "': "
                    + exception.describeLocation() + " " + exception.getMessage());
            return null;
        }

        if (!(root instanceof JsonObject document)) {
            warnOnce(key, "Expected a JSON object at the root of the animation resource '" + resource.relativePath()
                    + "'");
            return null;
        }

        AnimationFile file = BedrockAnimationParser.parse(pack.id(), resource.id(), resource.relativePath(), document,
                report);
        if (report.hasErrors()) {
            report.logAll();
        }
        return file;
    }

    /**
     * Forgets one cached resource, so the next lookup re-reads it.
     *
     * @param pack the owning content pack
     * @param id   the animation resource identifier
     */
    public synchronized void invalidate(ContentPack pack, ResourceId id) {
        if (pack == null || id == null) {
            return;
        }
        String key = key(pack, id);
        entries.remove(key);
        reportedFailures.remove(key);
        generation++;
    }

    /** Drops every cached document; this is what the resource-reload listeners call. */
    public synchronized void clear() {
        entries.clear();
        reportedFailures.clear();
        generation++;
    }

    /** @return the number of cached documents. */
    public synchronized int size() {
        return entries.size();
    }

    /** @return a counter that changes on every invalidation, for diagnostics and tests. */
    public synchronized long generation() {
        return generation;
    }

    /** @return how often a lookup was served from the cache. */
    public synchronized int hitCount() {
        return hits;
    }

    /** @return how often a lookup had to resolve the resource again. */
    public synchronized int missCount() {
        return misses;
    }

    /** @return how often a document was read and parsed, including failed attempts. */
    public synchronized int parseCount() {
        return parses;
    }

    private void warnOnce(String key, String message) {
        if (reportedFailures.add(key)) {
            Constants.LOG.warn("[ARG] {}", message);
        }
    }

    private static String key(ContentPack pack, ResourceId id) {
        return pack.id() + "|" + id.asString();
    }

    /**
     * @param resource the file the document was read from
     * @param file     the parsed document
     */
    private record Entry(ContentPackResources.Resolved resource, AnimationFile file) {
    }
}
