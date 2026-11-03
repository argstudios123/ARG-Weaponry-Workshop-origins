package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The sway spring.
 *
 * <p>These cases pin the behaviour the feature promises: a mouse movement produces a rotation and a translation, the weapon
 * returns to rest when the mouse stops, aiming scales the effect, {@code enabled: false} turns it off entirely, and the
 * result does not depend on the frame rate.</p>
 */
class SwayStateTest {

    private static final double FRAME = 1.0 / 60.0;
    private static final WeaponDefinition.Sway DEFAULTS = WeaponDefinition.Sway.DEFAULT;

    /** One degree of camera movement per frame, i.e. a steady 60 degrees per second at 60 frames per second. */
    private static final double FLICK = Math.toRadians(1.0);

    @Test
    void aHorizontalMouseMovementProducesARotationAndATranslation() {
        SwayState sway = new SwayState();

        sway.update(FLICK, 0.0, FRAME, DEFAULTS, 1.0);

        assertTrue(Math.abs(sway.rotationY()) > 0.1, "the weapon lags behind a horizontal flick");
        assertTrue(Math.abs(sway.positionX()) > 0.0, "and moves a little while it does");
        assertEquals(0.0, sway.rotationX(), 1.0E-9, "a horizontal movement does not tilt the weapon vertically");
    }

    @Test
    void turningRightAndTurningLeftMirrorEachOther() {
        SwayState right = new SwayState();
        SwayState left = new SwayState();

        for (int frame = 0; frame < 10; frame++) {
            right.update(FLICK, 0.0, FRAME, DEFAULTS, 1.0);
            left.update(-FLICK, 0.0, FRAME, DEFAULTS, 1.0);
        }

        assertEquals(-right.rotationY(), left.rotationY(), 1.0E-9);
        assertEquals(-right.positionX(), left.positionX(), 1.0E-9);
    }

    @Test
    void aVerticalMouseMovementTiltsTheWeapon() {
        SwayState sway = new SwayState();

        for (int frame = 0; frame < 10; frame++) {
            sway.update(0.0, FLICK, FRAME, DEFAULTS, 1.0);
        }

        assertTrue(Math.abs(sway.rotationX()) > 0.1);
        assertTrue(Math.abs(sway.positionY()) > 0.0);
        assertEquals(0.0, sway.rotationY(), 1.0E-9);
    }

    @Test
    void theWeaponReturnsToRestWhenTheMouseStops() {
        SwayState sway = new SwayState();
        for (int frame = 0; frame < 10; frame++) {
            sway.update(FLICK, FLICK, FRAME, DEFAULTS, 1.0);
        }
        assertNotEquals(0.0, sway.rotationY(), "the weapon is off-centre while the mouse moves");

        for (int frame = 0; frame < 120; frame++) {
            sway.update(0.0, 0.0, FRAME, DEFAULTS, 1.0);
        }

        assertTrue(Math.abs(sway.rotationY()) < 0.05, "two seconds later it is back at rest");
        assertTrue(Math.abs(sway.rotationX()) < 0.05);
    }

    @Test
    void aimingScalesTheEffectByTheConfiguredMultiplier() {
        SwayState hipFire = new SwayState();
        SwayState aiming = new SwayState();

        for (int frame = 0; frame < 10; frame++) {
            hipFire.update(FLICK, 0.0, FRAME, DEFAULTS, 1.0);
            aiming.update(FLICK, 0.0, FRAME, DEFAULTS, DEFAULTS.aimMultiplier());
        }

        assertEquals(hipFire.rotationY() * DEFAULTS.aimMultiplier(), aiming.rotationY(), 1.0E-6,
                "aim_multiplier 0.3 keeps exactly 30% of the sway");
    }

    @Test
    void disablingTheEffectLeavesTheWeaponAtRest() {
        WeaponDefinition.Sway disabled = new WeaponDefinition.Sway(false, 1.0, 5.0, 5.0, 0.3);
        SwayState sway = new SwayState();

        for (int frame = 0; frame < 10; frame++) {
            sway.update(FLICK, FLICK, FRAME, disabled, 1.0);
        }

        assertTrue(sway.atRest());
        assertEquals(0.0, sway.positionX(), 1.0E-9);
    }

    @Test
    void intensityScalesTheEffect() {
        SwayState normal = new SwayState();
        SwayState strong = new SwayState();
        WeaponDefinition.Sway doubled = new WeaponDefinition.Sway(true, 2.0, 5.0, 5.0, 0.3);

        for (int frame = 0; frame < 10; frame++) {
            normal.update(FLICK, 0.0, FRAME, DEFAULTS, 1.0);
            strong.update(FLICK, 0.0, FRAME, doubled, 1.0);
        }

        assertEquals(normal.rotationY() * 2.0, strong.rotationY(), 1.0E-6);
    }

    @Test
    void theSameSettingsProduceTheSameResultAtSixtyAndTwoHundredAndFortyFrames() {
        SwayState slow = new SwayState();
        SwayState fast = new SwayState();

        // A quarter second of the same camera movement (60 degrees per second), integrated at two frame rates.
        double degreesPerSecond = 60.0;
        for (int frame = 0; frame < 15; frame++) {
            slow.update(Math.toRadians(degreesPerSecond / 60.0), 0.0, 1.0 / 60.0, DEFAULTS, 1.0);
        }
        for (int frame = 0; frame < 60; frame++) {
            fast.update(Math.toRadians(degreesPerSecond / 240.0), 0.0, 1.0 / 240.0, DEFAULTS, 1.0);
        }

        assertEquals(slow.rotationY(), fast.rotationY(), 1.0E-3,
                "the spring is integrated with an exponential step, so the frame rate does not change the result");
    }

    @Test
    void anEnormousFlickIsClampedInsteadOfThrowingTheWeaponOffScreen() {
        SwayState sway = new SwayState();

        for (int frame = 0; frame < 60; frame++) {
            sway.update(Math.toRadians(720.0), 0.0, FRAME, DEFAULTS, 1.0);
        }

        double ceiling = DEFAULTS.style().maxRotationDegrees();
        assertEquals(ceiling, Math.abs(sway.rotationY()), 0.05,
                "the style's own ceiling is what limits a saturated sway");
        assertTrue(Math.abs(sway.rotationY()) <= SwayState.MAX_ROTATION_DEGREES);
    }

    @Test
    void sensitivityIsTheInputGainAndIntensityTheOutputAmplitude() {
        SwayState sensitive = new SwayState();
        SwayState insensitive = new SwayState();
        WeaponDefinition.Sway lowSensitivity = new WeaponDefinition.Sway(true, WeaponSwayStyle.VANILLA, 1.0, 5.0, 0.5,
                6.0, 0.3);
        WeaponDefinition.Sway highSensitivity = new WeaponDefinition.Sway(true, WeaponSwayStyle.VANILLA, 1.0, 5.0, 10.0,
                6.0, 0.3);

        for (int frame = 0; frame < 20; frame++) {
            sensitive.update(Math.toRadians(2.0), 0.0, FRAME, highSensitivity, 1.0);
            insensitive.update(Math.toRadians(2.0), 0.0, FRAME, lowSensitivity, 1.0);
        }

        assertTrue(Math.abs(sensitive.rotationY()) > Math.abs(insensitive.rotationY()),
                "the same camera movement produces more sway at a higher sensitivity");

        SwayState full = new SwayState();
        SwayState half = new SwayState();
        WeaponDefinition.Sway halfIntensity = new WeaponDefinition.Sway(true, WeaponSwayStyle.VANILLA, 0.5, 5.0, 10.0,
                6.0, 0.3);
        for (int frame = 0; frame < 20; frame++) {
            full.update(Math.toRadians(2.0), 0.0, FRAME, highSensitivity, 1.0);
            half.update(Math.toRadians(2.0), 0.0, FRAME, halfIntensity, 1.0);
        }

        assertEquals(full.rotationY() * 0.5, half.rotationY(), 1.0E-9,
                "intensity scales the output exactly, without changing the spring");
    }

    @Test
    void aimingWithAMultiplierAboveOneMakesTheSwayHeavier() {
        SwayState hipFire = new SwayState();
        SwayState aiming = new SwayState();
        double smallFlick = Math.toRadians(0.2);

        for (int frame = 0; frame < 20; frame++) {
            hipFire.update(smallFlick, 0.0, FRAME, DEFAULTS, 1.0);
            aiming.update(smallFlick, 0.0, FRAME, DEFAULTS, 2.9);
        }

        assertEquals(hipFire.rotationY() * 2.9, aiming.rotationY(), 1.0E-6);
    }

    @Test
    void anUnknownStyleNameNeverDisablesSway() {
        SwayState sway = new SwayState();
        WeaponDefinition.Sway unknown = new WeaponDefinition.Sway(true, WeaponSwayStyle.parse("nonsense"), 1.0, 5.0,
                3.0, 6.0, 0.3);

        for (int frame = 0; frame < 10; frame++) {
            sway.update(FLICK, 0.0, FRAME, unknown, 1.0);
        }

        assertEquals(WeaponSwayStyle.VANILLA, sway.style());
        assertTrue(Math.abs(sway.rotationY()) > 0.0);
    }

    @Test
    void theRotatoryStyleAnswersOnTheSameFrameWithYawPitchAndRoll() {
        WeaponDefinition.Sway rotatory = new WeaponDefinition.Sway(true, WeaponSwayStyle.ROTATORY, 0.2, 3.0, 3.0,
                6.0, 0.25);
        SwayState sway = new SwayState();

        sway.update(Math.toRadians(2.0), Math.toRadians(-1.0), FRAME, rotatory, 1.0);

        assertTrue(Math.abs(sway.rotationY()) > 0.0, "horizontal movement rotates the master transform immediately");
        assertTrue(Math.abs(sway.rotationX()) > 0.0, "vertical movement tilts it immediately");
        assertTrue(Math.abs(sway.rotationZ()) > 0.0, "the rotatory style rolls with horizontal movement");
    }

    @Test
    void aZeroAdsMultiplierRemovesTheSwayWithoutBreakingTheSpring() {
        WeaponDefinition.Sway zeroAim = new WeaponDefinition.Sway(true, WeaponSwayStyle.ROTATORY, 0.2, 3.0, 3.0,
                6.0, 0.0);
        SwayState sway = new SwayState();
        for (int frame = 0; frame < 20; frame++) {
            sway.update(FLICK, FLICK, FRAME, zeroAim, 0.0);
        }

        assertEquals(0.0, sway.rotationY(), 1.0E-12);
        assertEquals(0.0, sway.rotationX(), 1.0E-12);
        assertEquals(0.0, sway.rotationZ(), 1.0E-12);
        assertEquals(0.0, sway.positionX(), 1.0E-12);
    }

    @Test
    void aZeroLengthFrameChangesNothing() {
        SwayState sway = new SwayState();

        sway.update(FLICK, FLICK, 0.0, DEFAULTS, 1.0);

        assertTrue(sway.atRest(), "the second sample of one frame must not be integrated");
    }
}
