package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplayLoader;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the parsed display configuration of each weapon, so the JSON is read once instead of every
 * frame.
 *
 * <p>The display document names the model, texture and animation resources, so it is the entry point
 * of the render data. Entries remember the size and modification time of their file, which is how an
 * edited pack is picked up.</p>
 *
 * <p>A display resource that is absent is remembered once and then reported once, so a broken reference
 * costs one diagnostic and one lookup instead of one of each per rendered frame. Remembered misses are
 * dropped by {@link #clear()}, which is what the resource-reload hook calls.</p>
 */
public final class WeaponDisplayCache {

    private static final WeaponDisplayCache INSTANCE = new WeaponDisplayCache();

    /** Entry stored for a resource that was looked up and does not exist; dropped by {@link #clear()}. */
    private static final Entry ABSENT = new Entry(null, null);

    private final Map<String, Entry> entries = new HashMap<>();

    /** Caches the display configuration of a weapon definition. */
    private WeaponDisplayCache() {
    }

    /** @return the shared cache instance. */
    public static WeaponDisplayCache instance() {
        return INSTANCE;
    }

    /**
     * Returns the display configuration of a weapon, parsing it on first use.
     *
     * @param pack       the owning content pack
     * @param definition the weapon definition
     * @return the configuration, or {@code null} when it is missing or unusable
     */
    public synchronized Optional<WeaponDisplay> get(ContentPack pack, WeaponDefinition definition) {
        if (pack == null || definition == null || definition.display() == null) {
            return Optional.empty();
        }

        String key = pack.id() + "|" + definition.display().asString();
        Entry entry = entries.get(key);
        if (entry == ABSENT) {
            return Optional.empty();
        }
        if (entry != null && entry.resource != null && entry.resource.isUnchanged()) {
            return Optional.ofNullable(entry.display);
        }

        Optional<ContentPackResources.Resolved> resolved = ContentPackResources.find(pack, definition.display(),
                WeaponDisplayLoader.DISPLAY_DIRECTORY, ContentPackResources.JSON_SUFFIXES);
        ValidationReport report = new ValidationReport();
        if (resolved.isEmpty()) {
            // The loader owns the display diagnostics, so let it report the missing resource before the miss is
            // remembered; that keeps the explanation available exactly once instead of on every frame.
            WeaponDisplayLoader.load(pack, definition, report);
            report.logAll();
            entries.put(key, ABSENT);
            return Optional.empty();
        }

        WeaponDisplay display = WeaponDisplayLoader.load(pack, definition, report).orElse(null);
        report.logAll();
        entries.put(key, new Entry(resolved.get(), display));
        return Optional.ofNullable(display);
    }

    /** Drops every cached display configuration, including remembered misses. */
    public synchronized void clear() {
        entries.clear();
    }

    /** @return the number of cached entries. */
    public synchronized int size() {
        return entries.size();
    }

    private record Entry(ContentPackResources.Resolved resource, WeaponDisplay display) {
    }
}
