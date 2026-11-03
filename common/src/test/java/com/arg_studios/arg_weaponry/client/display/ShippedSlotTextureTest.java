package com.arg_studios.arg_weaponry.client.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The shipped weapon's slot icon, checked against the display file and the PNG the pack really contains.
 *
 * <p>This is the configuration half of the "my 64x64 slot texture does not appear" report. The icon only reaches the item
 * renderer when the {@code slot} reference of the display file resolves to a PNG inside the pack, because that reference is
 * what the synthesised item model uses as its {@code layer0}. The test therefore walks the real chain - weapon definition to
 * display file to texture id to file - and checks the image is the 64x64 icon the artist shipped, so a broken path or a
 * replaced file fails here instead of only being noticed in game.</p>
 */
class ShippedSlotTextureTest {

    /** Classpath prefix of the bundled default pack. */
    private static final String PACK = "assets/arg_weaponry/weapon_packs/arg_default_pack/assets/";

    private static final String WEAPON = PACK + "arg_pack/weapons_registry/weapons/dmrs/m100_dmr.json";

    @Test
    void theShippedWeaponsSlotReferenceResolvesToItsIcon() throws Exception {
        ResourceId slot = declaredSlot();
        ResourceId texture = SlotTextureId.canonical(slot);

        assertEquals(new ResourceId("arg_pack", "gui/hud/slots/m100_dmr"), texture,
                "the declared slot reference must end up as the texture id the item model can use");

        String resource = PACK + texture.namespace() + "/textures/" + texture.path() + ".png";
        try (InputStream stream = ShippedSlotTextureTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "the pack does not contain the declared slot icon at " + resource);
            BufferedImage icon = ImageIO.read(stream);
            assertNotNull(icon, resource + " is not a readable PNG");
            assertEquals(64, icon.getWidth(), "the slot icon must stay 64 pixels wide: " + resource);
            assertEquals(64, icon.getHeight(), "the slot icon must stay 64 pixels high: " + resource);
        }
    }

    @Test
    void theFileStyleSpellingOfTheSameReferenceResolvesToTheSameIcon() throws Exception {
        // What an author sees in the pack, and what an installed content pack may still contain; the loader accepts both, so
        // the icon must be found either way.
        assertEquals(SlotTextureId.canonical(declaredSlot()),
                SlotTextureId.canonical(new ResourceId("arg_pack", "textures/gui/hud/slots/m100_dmr.png")));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * @return the {@code slot} reference of the shipped weapon, read through its display file exactly like the loader does
     */
    private static ResourceId declaredSlot() throws Exception {
        JsonObject weapon = readJson(WEAPON).getAsJsonObject();
        JsonElement display = weapon.get("display");
        assertNotNull(display, "the shipped weapon declares no display configuration");
        ResourceId displayId = parse(display.getAsString());

        JsonObject displayDocument = readJson(PACK + displayId.namespace() + "/display/" + displayId.path() + ".json")
                .getAsJsonObject();
        JsonElement slot = displayDocument.get("slot");
        assertNotNull(slot, "the shipped display file declares no slot icon");
        return parse(slot.getAsString());
    }

    private static ResourceId parse(String value) {
        ResourceId.Result parsed = ResourceId.tryParse(value);
        assertTrue(parsed.isValid(), "'" + value + "' is not a valid resource identifier: " + parsed.error());
        return parsed.id();
    }

    private static JsonElement readJson(String resource) throws Exception {
        try (InputStream stream = ShippedSlotTextureTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "the shipped resource " + resource + " is not on the test classpath");
            return JsoncReader.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
