package com.arg_studios.arg_weaponry.weapon.aiming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/** The ADS reload zoom envelope, its conditions and the reference's fractional {@code zoom_amount} meaning. */
class AdsReloadZoomControlTest {

    private static final double EPSILON = 1.0E-5;
    private static final WeaponDefinition.AdsReloadZoom CONFIG =
            new WeaponDefinition.AdsReloadZoom(true, "linear", 0.06);

    @Test
    void theEffectNeedsBothAReLoadAndAnAimRequest() {
        AdsReloadZoomControl control = new AdsReloadZoomControl();

        control.update(true, false, true, 0.20);
        assertEquals(0.0, control.currentProgress(), EPSILON, "a reload without ADS has no effect");
        control.update(true, true, false, 0.20);
        assertEquals(0.0, control.currentProgress(), EPSILON, "ADS is what the feature follows");
        control.update(false, true, true, 0.20);
        assertEquals(0.0, control.currentProgress(), EPSILON, "disabled is disabled");
        control.update(true, true, true, 0.20);
        assertEquals(1.0, control.currentProgress(), EPSILON);
    }

    @Test
    void theEnvelopeRampsInOverTheReferenceTransitionAndRampsOutSmoothly() {
        AdsReloadZoomControl control = new AdsReloadZoomControl();
        control.update(true, true, true, 0.10);
        assertEquals(0.5, control.currentProgress(), EPSILON, "half of the 200 ms attack");
        control.update(true, true, true, 0.10);
        assertEquals(1.0, control.currentProgress(), EPSILON);

        control.update(true, false, true, 0.10);
        assertEquals(0.5, control.currentProgress(), EPSILON, "release is a 200 ms fade, not a snap");
        control.update(true, false, true, 0.10);
        assertEquals(0.0, control.currentProgress(), EPSILON);
    }

    @Test
    void theMultiplierIsAFractionOfTheCurrentZoomNotAnAbsoluteZoom() {
        AdsReloadZoomControl control = fullEffect();

        assertEquals(0.94F, control.multiplier(1.0F, CONFIG), EPSILON,
                "0.06 removes 6 % of the aiming zoom at full effect");
        assertEquals(1.0F, control.multiplier(1.0F,
                new WeaponDefinition.AdsReloadZoom(true, "linear", 0.0)), EPSILON);
        assertEquals(0.0F, control.multiplier(1.0F,
                new WeaponDefinition.AdsReloadZoom(true, "linear", 1.0)), EPSILON);
    }

    @Test
    void aDisabledOrMissingConfigurationNeverChangesTheFieldOfView() {
        AdsReloadZoomControl control = fullEffect();

        assertEquals(1.0F, control.multiplier(1.0F, WeaponDefinition.AdsReloadZoom.DISABLED), EPSILON);
        assertEquals(1.0F, control.multiplier(1.0F, (WeaponDefinition.AdsReloadZoom) null), EPSILON);
    }

    @Test
    void everyDocumentedEasingTypeIsSupported() {
        for (String name : new String[]{"linear", "smooth", "ease_in", "ease_out", "ease_in_out", "sine_in",
                "sine_out", "sine_in_out"}) {
            WeaponDefinition.AdsReloadZoom config = new WeaponDefinition.AdsReloadZoom(true, name, 0.2);
            AdsReloadZoomControl control = fullEffect();

            assertEquals(0.8F, control.multiplier(1.0F, config), 2.0E-5, "the easing '" + name + "' must apply");
            assertTrue(config.curve() != null);
        }
    }

    @Test
    void theEffectIsIndependentOfWhetherNormalAimingIsAllowed() {
        // The only inputs are enabled/reloading/aim-requested; there is no accepted-aim flag to consult. This is what keeps
        // no_aiming_on_reload from disabling the feature.
        AdsReloadZoomControl control = new AdsReloadZoomControl();
        control.update(true, true, true, 0.20);

        assertTrue(control.active());
        assertEquals(0.94F, control.multiplier(1.0F, CONFIG), EPSILON);
    }

    @Test
    void resettingDropsTheEnvelopeImmediately() {
        AdsReloadZoomControl control = fullEffect();
        control.reset();

        assertFalse(control.active());
        assertEquals(0.0, control.currentProgress(), EPSILON);
        assertEquals(1.0F, control.multiplier(1.0F, CONFIG), EPSILON);
    }

    private static AdsReloadZoomControl fullEffect() {
        AdsReloadZoomControl control = new AdsReloadZoomControl();
        control.update(true, true, true, 0.20);
        control.update(true, true, true, 1.0F);
        return control;
    }
}
