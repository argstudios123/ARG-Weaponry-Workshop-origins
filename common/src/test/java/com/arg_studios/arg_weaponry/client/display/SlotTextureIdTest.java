package com.arg_studios.arg_weaponry.client.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

/**
 * The one rule that makes a display file's {@code slot} reference reach the PNG it names.
 *
 * <p>An author writes the path they can see in the pack, {@code arg_pack:textures/gui/hud/slots/m100_dmr.png}, while
 * Minecraft addresses the same file as the texture id {@code arg_pack:gui/hud/slots/m100_dmr}. A display file that is used
 * verbatim makes the game look for {@code assets/arg_pack/textures/textures/gui/...} - a path that cannot exist - so the
 * icon silently fell back to the placeholder. Both spellings must therefore survive parsing, and both must resolve to the
 * id the renderer and the synthesised item model use.</p>
 */
class SlotTextureIdTest {

    private static ResourceId id(String value) {
        ResourceId.Result parsed = ResourceId.tryParse(value);
        if (!parsed.isValid()) {
            throw new IllegalArgumentException(value + ": " + parsed.error());
        }
        return parsed.id();
    }

    @Test
    void aFileStylePathBecomesTheTextureId() {
        assertEquals(new ResourceId("arg_pack", "gui/hud/slots/m100_dmr"),
                SlotTextureId.canonical(id("arg_pack:textures/gui/hud/slots/m100_dmr.png")));
        assertEquals(new ResourceId("arg_pack", "gui/hud/slots/m100_dmr"),
                SlotTextureId.canonical(id("arg_pack:textures/gui/hud/slots/m100_dmr")),
                "the extension is optional");
    }

    @Test
    void anAlreadyCanonicalIdIsLeftAlone() {
        assertEquals(new ResourceId("arg_pack", "gui/hud/slots/m100_dmr"),
                SlotTextureId.canonical(id("arg_pack:gui/hud/slots/m100_dmr.png")));
        ResourceId canonical = id("arg_pack:gui/hud/slots/m100_dmr");
        assertSame(canonical, SlotTextureId.canonical(canonical),
                "nothing changes, so the very same id is returned instead of an equal copy");
    }

    @Test
    void aFullAssetsPathIsReducedToTheTextureId() {
        assertEquals(new ResourceId("arg_pack", "gui/hud/slots/m100_dmr"),
                SlotTextureId.canonical(id("arg_pack:assets/arg_pack/textures/gui/hud/slots/m100_dmr.png")),
                "a path copied from the repository root resolves to the same icon");
    }

    @Test
    void anUnusualNamespaceIsPreserved() {
        assertEquals(new ResourceId("some_pack", "gui/slots/icon"),
                SlotTextureId.canonical(id("some_pack:textures/gui/slots/icon.png")),
                "the rule is about the path, never about a particular pack namespace");
    }

    @Test
    void nothingDeclaredStaysNothing() {
        assertNull(SlotTextureId.canonical(null));
    }
}
