package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The three weapon sway styles: parsing, and the measurable difference between them.
 *
 * <p>The styles are compared through {@link SwayState}, so the test pins the feel the feature promises rather than the
 * constants: {@code rotatory} turns further than {@code vanilla} for the same movement, the inertial styles keep the weapon
 * moving after the camera stops, and {@code type_1} lags behind the camera and settles more slowly and more heavily.</p>
 */
class WeaponSwayStyleTest {

    private static final double FRAME = 1.0 / 60.0;
    private static final double FLICK = Math.toRadians(2.0);

    @Test
    void theDocumentedNamesResolveAndUnknownOnesFallBackToVanilla() {
        assertEquals(WeaponSwayStyle.VANILLA, WeaponSwayStyle.parse("vanilla"));
        assertEquals(WeaponSwayStyle.ROTATORY, WeaponSwayStyle.parse("rotatory"));
        assertEquals(WeaponSwayStyle.TYPE_1, WeaponSwayStyle.parse("type_1"));
        assertEquals(WeaponSwayStyle.TYPE_1, WeaponSwayStyle.parse("Type 1"), "separators and case are ignored");
        assertEquals(WeaponSwayStyle.VANILLA, WeaponSwayStyle.parse("default"),
                "the shipped pack still writes the pre-style name");
        assertEquals(WeaponSwayStyle.VANILLA, WeaponSwayStyle.parse("nonsense"));
        assertEquals(WeaponSwayStyle.VANILLA, WeaponSwayStyle.parse(null));
        assertNull(WeaponSwayStyle.lookup("nonsense"));
    }

    @Test
    void rotatoryTurnsTheWeaponFurtherThanVanilla() {
        WeaponDefinition.Sway vanillaSettings = style(WeaponSwayStyle.VANILLA);
        WeaponDefinition.Sway rotatorySettings = style(WeaponSwayStyle.ROTATORY);
        SwayState vanilla = state();
        SwayState rotatory = state();

        for (int frame = 0; frame < 20; frame++) {
            vanilla.update(FLICK, 0.0, FRAME, vanillaSettings, 1.0);
            rotatory.update(FLICK, 0.0, FRAME, rotatorySettings, 1.0);
        }

        assertTrue(Math.abs(rotatory.rotationY()) > Math.abs(vanilla.rotationY()) * 1.5,
                "the rotatory style is what makes horizontal camera movement read as rotation");
        assertTrue(Math.abs(rotatory.rotationZ()) > Math.abs(vanilla.rotationZ()),
                "and it rolls noticeably more");
    }

    @Test
    void theInertialStylesKeepMovingAfterTheCameraStops() {
        WeaponDefinition.Sway vanillaSettings = style(WeaponSwayStyle.VANILLA);
        WeaponDefinition.Sway rotatorySettings = style(WeaponSwayStyle.ROTATORY);
        SwayState vanilla = state();
        SwayState rotatory = state();

        // One hard flick, then the mouse stops: vanilla reacts on that frame and decays from there, the spring keeps going.
        vanilla.update(Math.toRadians(200.0), 0.0, FRAME, vanillaSettings, 1.0);
        rotatory.update(Math.toRadians(200.0), 0.0, FRAME, rotatorySettings, 1.0);
        double vanillaPeak = Math.abs(vanilla.rotationY());
        double rotatoryPeak = Math.abs(rotatory.rotationY());
        for (int frame = 0; frame < 3; frame++) {
            vanilla.update(0.0, 0.0, FRAME, vanillaSettings, 1.0);
            rotatory.update(0.0, 0.0, FRAME, rotatorySettings, 1.0);
        }

        assertTrue(Math.abs(vanilla.rotationY()) < vanillaPeak,
                "the vanilla style simply follows the camera and stops with it");
        assertTrue(Math.abs(rotatory.rotationY()) > rotatoryPeak,
                "the rotatory style carries inertia for a few frames, which is the lag the player sees");
    }

    @Test
    void type1LagsBehindTheCameraAndMovesTheWeaponFurtherPerDegree() {
        WeaponDefinition.Sway vanillaSettings = style(WeaponSwayStyle.VANILLA);
        WeaponDefinition.Sway type1Settings = style(WeaponSwayStyle.TYPE_1);
        SwayState vanilla = state();
        SwayState type1 = state();

        vanilla.update(FLICK, 0.0, FRAME, vanillaSettings, 1.0);
        type1.update(FLICK, 0.0, FRAME, type1Settings, 1.0);
        assertTrue(Math.abs(type1.rotationY()) < Math.abs(vanilla.rotationY()),
                "type_1 answers a single frame of movement more slowly - the weapon trails the camera");

        for (int frame = 0; frame < 120; frame++) {
            vanilla.update(FLICK, 0.0, FRAME, vanillaSettings, 1.0);
            type1.update(FLICK, 0.0, FRAME, type1Settings, 1.0);
        }
        assertTrue(Math.abs(type1.positionX() / type1.rotationY())
                        > Math.abs(vanilla.positionX() / vanilla.rotationY()),
                "type_1 is the physically heavier style: more translation for the same rotation");
    }

    @Test
    void everyStyleSettlesBackToRest() {
        for (WeaponSwayStyle style : WeaponSwayStyle.values()) {
            WeaponDefinition.Sway settings = style(style);
            SwayState sway = state();
            for (int frame = 0; frame < 30; frame++) {
                sway.update(FLICK, FLICK, FRAME, settings, 1.0);
            }
            assertNotEquals(0.0, sway.rotationY(), "the weapon is off-centre while the mouse moves");

            for (int frame = 0; frame < 600; frame++) {
                sway.update(0.0, 0.0, FRAME, settings, 1.0);
            }
            assertTrue(sway.atRest(), style.canonicalName() + " must settle to exactly zero");
            assertEquals(0.0, sway.rotationY(), 0.0);
        }
    }

    @Test
    void everyStyleIsFrameRateIndependent() {
        for (WeaponSwayStyle style : WeaponSwayStyle.values()) {
            WeaponDefinition.Sway settings = style(style);
            SwayState slow = state();
            SwayState fast = state();
            for (int frame = 0; frame < 15; frame++) {
                slow.update(Math.toRadians(1.0), 0.0, 1.0 / 60.0, settings, 1.0);
            }
            for (int frame = 0; frame < 60; frame++) {
                fast.update(Math.toRadians(0.25), 0.0, 1.0 / 240.0, settings, 1.0);
            }
            assertEquals(slow.rotationY(), fast.rotationY(), 0.05,
                    style.canonicalName() + " must feel the same at 60 and 240 frames per second");
        }
    }

    private static SwayState state() {
        return new SwayState();
    }

    private static WeaponDefinition.Sway style(WeaponSwayStyle style) {
        return new WeaponDefinition.Sway(true, style, 1.0, 5.0, 3.0, 6.0, 0.3);
    }
}
