package com.arg_studios.arg_weaponry.weapon.equip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The two global toggles and the optional skip threshold, parsed from the configuration document.
 *
 * <p>Only the pure parser and the resolved settings are exercised here; the one file read lives in {@code get()} and needs
 * the loader's game-directory service, so the tests install settings with {@code apply(...)} and restore the defaults
 * afterwards.</p>
 */
class DrawHolsterConfigTest {

    @AfterEach
    void restoreDefaults() {
        DrawHolsterConfig.apply(DrawHolsterConfig.Settings.DEFAULT);
    }

    @Test
    void theDocumentedDefaultsAreOnOnAndOff() {
        DrawHolsterConfig.Settings defaults = DrawHolsterConfig.parse("{}");

        assertTrue(defaults.enableDrawHolster(), "the feature is enabled unless it is switched off");
        assertTrue(defaults.holsterSwitchDelay(), "delayed switches are enabled unless they are switched off");
        assertEquals(0.0, defaults.holsterSkipBelowDrawProgress(), "the skip rule is opt-in");
        assertTrue(defaults.blockGunsInOffhand(), "a weapon is kept out of the offhand unless that is switched off");
    }

    @Test
    void theOffhandRestrictionCanBeSwitchedOffForAFutureDualWield() {
        assertFalse(DrawHolsterConfig.parse("{ \"blockGunsInOffhand\": false }").blockGunsInOffhand(),
                "the one switch a dual-wield implementation would flip");
        assertTrue(DrawHolsterConfig.parse("{ \"blockGunsInOffhand\": true }").blockGunsInOffhand());
        assertTrue(new DrawHolsterConfig.Settings(true, true, 0.0, true).blockGunsInOffhand(),
                "the four-argument form used before the toggle existed keeps the restriction on");
    }

    @Test
    void theDocumentedPropertiesAreRead() {
        DrawHolsterConfig.Settings settings = DrawHolsterConfig.parse("""
                {
                  "enableDrawHolster": false,
                  "holsterSwitchDelay": false,
                  "holsterSkipBelowDrawProgress": 0.5
                }
                """);

        assertFalse(settings.enableDrawHolster());
        assertFalse(settings.holsterSwitchDelay());
        assertEquals(0.5, settings.holsterSkipBelowDrawProgress());
    }

    @Test
    void absentOrUnusableDocumentsFallBackToTheDefaults() {
        assertSame(DrawHolsterConfig.Settings.DEFAULT, DrawHolsterConfig.parse(null));
        assertSame(DrawHolsterConfig.Settings.DEFAULT, DrawHolsterConfig.parse(""));
        assertSame(DrawHolsterConfig.Settings.DEFAULT, DrawHolsterConfig.parse("   "));
        assertEquals(DrawHolsterConfig.Settings.DEFAULT, DrawHolsterConfig.parse("{ not json"),
                "a broken file must never stop the game from loading");
        assertEquals(DrawHolsterConfig.Settings.DEFAULT, DrawHolsterConfig.parse("[1, 2, 3]"),
                "the document is an object, not an array");
    }

    @Test
    void aWronglyTypedPropertyFallsBackToItsOwnDefault() {
        DrawHolsterConfig.Settings settings = DrawHolsterConfig.parse("""
                {
                  "enableDrawHolster": "yes",
                  "holsterSwitchDelay": 3,
                  "holsterSkipBelowDrawProgress": "half"
                }
                """);

        assertTrue(settings.enableDrawHolster(), "a string is not a boolean, so the default stays");
        assertTrue(settings.holsterSwitchDelay());
        assertEquals(0.0, settings.holsterSkipBelowDrawProgress());
    }

    @Test
    void theThresholdIsClampedIntoTheUnitRange() {
        assertEquals(1.0, DrawHolsterConfig.parse("{\"holsterSkipBelowDrawProgress\": 4.0}")
                .holsterSkipBelowDrawProgress());
        assertEquals(0.0, DrawHolsterConfig.parse("{\"holsterSkipBelowDrawProgress\": -2.0}")
                .holsterSkipBelowDrawProgress());
        assertEquals(0.25, DrawHolsterConfig.parse("{\"holsterSkipBelowDrawProgress\": 0.25}")
                .holsterSkipBelowDrawProgress());
    }

    @Test
    void theSkipRuleOnlyAppliesWhenItIsConfigured() {
        DrawHolsterConfig.Settings off = DrawHolsterConfig.Settings.DEFAULT;
        assertFalse(off.skipsHolsterBelow(0.0), "the rule is off by default");
        assertFalse(off.skipsHolsterBelow(0.99));

        DrawHolsterConfig.Settings half = new DrawHolsterConfig.Settings(true, true, 0.5);
        assertTrue(half.skipsHolsterBelow(0.0), "a switch at the very start of the draw skips the holster");
        assertTrue(half.skipsHolsterBelow(0.49));
        assertFalse(half.skipsHolsterBelow(0.5), "at the threshold the holster plays normally");
        assertFalse(half.skipsHolsterBelow(1.0));
    }

    @Test
    void installedSettingsAreWhatTheGameReads() {
        DrawHolsterConfig.Settings installed = new DrawHolsterConfig.Settings(false, false, 0.3);

        DrawHolsterConfig.apply(installed);

        assertSame(installed, DrawHolsterConfig.get());
        DrawHolsterConfig.apply(null);
        assertSame(DrawHolsterConfig.Settings.DEFAULT, DrawHolsterConfig.get());
    }

    @Test
    void aNonFiniteThresholdIsTreatedAsDisabled() {
        assertEquals(0.0, new DrawHolsterConfig.Settings(true, true, Double.NaN).holsterSkipBelowDrawProgress());
        assertEquals(0.0, new DrawHolsterConfig.Settings(true, true,
                Double.POSITIVE_INFINITY).holsterSkipBelowDrawProgress());
    }
}
