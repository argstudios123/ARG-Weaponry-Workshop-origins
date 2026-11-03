package com.arg_studios.arg_weaponry.client.animation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.WeaponType;
import com.arg_studios.arg_weaponry.weapon.equip.EquipAnimationResolver;

/**
 * The first-draw sound must follow the clip that actually played.
 *
 * <p>This is the rule that keeps a first draw coherent: the sound and the animation are one action, so a weapon whose
 * first-draw clip cannot be resolved plays the ordinary draw clip <em>and</em> the ordinary draw sound, instead of the
 * reported "the first-draw sound plays, but the animation does not".</p>
 */
class ClientHandoffVisualsFirstDrawTest {

    @Test
    void aFirstDrawClipThatPlayedKeepsTheFirstDrawSound() {
        assertTrue(ClientHandoffVisuals.firstDrawSound(definitionWithFirstDraw(), selection("anim.test.draw_first", true,
                true)), "clip and sound belong to the same first draw");
    }

    @Test
    void aFallbackDrawClipUsesTheOrdinarySound() {
        assertFalse(ClientHandoffVisuals.firstDrawSound(definitionWithFirstDraw(), selection("anim.test.draw", true,
                false)), "the first-draw sound must not lie about a clip that did not play");
    }

    @Test
    void aWeaponWithoutAFirstDrawClipKeepsItsSoundOnlyFirstDraw() {
        assertTrue(ClientHandoffVisuals.firstDrawSound(definitionWithoutFirstDraw(), selection("anim.test.draw", true,
                false)), "a pack may ship a first-draw sound without a first-draw clip");
    }

    @Test
    void anOrdinaryDrawNeverUsesTheFirstDrawSound() {
        assertFalse(ClientHandoffVisuals.firstDrawSound(definitionWithoutFirstDraw(), selection("anim.test.draw", false,
                false)));
        assertFalse(ClientHandoffVisuals.firstDrawSound(null, null));
    }

    // ---------------------------------------------------------------- helpers

    private static EquipAnimationResolver.Selection selection(String clip, boolean firstDraw, boolean firstDrawEntry) {
        return new EquipAnimationResolver.Selection(clip, 1.0, firstDraw, false, List.of(), firstDrawEntry);
    }

    private static WeaponDefinition definitionWithFirstDraw() {
        return definition(List.of(new WeaponDefinition.DrawSelection("anim.test.draw_first", 1.0, null, true, true),
                new WeaponDefinition.DrawSelection("anim.test.draw", 1.0, "ammoCount > 0", false, false)));
    }

    private static WeaponDefinition definitionWithoutFirstDraw() {
        return definition(List.of(new WeaponDefinition.DrawSelection("anim.test.draw", 1.0, "ammoCount > 0", false,
                false)));
    }

    private static WeaponDefinition definition(List<WeaponDefinition.DrawSelection> drawAnimations) {
        return new WeaponDefinition(
                new ResourceId("arg_pack", "test/weapon"),
                new ResourceId("arg_pack", "test_weapon"),
                "arg_pack:test_weapon",
                "test_weapon",
                WeaponType.GUN,
                new ResourceId("arg_pack", "test/weapon"),
                List.of(),
                1.0,
                null,
                List.of(),
                1,
                false,
                WeaponDefinition.DEFAULT_TIME_BETWEEN_BURSTS,
                600,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                java.util.Map.of(),
                java.util.Map.of(),
                null,
                null,
                List.of(new WeaponDefinition.AnimationSelection("anim.test.idle", 2.0, null)),
                List.of(new WeaponDefinition.AnimationSelection("anim.test.holster", 0.74, null)),
                drawAnimations,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);
    }
}
