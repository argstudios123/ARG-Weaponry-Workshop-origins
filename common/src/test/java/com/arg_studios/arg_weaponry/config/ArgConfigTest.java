package com.arg_studios.arg_weaponry.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The global configuration: reading, writing, defaults and the documented failure behaviour.
 *
 * <p>The file accessors take an explicit {@link Path}, so the whole lifecycle is verified in a temporary directory without a
 * game directory, a loader or a running client.</p>
 */
class ArgConfigTest {

    @TempDir
    Path root;

    @Test
    void toggleAimingDefaultsToOn() {
        assertTrue(ArgConfig.parse("{}").toggleAimingValue(), "the documented default is toggle mode");
        assertTrue(ArgConfig.defaults().toggleAimingValue());
        assertTrue(ArgConfig.DEFAULT_TOGGLE_AIMING);
    }

    @Test
    void bothValuesAreReadAsWritten() {
        assertTrue(ArgConfig.parse("{\"toggle_aiming\": true}").toggleAimingValue());
        assertFalse(ArgConfig.parse("{\"toggle_aiming\": false}").toggleAimingValue(), "hold-to-aim stays available");
    }

    @Test
    void theFileMayCarryCommentsLikeEveryOtherContentFile() {
        ArgConfig config = ArgConfig.parse("""
                {
                  // right-click toggles aiming
                  "toggle_aiming": false
                }
                """);

        assertFalse(config.toggleAimingValue());
    }

    @Test
    void anUnusableDocumentFallsBackToTheDefaults() {
        assertTrue(ArgConfig.parse("not json at all").toggleAimingValue());
        assertTrue(ArgConfig.parse("[]").toggleAimingValue(), "the document must be an object");
        assertTrue(ArgConfig.parse("{\"toggle_aiming\": \"yes\"}").toggleAimingValue(),
                "a wrongly typed value cannot switch the mode off");
        assertTrue(ArgConfig.parse("{\"toggle_aiming\": null}").toggleAimingValue());
    }

    @Test
    void anUnknownPropertyIsIgnoredSoANewerFileStillLoads() {
        ArgConfig config = ArgConfig.parse("{\"toggle_aiming\": false, \"something_new\": 12}");

        assertFalse(config.toggleAimingValue());
    }

    @Test
    void aMissingFileIsCreatedWithTheDefaults() throws Exception {
        Path file = root.resolve("config").resolve(ArgConfig.FILE_NAME);

        ArgConfig created = ArgConfig.read(file);

        assertTrue(created.toggleAimingValue());
        assertTrue(Files.isRegularFile(file), "the file is created so the player has something to edit");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("toggle_aiming"));
    }

    @Test
    void aWrittenValueIsReadBackUnchanged() throws Exception {
        Path file = root.resolve("arg_weaponry.json");

        ArgConfig.write(file, new ArgConfig(false));

        assertFalse(ArgConfig.read(file).toggleAimingValue());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("false"));
    }

    @Test
    void aMalformedFileIsNotOverwritten() throws Exception {
        Path file = root.resolve("arg_weaponry.json");
        Files.writeString(file, "{ broken", StandardCharsets.UTF_8);

        ArgConfig config = ArgConfig.read(file);

        assertTrue(config.toggleAimingValue(), "the defaults are used...");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("broken"),
                "...but the file the player may want to repair is left alone");
    }

    @Test
    void theLiveValueCanBeChangedWithoutAFile() {
        try {
            ArgConfig.setToggleAiming(false);
            assertFalse(ArgConfig.toggleAiming(), "the aiming input reads this value every tick");
        } finally {
            ArgConfig.setToggleAiming(ArgConfig.DEFAULT_TOGGLE_AIMING);
        }
    }
}
