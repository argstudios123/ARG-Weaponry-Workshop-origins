package com.arg_studios.arg_weaponry.weapon.ammo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

/**
 * The Creative ammunition rule.
 *
 * <p>Every case here is about one of the three promises the feature makes: Survival is untouched, the dedicated rounds stay
 * preferred, and a Creative reload is never blocked by an empty inventory when the weapon declares the fallback.</p>
 */
class CreativeAmmunitionTest {

    private static final ResourceId CREATIVE_ITEM = new ResourceId("arg_pack", "creative_ammo");
    private static final ResourceId DMR_ITEM = new ResourceId("arg_pack", "dmr_ammo");

    @Test
    void theFallbackIsRecognisedByName() {
        Map<String, ResourceId> ammo = Map.of("creative_ammo", CREATIVE_ITEM, "dmr_ammo", DMR_ITEM);

        assertTrue(CreativeAmmunition.isFallback(AmmunitionEntries.of("creative_ammo", ammo)));
        assertFalse(CreativeAmmunition.isFallback(AmmunitionEntries.of("dmr_ammo", ammo)),
                "the dedicated rounds are never the fallback");
        assertFalse(CreativeAmmunition.isFallback(null));
    }

    @Test
    void survivalNeverGetsTheCreativeSupply() {
        assertFalse(CreativeAmmunition.grantsUnlimited(false, true),
                "a Survival player with the fallback declared still needs real rounds");
        assertEquals(0, CreativeAmmunition.reserveFor(0, 20, false),
                "and an empty inventory still refuses the reload, exactly as before this feature");
    }

    @Test
    void aCreativePlayerGetsAFullMagazineOfReserveWithoutCarryingAnything() {
        assertTrue(CreativeAmmunition.grantsUnlimited(true, true));
        assertEquals(20, CreativeAmmunition.reserveFor(0, 20, true));
    }

    @Test
    void aWeaponWithoutTheFallbackIsNotFreeInCreative() {
        assertFalse(CreativeAmmunition.grantsUnlimited(true, false),
                "the fallback must be declared in ammo_to_use, otherwise Creative changes nothing");
        assertEquals(3, CreativeAmmunition.reserveFor(3, 20, false));
    }

    @Test
    void theDedicatedRoundsAreConsumedFirstAndOnlyTheyAreConsumed() {
        // 5 dedicated rounds cover 5 of the 20 loaded; the remaining 15 come from the Creative supply.
        assertEquals(5, CreativeAmmunition.consumable(20, 5));
        assertEquals(0, CreativeAmmunition.consumable(20, 0), "an empty inventory consumes nothing at all");
        assertEquals(2, CreativeAmmunition.consumable(2, 5), "a reload never takes more than it loads");
    }

    @Test
    void aLargerRealReserveIsKept() {
        assertEquals(30, CreativeAmmunition.reserveFor(30, 20, true),
                "the validation result stays identical to Survival when the player carries more than a magazine");
    }
}
