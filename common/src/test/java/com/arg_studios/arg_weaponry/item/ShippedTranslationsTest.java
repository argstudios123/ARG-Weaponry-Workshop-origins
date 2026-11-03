package com.arg_studios.arg_weaponry.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.google.gson.JsonObject;

/**
 * The translation entries that ship with the mod and with the bundled content pack.
 *
 * <p>Two distinct failures are covered here, because they looked identical on screen:</p>
 * <ul>
 *   <li>the bundled pack shipped an <em>empty</em> language file, which made Minecraft fail the entire language load, so every
 *   key in the game - including the mod's own key bindings - showed its raw form;</li>
 *   <li>item names and the creative tab title had no entries at all.</li>
 * </ul>
 *
 * <p>The test reads the real lang files and the real registry documents from the build output, so a future edit of either is
 * covered.</p>
 */
class ShippedTranslationsTest {

    private static final String MOD_LANG = "assets/arg_weaponry/lang/en_us.json";
    private static final String PACK_ROOT = "assets/arg_weaponry/weapon_packs/arg_default_pack";
    private static final String PACK_LANG = PACK_ROOT + "/assets/arg_pack/lang/en_us.json";

    /** Every key binding and the category the mod registers. */
    private static final Set<String> KEYBIND_KEYS = Set.of(
            "key.category.arg_weaponry.weapons",
            "key.arg_weaponry.aim",
            "key.arg_weaponry.fire",
            "key.arg_weaponry.fire_mode",
            "key.arg_weaponry.inspect",
            "key.arg_weaponry.reload",
            "key.arg_weaponry.animation_tester");

    private static final Set<String> FIRE_MODE_KEYS = Set.of(
            "arg_weaponry.fire_mode.changed",
            "arg_weaponry.fire_mode.single",
            "arg_weaponry.fire_mode.burst",
            "arg_weaponry.fire_mode.auto");

    @Test
    void everyKeybindingAndTheCategoryAreTranslated() {
        Map<String, String> entries = lang(MOD_LANG);

        for (String key : KEYBIND_KEYS) {
            assertTrue(entries.containsKey(key), "missing translation for '" + key + "'");
            assertFalse(entries.get(key).isBlank(), "'" + key + "' must have a readable label");
        }
        for (String key : FIRE_MODE_KEYS) {
            assertTrue(entries.containsKey(key), "missing translation for '" + key + "'");
        }
    }

    @Test
    void theBundledPackLanguageIsAValidObject() {
        // The regression: an empty file here stops Minecraft from loading *any* language.
        Map<String, String> entries = lang(PACK_LANG);

        assertFalse(entries.isEmpty(), "the bundled pack must ship a real language document");
        assertTrue(entries.containsKey("arg_default_pack.name"), "the creative tab title needs an entry");
        assertEqualsIgnoringCase("ARG Default Pack", entries.get("arg_default_pack.name"));
    }

    @Test
    void everyRegisteredItemOfTheBundledPackIsTranslated() throws Exception {
        Map<String, String> entries = lang(PACK_LANG);
        Set<String> ids = registryItemIds();

        assertFalse(ids.isEmpty(), "the shipped pack must declare at least one registrable id");
        for (String id : ids) {
            String[] parts = id.split(":", 2);
            String key = ItemDisplayName.itemKey(parts[0], parts[1]);
            assertTrue(entries.containsKey(key), "missing translation for registered item '" + id + "' (" + key + ")");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertEqualsIgnoringCase(String expected, String actual) {
        assertNotNull(actual);
        assertTrue(expected.equalsIgnoreCase(actual.trim()), "expected '" + expected + "' but was '" + actual + "'");
    }

    /** @return every {@code weapon_id}/{@code ammo_id} the shipped pack's registry documents declare. */
    private static Set<String> registryItemIds() throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        URL registry = ShippedTranslationsTest.class.getClassLoader()
                .getResource(PACK_ROOT + "/assets/arg_pack/weapons_registry");
        assertNotNull(registry, "the shipped pack's registry must be on the test classpath");
        Path root = Path.of(registry.toURI());
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (text.isEmpty()) {
                    // Placeholder documents (the shipped melee/throwable examples) register nothing.
                    continue;
                }
                JsonObject document = JsoncReader.parse(text).getAsJsonObject();
                if (document.has("weapon_id")) {
                    ids.add(document.get("weapon_id").getAsString());
                } else if (document.has("ammo_id")) {
                    ids.add(document.get("ammo_id").getAsString());
                }
            }
        }
        return ids;
    }

    private static Map<String, String> lang(String resource) {
        try (InputStream stream = ShippedTranslationsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "the language file '" + resource + "' must be on the test classpath");
            return ContentPackLanguage.readEntries(stream.readAllBytes());
        } catch (Exception e) {
            throw new AssertionError("the language file '" + resource + "' could not be read", e);
        }
    }
}
