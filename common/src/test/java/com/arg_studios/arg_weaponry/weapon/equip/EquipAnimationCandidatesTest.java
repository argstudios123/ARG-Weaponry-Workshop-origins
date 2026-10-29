package com.arg_studios.arg_weaponry.weapon.equip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The playback candidate lists: the order the client tries clips in, and the fallback that keeps a weapon visible when the
 * animation resource is missing the variant the configuration selected.
 *
 * <p>The shipped M100 DMR is the real case these tests describe: {@code draw_anims} declares
 * {@code anim.model.draw_empty} for an empty magazine, while its animation document contains
 * {@code anim.model.draw} but not {@code anim.model.draw_empty}. The selection must still be the empty entry - the
 * configuration says so, and the sound follows it - but the client must have the general variant available to play.</p>
 */
class EquipAnimationCandidatesTest {

    private static final List<WeaponDefinition.DrawSelection> SHIPPED_DRAWS = List.of(
            firstDraw("anim.model.draw_first", 1.63, null),
            draw("anim.model.draw", 0.87, "ammoCount > 0"),
            draw("anim.model.draw_empty", 0.89, "ammoCount == 0"));

    private static final List<WeaponDefinition.AnimationSelection> SHIPPED_HOLSTERS = List.of(
            holster("anim.model.holster", 0.72, "ammoCount > 0"),
            holster("anim.model.holster_empty", 0.72, "ammoCount == 0"));

    // ---------------------------------------------------------------- draw order

    @Test
    void thePrimaryDrawCandidateIsTheSelectionItself() {
        for (int ammoCount : new int[] {0, 1, 30}) {
            EquipAnimationResolver.Selection primary =
                    EquipAnimationResolver.selectDraw(SHIPPED_DRAWS, ammoCount, false);
            List<EquipAnimationResolver.Selection> candidates =
                    EquipAnimationResolver.drawCandidates(SHIPPED_DRAWS, ammoCount, false);

            assertEquals(primary.clip(), candidates.get(0).clip(),
                    "the server's authoritative clip and the client's first attempt must be the same entry");
            assertEquals(primary.durationSeconds(), candidates.get(0).durationSeconds());
        }
    }

    @Test
    void theOtherDeclaredDrawVariantsFollowAsFallbacks() {
        List<EquipAnimationResolver.Selection> empty =
                EquipAnimationResolver.drawCandidates(SHIPPED_DRAWS, 0, false);

        assertEquals(2, empty.size(), "the empty entry, then the loaded one as the fallback");
        assertEquals("anim.model.draw_empty", empty.get(0).clip());
        assertTrue(empty.get(0).emptyMagazine());
        assertEquals("anim.model.draw", empty.get(1).clip(),
                "a pack whose animation resource lacks draw_empty can still show the general draw");
        assertTrue(empty.get(1).emptyMagazine(),
                "the fallback reports the same magazine state as the selection, which is what the sound follows");

        List<EquipAnimationResolver.Selection> loaded =
                EquipAnimationResolver.drawCandidates(SHIPPED_DRAWS, 12, false);
        assertEquals("anim.model.draw", loaded.get(0).clip());
        assertEquals("anim.model.draw_empty", loaded.get(1).clip(),
                "and the other way round on a loaded magazine");
    }

    @Test
    void theFirstDrawClipIsNeverAFallback() {
        List<EquipAnimationResolver.Selection> candidates =
                EquipAnimationResolver.drawCandidates(SHIPPED_DRAWS, 0, false);

        assertTrue(candidates.stream().noneMatch(EquipAnimationResolver.Selection::firstDrawEntry),
                "a first-draw clip must never be played as a substitute for a normal draw");
    }

    @Test
    void aFirstDrawPutsItsClipFirstAndKeepsTheFallbacksAfterIt() {
        List<EquipAnimationResolver.Selection> candidates =
                EquipAnimationResolver.drawCandidates(SHIPPED_DRAWS, 12, true);

        assertEquals("anim.model.draw_first", candidates.get(0).clip());
        assertTrue(candidates.get(0).firstDraw(), "the draw_first *sound* applies on this draw");
        assertTrue(candidates.get(0).firstDrawEntry(), "and this draw consumes the first-draw animation");
        assertTrue(candidates.size() >= 2);
        assertTrue(candidates.subList(1, candidates.size()).stream()
                        .noneMatch(EquipAnimationResolver.Selection::firstDrawEntry),
                "the ordinary clips after it are ordinary clips");
    }

    @Test
    void theFirstDrawEntryFlagIsFalseWhenNoSuchEntrySuppliedTheClip() {
        List<WeaponDefinition.DrawSelection> draws = List.of(draw("anim.model.draw", 0.87, null));

        EquipAnimationResolver.Selection selection = EquipAnimationResolver.selectDraw(draws, 5, true);

        assertEquals("anim.model.draw", selection.clip());
        assertTrue(selection.firstDraw(), "this is still the stack's first draw, so draw_first is the sound");
        assertFalse(selection.firstDrawEntry(), "but no first-draw animation was consumed");
        assertFalse(EquipAnimationResolver.hasFirstDrawEntry(draws));
    }

    @Test
    void aWeaponDeclaringAFirstDrawClipReportsIt() {
        assertTrue(EquipAnimationResolver.hasFirstDrawEntry(SHIPPED_DRAWS));
        assertFalse(EquipAnimationResolver.hasFirstDrawEntry(List.of()),
                "a weapon with no draw_anims has no first draw to consume");
        assertFalse(EquipAnimationResolver.hasFirstDrawEntry(
                (List<WeaponDefinition.DrawSelection>) null));
    }

    // ---------------------------------------------------------------- holster order

    @Test
    void holsterCandidatesPutTheEmptyVariantFirstAndKeepTheGeneralOne() {
        List<EquipAnimationResolver.Selection> empty =
                EquipAnimationResolver.holsterCandidates(SHIPPED_HOLSTERS, 0);

        assertEquals("anim.model.holster_empty", empty.get(0).clip());
        assertEquals("anim.model.holster", empty.get(1).clip(),
                "a missing holster_empty still puts the weapon away with the general clip");
        assertTrue(empty.get(1).emptyMagazine(), "the magazine state travels with the fallback as well");

        EquipAnimationResolver.Selection primary = EquipAnimationResolver.selectHolster(SHIPPED_HOLSTERS, 0);
        assertEquals(primary.clip(), empty.get(0).clip());
    }

    @Test
    void aWeaponWithoutHolsterEntriesHasNoCandidatesAtAll() {
        assertTrue(EquipAnimationResolver.holsterCandidates(List.of(), 5).isEmpty());
        assertTrue(EquipAnimationResolver.holsterCandidates(
                (List<WeaponDefinition.AnimationSelection>) null, 5).isEmpty());
        assertTrue(EquipAnimationResolver.drawCandidates(
                (List<WeaponDefinition.DrawSelection>) null, 5, true).isEmpty());
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
