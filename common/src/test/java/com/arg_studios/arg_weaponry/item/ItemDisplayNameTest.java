package com.arg_studios.arg_weaponry.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The naming rules used for item keys and the readable fallback a content pack gets when it ships no translation.
 *
 * <p>These are the rules that replace "show the raw registry identifier": a declared identifier becomes a readable name, and a
 * value that already reads like a display name is never rewritten.</p>
 */
class ItemDisplayNameTest {

    @Test
    void identifiersBecomeReadableNames() {
        assertEquals("M100 Dmr", ItemDisplayName.humanize("m100_dmr"));
        assertEquals("Creative Ammo", ItemDisplayName.humanize("creative_ammo"));
        assertEquals("Dmr Ammo", ItemDisplayName.humanize("dmr_ammo"));
        assertEquals("Example Melee", ItemDisplayName.humanize("example-melee"));
        assertEquals("Arg Default Pack", ItemDisplayName.humanize("arg_default_pack"));
    }

    @Test
    void aDeclaredDisplayNameIsNeverRewritten() {
        assertEquals("M100 DMR", ItemDisplayName.humanize("M100 DMR"), "a value with spaces is already a display name");
        assertEquals("AK-47", ItemDisplayName.humanize("AK-47"), "a value with capitals after the first letter is kept");
    }

    @Test
    void absentValuesProduceAnEmptyStringInsteadOfNull() {
        assertEquals("", ItemDisplayName.humanize(null));
        assertEquals("", ItemDisplayName.humanize("   "));
    }

    @Test
    void theTranslationKeyFollowsTheVanillaConvention() {
        assertEquals("item.arg_pack.m100_dmr", ItemDisplayName.itemKey("arg_pack", "m100_dmr"));
        assertEquals("item.arg_weaponry.dev_m100_dmr", ItemDisplayName.itemKey("arg_weaponry", "dev_m100_dmr"));
    }
}
