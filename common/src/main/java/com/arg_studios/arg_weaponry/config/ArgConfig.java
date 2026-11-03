package com.arg_studios.arg_weaponry.config;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;


public final class ArgConfig {

    /** The directory below the game directory that holds configuration files. */
    public static final String CONFIG_DIRECTORY = "config";

    /** The file name of the global configuration. */
    public static final String FILE_NAME = "arg_weaponry.json";

    /** The documented default of {@code toggle_aiming}. */
    public static final boolean DEFAULT_TOGGLE_AIMING = true;

    private static final String TOGGLE_AIMING_KEY = "toggle_aiming";

    private static final Gson WRITER = new GsonBuilder().setPrettyPrinting().create();

    private static volatile ArgConfig current = new ArgConfig(DEFAULT_TOGGLE_AIMING);

    /** The file the live instance was loaded from, or {@code null} until {@link #load()} ran. */
    private static Path loadedFile;

    private final boolean toggleAiming;

    /**
     * @param toggleAiming whether one right-click toggles aiming ({@code true}) or aiming is held ({@code false})
     */
    public ArgConfig(boolean toggleAiming) {
        this.toggleAiming = toggleAiming;
    }

    /** @return the defaults, i.e. the configuration used before a file was loaded. */
    public static ArgConfig defaults() {
        return new ArgConfig(DEFAULT_TOGGLE_AIMING);
    }

    /** @return the live configuration, never {@code null}. */
    public static ArgConfig current() {
        return current;
    }

    /** @return whether right-click toggles aiming instead of being held. */
    public static boolean toggleAiming() {
        return current.toggleAiming;
    }

    /** @return the value of this instance's {@code toggle_aiming}. */
    public boolean toggleAimingValue() {
        return toggleAiming;
    }

    /**
     * Changes the live value and persists it immediately.
     *
     * <p>The new value is visible to {@link #toggleAiming()} on the next call, which is what lets the aiming input
     * handler switch behaviour without a game restart.</p>
     *
     * @param toggleAiming the new value
     */
    public static synchronized void setToggleAiming(boolean toggleAiming) {
        current = new ArgConfig(toggleAiming);
        if (loadedFile != null) {
            write(loadedFile, current);
        }
    }

    /**
     * Loads the configuration from the game directory.
     *
     * <p>Called once by the shared bootstrap during mod initialisation, before any client tick can read the value.
     * Safe to call again: the file is re-read and the live instance replaced.</p>
     */
    public static synchronized void load() {
        Path file = configFile();
        loadedFile = file;
        current = read(file);
        Constants.LOG.info("[ARG] Global configuration: toggle_aiming={} ({})", current.toggleAiming, file);
    }

    /** Re-reads the file the live instance came from; does nothing before {@link #load()} ran. */
    public static synchronized void reload() {
        if (loadedFile == null) {
            return;
        }
        current = read(loadedFile);
    }

    /** @return {@code <game directory>/config/arg_weaponry.json}; never {@code null}. */
    public static Path configFile() {
        return Services.GAME_DIRECTORY.getGameDirectory().resolve(CONFIG_DIRECTORY).resolve(FILE_NAME);
    }

    /**
     * Reads a configuration file, creating it with the defaults when it does not exist.
     *
     * @param file the file to read
     * @return the parsed configuration, or {@link #defaults()} when the file is missing or unusable
     */
    public static ArgConfig read(Path file) {
        if (file == null) {
            return defaults();
        }
        if (!Files.isRegularFile(file)) {
            ArgConfig created = defaults();
            write(file, created);
            return created;
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            Constants.LOG.warn("[ARG] The global configuration '{}' could not be read: {}; the defaults are used "
                    + "and the file is left untouched", file, exception.getMessage());
            return defaults();
        }
    }

    /**
     * Parses a configuration document.
     *
     * @param text the file content
     * @return the parsed configuration, or {@link #defaults()} when the document is not usable
     */
    public static ArgConfig parse(String text) {
        JsonElement parsed;
        try {
            parsed = JsoncReader.parse(text);
        } catch (JsoncReader.JsoncException exception) {
            Constants.LOG.warn("[ARG] The global configuration is not valid JSON: {}; the defaults are used",
                    exception.describe());
            return defaults();
        }
        if (!parsed.isJsonObject()) {
            Constants.LOG.warn("[ARG] The global configuration must be a JSON object; the defaults are used");
            return defaults();
        }
        JsonElement toggle = parsed.getAsJsonObject().get(TOGGLE_AIMING_KEY);
        if (toggle == null || toggle.isJsonNull()) {
            return defaults();
        }
        if (!toggle.isJsonPrimitive() || !toggle.getAsJsonPrimitive().isBoolean()) {
            Constants.LOG.warn("[ARG] '{}' must be true or false, but the configuration declares {}; the default "
                    + "({}) is used", TOGGLE_AIMING_KEY, toggle, DEFAULT_TOGGLE_AIMING);
            return defaults();
        }
        return new ArgConfig(toggle.getAsBoolean());
    }

    /**
     * Writes a configuration file, creating the directory if needed.
     *
     * @param file   the target file
     * @param config the configuration to write
     */
    public static void write(Path file, ArgConfig config) {
        if (file == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty(TOGGLE_AIMING_KEY, config == null ? DEFAULT_TOGGLE_AIMING : config.toggleAiming);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, WRITER.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            Constants.LOG.warn("[ARG] The global configuration '{}' could not be written: {}", file,
                    exception.getMessage());
        }
    }

    @Override
    public String toString() {
        return "ArgConfig{toggle_aiming=" + toggleAiming + "}";
    }
}
