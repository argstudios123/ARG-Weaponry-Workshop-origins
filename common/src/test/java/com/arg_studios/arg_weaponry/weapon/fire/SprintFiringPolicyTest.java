package com.arg_studios.arg_weaponry.weapon.fire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The sprint-firing rule shared by the client and the server.
 *
 * <p>The policy is a pure boolean function. All firing modes — semi-automatic, automatic, burst and the sustained
 * automatic/burst session — call the same rule through the authoritative firing validation, so a refusal happens before
 * ammunition, sound, recoil, animation or an accepted-shot packet can occur.</p>
 */
class SprintFiringPolicyTest {

    @Test
    void sprintingIsAllowedOnlyWhenTheWeaponAllowsIt() {
        assertTrue(SprintFiringPolicy.allows(false, false), "a stationary player is never restricted");
        assertTrue(SprintFiringPolicy.allows(false, true));
        assertTrue(SprintFiringPolicy.allows(true, true), "the opt-in keeps the previous behaviour");
        assertFalse(SprintFiringPolicy.allows(true, false));
    }
}
