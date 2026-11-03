package com.arg_studios.arg_weaponry.client.display;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The separation between the animated first-person renderer and the static item model.
 *
 * <p>This is the piece that decides, per display context, which of the two renderers owns a weapon. It is deliberately a pure
 * string test so the split can be verified without a client - and so a wrong answer cannot hide behind a rendering bug.</p>
 */
class StaticWeaponContextsTest {

    @Test
    void theWorldContextsRenderStatically() {
        assertTrue(StaticWeaponContexts.rendersStatically("thirdperson_righthand"));
        assertTrue(StaticWeaponContexts.rendersStatically("thirdperson_lefthand"));
        assertTrue(StaticWeaponContexts.rendersStatically("ground"), "a dropped item and an item entity");
        assertTrue(StaticWeaponContexts.rendersStatically("fixed"), "an item frame and a shelf-mounted item");
        assertTrue(StaticWeaponContexts.rendersStatically("on_shelf"));
        assertTrue(StaticWeaponContexts.rendersStatically("none"), "a container that draws an item without a context");
    }

    @Test
    void theFirstPersonContextsStayWithTheAnimatedRenderer() {
        assertFalse(StaticWeaponContexts.rendersStatically("firstperson_righthand"));
        assertFalse(StaticWeaponContexts.rendersStatically("firstperson_lefthand"));
        assertTrue(StaticWeaponContexts.isFirstPerson("firstperson_righthand"));
        assertTrue(StaticWeaponContexts.isFirstPerson("firstperson_lefthand"));
        assertFalse(StaticWeaponContexts.isFirstPerson("thirdperson_righthand"));
    }

    @Test
    void theGuiKeepsItsExistingRendering() {
        assertFalse(StaticWeaponContexts.rendersStatically("gui"),
                "the inventory keeps the item model the pack provides, or the built-in placeholder");
    }

    @Test
    void anUnknownContextIsNotTakenOver() {
        assertFalse(StaticWeaponContexts.rendersStatically(null));
        assertFalse(StaticWeaponContexts.rendersStatically("some_modded_context"));
        assertFalse(StaticWeaponContexts.isFirstPerson(null));
    }

    @Test
    void theTwoRenderersNeverClaimTheSameContext() {
        for (String context : StaticWeaponContexts.staticContexts()) {
            assertFalse(StaticWeaponContexts.isFirstPerson(context),
                    "the static and the animated renderer must not both draw '" + context + "'");
        }
        assertTrue(StaticWeaponContexts.staticContexts().contains("thirdperson_righthand"));
        assertTrue(StaticWeaponContexts.staticContexts().contains("thirdperson_lefthand"),
                "the two hands are separate contexts, not one shared transform");
        assertNotEquals(StaticWeaponContexts.staticContexts().size(), 0);
    }

    @Test
    void theDisplayTransformIsLookedUpPerContextAndDefaultsToIdentity() {
        WeaponDisplay display = new WeaponDisplay(new ResourceId("arg_pack", "display"),
                new ResourceId("arg_pack", "model"), new ResourceId("arg_pack", "texture"),
                new ResourceId("arg_pack", "anim"), null,
                Map.of("thirdperson_righthand", new DisplayTransform(new Vec3(0.0F, -2.75F, -2.5F), Vec3.ZERO,
                        new Vec3(0.3F, 0.3F, 0.3F))));

        assertTrue(display.transform("thirdperson_righthand").scale().x() == 0.3F);
        assertTrue(display.transform("thirdperson_lefthand").isIdentity(),
                "a context a pack does not declare is not borrowed from its sibling: the identity is the safe default");
        assertTrue(display.transform("ground").isIdentity());
        assertTrue(display.transform(null).isIdentity());
    }

    @Test
    void aHiddenContextIsRecognised() {
        WeaponDisplay display = new WeaponDisplay(new ResourceId("arg_pack", "display"),
                new ResourceId("arg_pack", "model"), new ResourceId("arg_pack", "texture"), null, null,
                Map.of("head", new DisplayTransform(Vec3.ZERO, Vec3.ZERO, new Vec3(0.0F, 0.0F, 0.0F))));

        assertTrue(StaticWeaponPlacement.isHidden(display, "head"));
        assertFalse(StaticWeaponPlacement.isHidden(display, "thirdperson_righthand"));
        assertTrue(StaticWeaponPlacement.isHidden(null, "ground"), "no display means nothing to draw");
    }
}
