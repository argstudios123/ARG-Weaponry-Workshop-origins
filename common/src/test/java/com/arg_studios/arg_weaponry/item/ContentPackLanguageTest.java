package com.arg_studios.arg_weaponry.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The document a content pack is served as its {@code lang/en_us.json}.
 *
 * <p>The regression this pins is the reported missing translations: the bundled pack shipped an <em>empty</em> language file,
 * Minecraft's language loader threw on it, and every key in the game fell back to its raw form. The generated document is
 * therefore always a JSON object, and it carries a readable fallback for the pack's items.</p>
 */
class ContentPackLanguageTest {

    @Test
    void anEmptyOrMissingDocumentBecomesAnEmptyObject() {
        assertEquals("{}", ContentPackLanguage.mergedDocument(null, null));
        assertEquals("{}", ContentPackLanguage.mergedDocument(new byte[0], null));
        assertEquals("{}", ContentPackLanguage.mergedDocument("   \n".getBytes(StandardCharsets.UTF_8), null),
                "a whitespace-only file must not be served as-is");
        assertTrue(ContentPackLanguage.readEntries(null).isEmpty());
    }

    @Test
    void aMalformedDocumentIsTreatedAsEmpty() {
        String merged = ContentPackLanguage.mergedDocument("{not json".getBytes(StandardCharsets.UTF_8), null);
        assertParsesToString(merged);
        assertEquals("{}", merged);
    }

    @Test
    void aNonObjectDocumentIsReplaced() {
        assertEquals("{}", ContentPackLanguage.mergedDocument("[1, 2, 3]".getBytes(StandardCharsets.UTF_8), null));
        assertEquals("{}", ContentPackLanguage.mergedDocument("null".getBytes(StandardCharsets.UTF_8), null));
        assertEquals("{}", ContentPackLanguage.mergedDocument("\"a string\"".getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    void thePacksOwnEntriesAlwaysWin() {
        Map<String, String> fallbacks = new LinkedHashMap<>();
        fallbacks.put("item.arg_pack.m100_dmr", "Fallback");
        fallbacks.put("item.arg_pack.dmr_ammo", "Ammo Fallback");

        String merged = ContentPackLanguage.mergedDocument(
                "{\"item.arg_pack.m100_dmr\": \"M100 DMR\"}".getBytes(StandardCharsets.UTF_8), fallbacks);
        JsonObject object = assertParsesToString(merged);

        assertEquals("M100 DMR", object.get("item.arg_pack.m100_dmr").getAsString());
        assertEquals("Ammo Fallback", object.get("item.arg_pack.dmr_ammo").getAsString(),
                "an untranslated item still gets a readable name");
    }

    @Test
    void generatedNamesCoverEveryRegisteredItemOfThatPack() {
        Map<String, String> fallbacks = new LinkedHashMap<>();
        ContentPackLanguage.addItemName(fallbacks, "arg_pack", "m100_dmr", "m100_dmr");
        ContentPackLanguage.addItemName(fallbacks, "arg_pack", "dmr_ammo", "dmr_ammo");
        ContentPackLanguage.addItemName(fallbacks, "arg_pack", "creative_ammo", "creative_ammo");
        ContentPackLanguage.addTranslationName(fallbacks, "arg_default_pack.name", "arg_default_pack");

        JsonObject object = assertParsesToString(ContentPackLanguage.mergedDocument(null, fallbacks));

        assertEquals("M100 Dmr", object.get("item.arg_pack.m100_dmr").getAsString());
        assertEquals("Dmr Ammo", object.get("item.arg_pack.dmr_ammo").getAsString());
        assertEquals("Creative Ammo", object.get("item.arg_pack.creative_ammo").getAsString());
        assertEquals("Arg Default Pack", object.get("arg_default_pack.name").getAsString());
    }

    @Test
    void onlyStringValuesAreKept() {
        Map<String, String> entries = ContentPackLanguage.readEntries(
                "{\"a\": \"text\", \"b\": 5, \"c\": null, \"d\": {\"nested\": \"x\"}}"
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(Map.of("a", "text"), entries);
    }

    private static JsonObject assertParsesToString(String document) {
        assertNotNull(document);
        var parsed = JsonParser.parseString(document);
        assertTrue(parsed.isJsonObject(), "the served document must always be a JSON object");
        return parsed.getAsJsonObject();
    }
}
