package com.arg_studios.arg_weaponry.client.audio;

import net.minecraft.world.InteractionHand;

/**
 * The reload sound of a weapon, routed through the weapon audio engine.
 *
 * <p>Before Milestone 10R this class owned the lookup and the registry check itself. Both now live in
 * {@link WeaponActionSounds} and {@link WeaponAudio}, which serve every weapon action (fire, reload, inspect, draw,
 * holster and hit feedback) from one place, so this class is kept only as the named call site the reload path uses. It
 * adds no behaviour of its own.</p>
 */
public final class WeaponReloadSounds {

    private WeaponReloadSounds() {
    }

    /**
     * Plays the reload sound of the weapon held in one hand, if the weapon declares one.
     *
     * @param hand          the hand the reload belongs to
     * @param emptyMagazine whether the magazine was empty at reload start, which selects
     *                      {@code reload_empty} instead of {@code reload}
     */
    public static void play(InteractionHand hand, boolean emptyMagazine) {
        WeaponActionSounds.reload(hand, emptyMagazine);
    }

    /** Forgets which identifiers were already reported, so a resource reload reports them again. */
    public static void reset() {
        WeaponAudio.reset();
    }
}
