package com.arg_studios.arg_weaponry.weapon.equip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.AmmoCapacity;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The draw and holster clip selection rules.
 *
 * <p>The first three tests are the ones the schema needs most: a first-draw entry usually declares no {@code when}, so it
 * must be excluded from the ordinary pass explicitly, and it must still be reachable when the stack has never been drawn
 * before.</p>
 */
class EquipAnimationResolverTest {

    /** The shipped weapon's draw list, in file order. */
    private static final List<WeaponDefinition.DrawSelection> SHIPPED_DRAWS = List.of(
            firstDraw("anim.model.draw_first", 1.2, null),
            draw("anim.model.draw", 0.87, "ammoCount > 0"),
            draw("anim.model.draw_empty", 0.89, "ammoCount == 0"));

    private static final List<WeaponDefinition.AnimationSelection> SHIPPED_HOLSTERS = List.of(
            holster("anim.model.holster", 0.8, "ammoCount > 0"),
            holster("anim.model.holster_empty", 0.7, "ammoCount == 0"));

    // ---------------------------------------------------------------- normal draw

    @Test
    void normalDrawSelectionNeverPicksTheFirstDrawEntry() {
        EquipAnimationResolver.Selection loaded =
                EquipAnimationResolver.selectDraw(SHIPPED_DRAWS, 10, false);
        assertEquals("anim.model.draw", loaded.clip(),
                "a first-draw entry has no condition, so the ordinary pass must exclude it by flag");
        assertEquals(0.87, loaded.durationSeconds());
        assertFalse(loaded.firstDraw());
        assertFalse(loaded.emptyMagazine());

        EquipAnimationResolver.Selection empty = EquipAnimationResolver.selectDraw(SHIPPED_DRAWS, 0, false);
        assertEquals("anim.model.draw_empty", empty.clip());
        assertTrue(empty.emptyMagazine());
    }

    @Test
    void theFirstDrawFromANeverDrawnStackUsesTheFirstDrawEntry() {
        EquipAnimationResolver.Selection selection = EquipAnimationResolver.selectDraw(SHIPPED_DRAWS, 10, true);

        assertEquals("anim.model.draw_first", selection.clip());
        assertEquals(1.2, selection.durationSeconds());
        assertTrue(selection.firstDraw());
        assertFalse(selection.emptyMagazine());
    }

    @Test
    void aWeaponWithoutAFirstDrawEntryDrawsNormallyEvenOnItsFirstDraw() {
        List<WeaponDefinition.DrawSelection> draws = List.of(
                draw("anim.model.draw", 0.87, "ammoCount > 0"),
                draw("anim.model.draw_empty", 0.89, "ammoCount == 0"));

        EquipAnimationResolver.Selection selection = EquipAnimationResolver.selectDraw(draws, 10, true);

        assertEquals("anim.model.draw", selection.clip(), "no first-draw clip means the ordinary one is used");
        assertTrue(selection.firstDraw(),
                "the draw_first *sound* still applies on a first draw, which is what the sound rule expects");
    }

    @Test
    void aFirstDrawEntryWhoseConditionFailsFallsBackToTheNormalDraw() {
        List<WeaponDefinition.DrawSelection> draws = List.of(
                firstDraw("anim.model.draw_first", 1.2, "ammoCount == 0"),
                draw("anim.model.draw", 0.87, "ammoCount > 0"),
                draw("anim.model.draw_empty", 0.89, "ammoCount == 0"));

        assertEquals("anim.model.draw", EquipAnimationResolver.selectDraw(draws, 10, true).clip(),
                "the first-draw entry does not apply, so the ordinary entry is used");
        assertEquals("anim.model.draw_first", EquipAnimationResolver.selectDraw(draws, 0, true).clip(),
                "and it is used when its own condition does hold");
    }

    @Test
    void anUnsupportedConditionIsReportedAndTheNextEntryStillWins() {
        List<WeaponDefinition.DrawSelection> draws = List.of(
                firstDraw("anim.model.draw_first", 1.2, "ammoCount > banana"),
                draw("anim.model.draw", 0.87, null));

        EquipAnimationResolver.Selection selection = EquipAnimationResolver.selectDraw(draws, 10, true);

        assertEquals("anim.model.draw", selection.clip());
        assertEquals(List.of("ammoCount > banana"), selection.unsupportedConditions(),
                "the evaluator's verdict is surfaced instead of guessed");
    }

    @Test
    void theFirstDrawEntryWinsInFileOrder() {
        List<WeaponDefinition.DrawSelection> draws = List.of(
                firstDraw("anim.model.draw_first_a", 1.0, null),
                firstDraw("anim.model.draw_first_b", 1.1, null),
                draw("anim.model.draw", 0.87, null));

        assertEquals("anim.model.draw_first_a", EquipAnimationResolver.selectDraw(draws, 5, true).clip());
        assertEquals("anim.model.draw", EquipAnimationResolver.selectDraw(draws, 5, false).clip());
    }

    @Test
    void blankEntriesAreSkippedInsteadOfChosen() {
        List<WeaponDefinition.DrawSelection> draws = List.of(
                firstDraw("   ", 1.2, null),
                draw(null, 1.0, null),
                draw("anim.model.draw", 0.87, null));

        assertEquals("anim.model.draw", EquipAnimationResolver.selectDraw(draws, 5, true).clip());
        assertEquals("anim.model.draw", EquipAnimationResolver.selectDraw(draws, 5, false).clip());
    }

    @Test
    void noApplicableEntryMeansNoSelection() {
        EquipAnimationResolver.Selection none = EquipAnimationResolver.selectDraw(List.of(), 5, true);
        assertFalse(none.isPresent());
        assertEquals(0.0, none.durationSeconds());

        EquipAnimationResolver.Selection noMatch =
                EquipAnimationResolver.selectDraw(List.of(draw("anim.model.draw", 0.87, "ammoCount > 0")), 0, false);
        assertFalse(noMatch.isPresent(), "a weapon whose conditions all fail has nothing to draw");
        assertTrue(noMatch.emptyMagazine());
        assertFalse(EquipAnimationResolver.selectDraw((List<WeaponDefinition.DrawSelection>) null, 5, false)
                .isPresent());
    }

    // ---------------------------------------------------------------- holster

    @Test
    void holsterSelectionFollowsTheMagazineState() {
        EquipAnimationResolver.Selection loaded =
                EquipAnimationResolver.selectHolster(SHIPPED_HOLSTERS, 5);
        assertEquals("anim.model.holster", loaded.clip());
        assertEquals(0.8, loaded.durationSeconds());
        assertFalse(loaded.emptyMagazine());

        EquipAnimationResolver.Selection empty = EquipAnimationResolver.selectHolster(SHIPPED_HOLSTERS, 0);
        assertEquals("anim.model.holster_empty", empty.clip());
        assertEquals(0.7, empty.durationSeconds());
        assertTrue(empty.emptyMagazine());
        assertFalse(empty.firstDraw(), "a holster is never a first draw");
    }

    @Test
    void aWeaponWithoutHolsterEntriesHasNoHolsterClip() {
        assertFalse(EquipAnimationResolver.selectHolster(List.of(), 5).isPresent());
        assertFalse(EquipAnimationResolver.selectHolster((List<WeaponDefinition.AnimationSelection>) null, 5)
                .isPresent());
    }

    // ---------------------------------------------------------------- capability

    @Test
    void capabilityChecksLookAtBothClipKinds() {
        assertTrue(EquipAnimationResolver.handles(SHIPPED_DRAWS, SHIPPED_HOLSTERS));
        assertTrue(EquipAnimationResolver.handles(SHIPPED_DRAWS, List.of()));
        assertTrue(EquipAnimationResolver.handles(List.of(), SHIPPED_HOLSTERS));
        assertFalse(EquipAnimationResolver.handles(List.of(), List.of()),
                "a weapon with neither list keeps today's instant, silent behaviour");
        assertFalse(EquipAnimationResolver.handles(null, null));
    }

    @Test
    void theEmptyFlagNeverFiresForUnlimitedAmmunition() {
        int unlimited = AmmoCapacity.UNLIMITED_ANIMATION_COUNT;

        EquipAnimationResolver.Selection selection =
                EquipAnimationResolver.selectDraw(SHIPPED_DRAWS, unlimited, false);

        assertEquals("anim.model.draw", selection.clip());
        assertFalse(selection.emptyMagazine(), "an unlimited weapon can never be empty");
    }

    // ---------------------------------------------------------------- helpers

    private static WeaponDefinition.DrawSelection draw(String clip, double duration, String when) {
        return new WeaponDefinition.DrawSelection(clip, duration, when, false, false);
    }

    private static WeaponDefinition.DrawSelection firstDraw(String clip, double duration, String when) {
        return new WeaponDefinition.DrawSelection(clip, duration, when, false, true);
    }

    private static WeaponDefinition.AnimationSelection holster(String clip, double duration, String when) {
        return new WeaponDefinition.AnimationSelection(clip, duration, when);
    }
}
