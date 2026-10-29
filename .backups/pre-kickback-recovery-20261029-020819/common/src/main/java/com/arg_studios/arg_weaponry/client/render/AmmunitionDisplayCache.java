package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.client.display.AmmunitionDisplay;
import com.arg_studios.arg_weaponry.client.display.AmmunitionDisplayLoader;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the parsed display definition of each ammunition item, so the JSON is read once instead of on every
 * item-model lookup.
 *
 * <p>This mirrors {@link WeaponDisplayCache}: entries remember the size and modification time of their file so
 * an edited pack is picked up, an absent resource is remembered once instead of being re-read every frame, and
 * {@link #clear()} — called by the client resource-reload hook through
 * {@code WeaponRenderer.invalidateCaches()} — forgets everything, including remembered misses.</p>
 */
public final class AmmunitionDisplayCache {

    private static final AmmunitionDisplayCache INSTANCE = new AmmunitionDisplayCache();

    /** Entry stored for a resource that was looked up and does not exist; dropped by {@link #clear()}. */
    private static final Entry ABSENT = new Entry(null, null);

    private final Map<String, Entry> entries = new HashMap<>();

    private AmmunitionDisplayCache() {
    }

    /** @return the shared cache instance. */
    public static AmmunitionDisplayCache instance() {
        return INSTANCE;
    }

    /**
     * Returns the display definition of an ammunition definition, parsing it on first use.
     *
     * @param pack       the owning content pack
     * @param definition the ammunition definition
     * @return the display, or empty when it is absent or unusable
     */
    public synchronized Optional<AmmunitionDisplay> get(ContentPack pack, AmmunitionDefinition definition) {
        if (pack == null || definition == null || definition.display() == null) {
            return Optional.empty();
        }

        String key = pack.id() + "|" + definition.display().asString();
        Entry entry = entries.get(key);
        if (entry == ABSENT) {
            return Optional.empty();
        }
        if (entry != null && entry.resource() != null && entry.resource().isUnchanged()) {
            return Optional.ofNullable(entry.display());
        }

        Optional<ContentPackResources.Resolved> resolved = ContentPackResources.find(pack, definition.display(),
                AmmunitionDisplayLoader.DISPLAY_DIRECTORY, ContentPackResources.JSON_SUFFIXES);
        ValidationReport report = new ValidationReport();
        if (resolved.isEmpty()) {
            // The loader owns the display diagnostics; let it explain the miss once before it is remembered.
            AmmunitionDisplayLoader.load(pack, definition, report);
            report.logAll();
            entries.put(key, ABSENT);
            return Optional.empty();
        }

        AmmunitionDisplay display = AmmunitionDisplayLoader.load(pack, definition, report).orElse(null);
        report.logAll();
        entries.put(key, new Entry(resolved.get(), display));
        return Optional.ofNullable(display);
    }

    /** Drops every cached display, including remembered misses. */
    public synchronized void clear() {
        entries.clear();
    }

    /** @return the number of cached entries, including remembered misses. */
    public synchronized int size() {
        return entries.size();
    }

    private record Entry(ContentPackResources.Resolved resource, AmmunitionDisplay display) {
    }
}
