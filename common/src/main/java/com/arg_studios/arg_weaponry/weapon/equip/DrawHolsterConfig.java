package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.platform.Services;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The global draw/holster toggles, read from an optional file in the game directory.
 *
 * <p>The project has no configuration library, so this follows the lightest convention that fits: one optional JSON
 * document at {@code <gameDir>/arg_weaponry/draw_holster.json}, read once per process, with the documented defaults when
 * it is absent or unreadable. Nothing is ever written: a server operator or player who wants different behaviour creates
 * the file, and a missing file is not an error.</p>
 *
 * <pre>
 *   {
 *     "enableDrawHolster": true,
 *     "holsterSwitchDelay": true,
 *     "holsterSkipBelowDrawProgress": 0.0,
 *     "handoffEnabled": true
 *   }
 * </pre>
 *
 * <ul>
 *   <li>{@code enableDrawHolster} - master switch. When it is {@code false} no hook does anything and every weapon keeps
 *   today's behaviour, including its current draw/holster sounds.</li>
 *   <li>{@code holsterSwitchDelay} - when {@code false}, hotbar switches are never intercepted (the vanilla instant switch
 *   stays), but draw clips still play.</li>
 *   <li>{@code holsterSkipBelowDrawProgress} - optional, {@code 0.0} by default, which disables it. When set to a value in
 *   {@code (0, 1]}, switching away during a draw whose progress is below that share skips the holster and switches
 *   immediately.</li>
 *   <li>{@code handoffEnabled} - when {@code true} (the default) the delegated handoff subsystem keeps the outgoing weapon
 *   visible for its holster clip and starts the incoming weapon's draw at the handoff boundary. When {@code false} the
 *   subsystem is inert: no switch is deferred, no holster plays, and every weapon behaves like a plain vanilla item.</li>
 * </ul>
 *
 * <p>{@link #parse(String)} is pure and is what the tests drive; {@link #get()} performs the one file read, lazily, so the
 * class never touches the file system for a caller that only wants the defaults.</p>
 */
public final class DrawHolsterConfig {

    /** Folder inside the game directory that already holds the installed content packs. */
    public static final String FOLDER = "arg_weaponry";

    /** File name of the optional configuration document. */
    public static final String FILE_NAME = "draw_holster.json";

    private static volatile Settings settings = Settings.DEFAULT;
    private static volatile boolean loaded;

    private DrawHolsterConfig() {
    }

    /**
     * The resolved toggles.
     *
     * @param enableDrawHolster             the master switch
     * @param holsterSwitchDelay            whether a hotbar switch waits for the holster clip
     * @param holsterSkipBelowDrawProgress  draw progress below which a switch skips the holster, in {@code [0, 1]}; {@code 0}
     *                                      disables the rule
     * @param handoffEnabled                whether the handoff subsystem keeps the outgoing weapon visible for its holster
     * @param blockGunsInOffhand            whether a weapon of this mod may never occupy the offhand; see
     *                                      {@code OffhandRestriction}. Turning it off restores vanilla swapping for every
     *                                      weapon, which is what a future dual-wield implementation would build on
     */
    public record Settings(boolean enableDrawHolster, boolean holsterSwitchDelay,
                           double holsterSkipBelowDrawProgress, boolean handoffEnabled,
                           boolean blockGunsInOffhand) {

        /** The documented defaults: feature on, delayed switches on, the skip rule off, handoff on, offhand blocked. */
        public static final Settings DEFAULT = new Settings(true, true, 0.0, true, true);

        /**
         * Keeps the four-argument form used before the offhand restriction existed; it blocks the offhand.
         *
         * @param enableDrawHolster            the master switch
         * @param holsterSwitchDelay           whether a hotbar switch waits for the holster clip
         * @param holsterSkipBelowDrawProgress draw progress below which a switch skips the holster
         * @param handoffEnabled               whether the handoff subsystem is active
         */
        public Settings(boolean enableDrawHolster, boolean holsterSwitchDelay, double holsterSkipBelowDrawProgress,
                        boolean handoffEnabled) {
            this(enableDrawHolster, holsterSwitchDelay, holsterSkipBelowDrawProgress, handoffEnabled, true);
        }

        /**
         * Keeps the three-argument form used before the handoff subsystem existed; it enables the subsystem.
         *
         * @param enableDrawHolster            the master switch
         * @param holsterSwitchDelay           whether a hotbar switch waits for the holster clip
         * @param holsterSkipBelowDrawProgress draw progress below which a switch skips the holster
         */
        public Settings(boolean enableDrawHolster, boolean holsterSwitchDelay, double holsterSkipBelowDrawProgress) {
            this(enableDrawHolster, holsterSwitchDelay, holsterSkipBelowDrawProgress, true);
        }

        public Settings {
            holsterSkipBelowDrawProgress = Double.isFinite(holsterSkipBelowDrawProgress)
                    ? Math.max(0.0, Math.min(1.0, holsterSkipBelowDrawProgress)) : 0.0;
        }

        /**
         * @param progress the progress of the draw that is being interrupted, in {@code [0, 1]}
         * @return whether the holster should be skipped and the switch applied immediately
         */
        public boolean skipsHolsterBelow(double progress) {
            return holsterSkipBelowDrawProgress > 0.0 && progress < holsterSkipBelowDrawProgress;
        }
    }

    /** @return the active settings, loading the optional file on first use. */
    public static Settings get() {
        if (!loaded) {
            loadOnce();
        }
        return settings;
    }

    /**
     * Installs settings without reading a file; used by the lazy loader and by tests.
     *
     * @param newSettings the settings to use, {@code null} meaning the defaults
     */
    public static void apply(Settings newSettings) {
        settings = newSettings == null ? Settings.DEFAULT : newSettings;
        loaded = true;
    }

    /** Forgets the loaded settings, so the next {@link #get()} reads the file again. */
    public static void reset() {
        settings = Settings.DEFAULT;
        loaded = false;
    }

    /**
     * Parses a configuration document.
     *
     * @param json the document text, may be {@code null} or blank
     * @return the settings; the documented defaults for an absent, malformed or wrongly typed value
     */
    public static Settings parse(String json) {
        if (json == null || json.isBlank()) {
            return Settings.DEFAULT;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                Constants.LOG.warn("[ARG] {} must contain a JSON object; the draw/holster defaults are used", FILE_NAME);
                return Settings.DEFAULT;
            }
            JsonObject root = parsed.getAsJsonObject();
            return new Settings(
                    booleanValue(root, "enableDrawHolster", Settings.DEFAULT.enableDrawHolster()),
                    booleanValue(root, "holsterSwitchDelay", Settings.DEFAULT.holsterSwitchDelay()),
                    doubleValue(root, "holsterSkipBelowDrawProgress",
                            Settings.DEFAULT.holsterSkipBelowDrawProgress()),
                    booleanValue(root, "handoffEnabled", Settings.DEFAULT.handoffEnabled()),
                    booleanValue(root, "blockGunsInOffhand", Settings.DEFAULT.blockGunsInOffhand()));
        } catch (RuntimeException e) {
            Constants.LOG.warn("[ARG] {} could not be read ({}); the draw/holster defaults are used", FILE_NAME,
                    e.getMessage());
            return Settings.DEFAULT;
        }
    }

    private static void loadOnce() {
        synchronized (DrawHolsterConfig.class) {
            if (loaded) {
                return;
            }
            loaded = true;
            try {
                Path file = Services.GAME_DIRECTORY.getGameDirectory().resolve(FOLDER).resolve(FILE_NAME);
                if (!Files.isRegularFile(file)) {
                    Constants.LOG.debug("[ARG] No draw/holster config at {}; the documented defaults are used", file);
                    return;
                }
                Settings parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
                settings = parsed;
                Constants.LOG.info("[ARG] Draw/holster config loaded from {}: enableDrawHolster={}, "
                                + "holsterSwitchDelay={}, holsterSkipBelowDrawProgress={}", file,
                        parsed.enableDrawHolster(), parsed.holsterSwitchDelay(),
                        parsed.holsterSkipBelowDrawProgress());
            } catch (IOException | RuntimeException e) {
                Constants.LOG.warn("[ARG] The draw/holster config could not be read ({}); the documented defaults are "
                        + "used", e.getMessage());
                settings = Settings.DEFAULT;
            }
        }
    }

    private static boolean booleanValue(JsonObject root, String key, boolean fallback) {
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            Constants.LOG.warn("[ARG] {} property '{}' must be true or false; {} is used instead", FILE_NAME, key,
                    fallback);
            return fallback;
        }
        return element.getAsBoolean();
    }

    private static double doubleValue(JsonObject root, String key, double fallback) {
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            Constants.LOG.warn("[ARG] {} property '{}' must be a number; {} is used instead", FILE_NAME, key, fallback);
            return fallback;
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            Constants.LOG.warn("[ARG] {} property '{}' must be a finite number; {} is used instead", FILE_NAME, key,
                    fallback);
            return fallback;
        }
        return value;
    }
}
