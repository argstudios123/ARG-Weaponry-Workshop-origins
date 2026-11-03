package com.arg_studios.arg_weaponry.item;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The language document a content pack is served with.
 *
 * <h2>The failure this exists for</h2>
 * <p>A content pack is mounted as a resource pack, so Minecraft reads its {@code assets/<namespace>/lang/en_us.json} and
 * merges it into the language. An <em>empty</em> file is not "no translations" to that loader: the parser yields a
 * non-object element, the language load throws, and <b>every</b> key in the game falls back to its raw form - the reported
 * "untranslated creative tab, items and key bindings". A pack that ships no file at all has the opposite problem: its items
 * have no names.</p>
 *
 * <p>This class therefore produces the document a pack is served with, always a JSON object: the pack's own entries win
 * (when its file is a valid object), then a readable fallback is added for every item the pack registers and for the pack's
 * own name key. Pure string and JSON work, so the rules are unit tested without a game.</p>
 */
public final class ContentPackLanguage {

    /** The language Minecraft itself always loads, and the only one this bridge generates. */
    public static final String DEFAULT_LANGUAGE = "en_us";

    /** The directory language files live in, inside a namespace. */
    public static final String LANGUAGE_DIRECTORY = "lang";

    private static final String JSON_EXTENSION = ".json";

    private ContentPackLanguage() {
    }

    /** @return the resource path of the generated language document, {@code lang/en_us.json} */
    public static String resourcePath() {
        return LANGUAGE_DIRECTORY + "/" + DEFAULT_LANGUAGE + JSON_EXTENSION;
    }

    /**
     * Reads the entries of one language document.
     *
     * <p>An absent, empty, malformed or non-object document yields an empty map instead of an exception: a broken pack
     * language file must never be able to stop the game's language from loading.</p>
     *
     * @param document the document's bytes, may be {@code null}
     * @return the entries it declares, never {@code null}
     */
    public static Map<String, String> readEntries(byte[] document) {
        Map<String, String> entries = new TreeMap<>();
        if (document == null || document.length == 0) {
            return entries;
        }
        String text = new String(document, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return entries;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed == null || !parsed.isJsonObject()) {
                return entries;
            }
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    entries.put(entry.getKey(), value.getAsString());
                }
            }
        } catch (RuntimeException e) {
            // A malformed document is treated as empty; the generated fallbacks are still served.
            return new TreeMap<>();
        }
        return entries;
    }

    /**
     * Builds the document a pack is served with.
     *
     * @param existing  the pack's own language file, may be {@code null}
     * @param fallbacks entries to add when the pack does not declare them, may be {@code null}
     * @return a valid JSON object containing every entry, never empty text
     */
    public static String mergedDocument(byte[] existing, Map<String, String> fallbacks) {
        Map<String, String> entries = readEntries(existing);
        if (fallbacks != null) {
            for (Map.Entry<String, String> fallback : fallbacks.entrySet()) {
                if (fallback.getKey() == null || fallback.getKey().isBlank() || fallback.getValue() == null) {
                    continue;
                }
                entries.putIfAbsent(fallback.getKey(), fallback.getValue());
            }
        }
        JsonObject object = new JsonObject();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            object.addProperty(entry.getKey(), entry.getValue());
        }
        return object.toString();
    }

    /**
     * Collects the fallback name of one registered item.
     *
     * @param target    the map being built
     * @param namespace the item's resource namespace
     * @param path      the item's registry path
     * @param declared  the name the definition declared, may be {@code null}
     */
    public static void addItemName(Map<String, String> target, String namespace, String path, String declared) {
        if (target == null || namespace == null || path == null) {
            return;
        }
        String humanised = ItemDisplayName.humanize(declared);
        if (!humanised.isBlank()) {
            target.putIfAbsent(ItemDisplayName.itemKey(namespace, path), humanised);
        }
    }

    /**
     * Collects the fallback of a translation key a pack declared (its own name, for the creative tab title).
     *
     * @param target   the map being built
     * @param key      the declared translation key, may be {@code null}
     * @param declared the identifier to humanise when the key is missing, may be {@code null}
     */
    public static void addTranslationName(Map<String, String> target, String key, String declared) {
        if (target == null || key == null || key.isBlank()) {
            return;
        }
        String humanised = ItemDisplayName.humanize(declared);
        if (!humanised.isBlank()) {
            target.putIfAbsent(key, humanised);
        }
    }

    /**
     * @param entries the entries to copy, may be {@code null}
     * @return an insertion-ordered copy, for callers that want a stable iteration order
     */
    public static Map<String, String> ordered(Map<String, String> entries) {
        return entries == null ? Map.of() : new LinkedHashMap<>(entries);
    }
}
