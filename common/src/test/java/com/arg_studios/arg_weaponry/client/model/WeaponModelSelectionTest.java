package com.arg_studios.arg_weaponry.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Which item model Minecraft ends up using for a dynamically registered weapon.
 *
 * <h2>The failure this pins down</h2>
 * <p>Every weapon in a content pack that declares a {@code slot} texture <em>does</em> have a baked item model: the client
 * resource-pack bridge synthesises {@code assets/<ns>/items/<weapon>.json} and a flat {@code minecraft:item/generated} model so
 * the GUI shows the slot icon. The selection used to return "leave it alone" for exactly those ids, so the static 3D model was
 * never installed and third-person hands, dropped weapons, item frames and shelves all drew the flat icon - which is
 * indistinguishable from an invisible weapon.</p>
 *
 * <p>The decision is a pure function of "is this a registered weapon", "did anything get baked for its id" and "is the
 * placeholder available", so it can be pinned here without a client: Minecraft stays off the test classpath, exactly like the
 * other headless suites in this module.</p>
 */
class WeaponModelSelectionTest {

    private static final String WEAPON = "arg_pack:m100_dmr";
    private static final String PLACEHOLDER = "minecraft:crossbow";

    @Test
    void aWeaponWithABakedIconStillGetsTheStaticModel() {
        WeaponModelSelection.Selection selection = WeaponModelSelection.select(true, true, true);

        assertTrue(selection.installsStaticModel(),
                "the model baked for a weapon id is the GUI icon, not a reason to skip the static world model");
        assertEquals(WeaponModelSelection.Icon.OWN_MODEL, selection.icon(),
                "that baked model stays the flat icon the static model falls back to");
    }

    @Test
    void aWeaponWithoutAnyBakedModelFallsBackToThePlaceholderIcon() {
        WeaponModelSelection.Selection selection = WeaponModelSelection.select(true, false, true);

        assertTrue(selection.installsStaticModel());
        assertEquals(WeaponModelSelection.Icon.PLACEHOLDER, selection.icon());
    }

    @Test
    void aWeaponWithNoIconAtAllStillDrawsItsStaticModel() {
        WeaponModelSelection.Selection selection = WeaponModelSelection.select(true, false, false);

        assertTrue(selection.installsStaticModel(), "a missing icon must not hide the 3D model in world contexts");
        assertEquals(WeaponModelSelection.Icon.NONE, selection.icon());
        assertFalse(selection.hasIcon());
    }

    @Test
    void anIdThatIsNotAWeaponIsLeftAlone() {
        for (boolean baked : new boolean[]{true, false}) {
            WeaponModelSelection.Selection selection = WeaponModelSelection.select(false, baked, true);
            assertFalse(selection.installsStaticModel(), "modelled vanilla and modded items keep their own item model");
            assertEquals(WeaponModelSelection.Selection.NOT_A_WEAPON, selection);
        }
    }

    @Test
    void installationNeverDependsOnWhichModelWasBaked() {
        assertTrue(WeaponModelSelection.select(true, true, true).installsStaticModel());
        assertTrue(WeaponModelSelection.select(true, true, false).installsStaticModel());
        assertTrue(WeaponModelSelection.select(true, false, true).installsStaticModel());
        assertTrue(WeaponModelSelection.select(true, false, false).installsStaticModel());
    }

    @Test
    void thePlaceholderReportOnlyNamesWeaponsWithoutAModelOfTheirOwn() {
        List<String> placeholderUsers = WeaponModelSelection.idsWithoutModel(
                List.of(WEAPON, "arg_pack:ak47", "arg_pack:no_icon"), List.of(WEAPON, PLACEHOLDER));

        assertEquals(List.of("arg_pack:ak47", "arg_pack:no_icon"), placeholderUsers);
    }
}
