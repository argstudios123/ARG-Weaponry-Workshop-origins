package com.arg_studios.arg_weaponry.weapon.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The one condition grammar the reload entries use.
 *
 * <p>The two variables are the whole point of the staged schema: {@code ammo_count} selects the opening clip and
 * {@code reload_inserted_count} decides between the first shell and every further one. Both spellings of the magazine
 * count are accepted so no pack has to rename anything.</p>
 */
class ReloadConditionsTest {

    @Test
    void anAbsentOrBlankOrStarConditionAlwaysApplies() {
        assertTrue(ReloadConditions.evaluate(null, 4, 2));
        assertTrue(ReloadConditions.evaluate("", 4, 2));
        assertTrue(ReloadConditions.evaluate("   ", 4, 2));
        assertTrue(ReloadConditions.evaluate("*", 4, 2));
    }

    @Test
    void theStagedSpellingReportsAnEmptyMagazineAsMinusOne() {
        // The staged schema's "nothing loaded", which is what makes the two prepare entries mutually exclusive.
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("ammo_count == -1", 0, 3));
        assertEquals(Boolean.FALSE, ReloadConditions.evaluate("ammo_count >= 0", 0, 3));
        assertEquals(Boolean.FALSE, ReloadConditions.evaluate("ammo_count == -1", 5, 3));
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("ammo_count >= 0", 5, 3));
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("ammo_count == 5", 5, 3),
                "a magazine that holds rounds reports the count itself");
    }

    @Test
    void theIdleAndLegacySpellingsStayZeroBased() {
        // The shipped content compares these against 0 for an empty magazine, so they must not follow the sentinel.
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("ammoCount == 0", 0, 3));
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("reload_count == 0", 0, 3));
        assertEquals(Boolean.FALSE, ReloadConditions.evaluate("ammoCount > 0", 0, 3));
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("ammoCount > 0", 1, 3));
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("reload_count == 3", 3, 0));
    }

    @Test
    void theInsertedCountIsEvaluatedForTheFirstAndTheLaterCycles() {
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("reload_inserted_count == 0", 0, 0));
        assertEquals(Boolean.FALSE, ReloadConditions.evaluate("reload_inserted_count > 0", 0, 0));
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("reload_inserted_count > 0", 0, 1));
        assertEquals(Boolean.FALSE, ReloadConditions.evaluate("reload_inserted_count == 0", 0, 1));
    }

    @Test
    void everyComparisonOperatorIsSupported() {
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("reload_inserted_count != 2", 0, 3));
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("reload_inserted_count >= 3", 0, 3));
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("reload_inserted_count < 4", 0, 3));
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("reload_inserted_count <= 3", 0, 3));
        assertEquals(Boolean.FALSE, ReloadConditions.evaluate("reload_inserted_count <= 2", 0, 3));
    }

    @Test
    void conjunctionsRequireEveryComparison() {
        assertEquals(Boolean.TRUE, ReloadConditions.evaluate("ammo_count == -1 && reload_inserted_count == 0", 0, 0));
        assertFalse(ReloadConditions.evaluate("ammo_count == -1 && reload_inserted_count > 0", 0, 0));
    }

    @Test
    void anExpressionOutsideTheGrammarIsReportedAsUnsupported() {
        assertNull(ReloadConditions.evaluate("magazine empty", 0, 0));
        assertNull(ReloadConditions.evaluate("ammo_count == 1.5", 1, 0));
        assertNull(ReloadConditions.evaluate("ammo_count || reload_inserted_count", 0, 0));
        assertNull(ReloadConditions.evaluate("ammo_count == 0 && nonsense", 0, 0),
                "one unsupported part makes the whole condition unsupported");
    }

    @Test
    void theVariablesOfAConditionCanBeListedForDiagnostics() {
        assertEquals(List.of("ammo_count", "reload_inserted_count"),
                ReloadConditions.variables("ammo_count == 0 && reload_inserted_count > 0"));
        assertTrue(ReloadConditions.variables(null).isEmpty());
    }

    @Test
    void theLegacySelectorKeepsItsMeaningAndSharesTheSpellings() {
        assertEquals(Boolean.TRUE, ReloadSequenceSelector.evaluate("reload_count == 0", 0));
        assertEquals(Boolean.FALSE, ReloadSequenceSelector.evaluate("reload_count == 0", 3));
        assertEquals(Boolean.TRUE, ReloadSequenceSelector.evaluate("ammoCount == 0", 0),
                "a pack that wrote the idle spelling in a reload entry is now understood instead of ignored");
        assertNull(ReloadSequenceSelector.evaluate("reload_inserted_count == 0", 0),
                "a single-clip reload has no insertion count, so that condition stays unsupported");
    }
}
