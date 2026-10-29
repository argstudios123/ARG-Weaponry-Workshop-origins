package com.arg_studios.arg_weaponry.weapon.handoff;

/**
 * Everything the handoff state machine needs to know about the player, the inventory and the network, and nothing else.
 *
 * <p>This is the seam that keeps the state machine testable without a running game: the client implements it over the local
 * player, the tests implement it over a fake inventory. It is deliberately small - reading the selection, applying the
 * deferred selection once, and notifying the server - because every additional method here would be transition logic that
 * belongs in the controller instead.</p>
 */
public interface HandoffEnvironment {

    /** @return whether a transition may be started at all (a live player in a world, not a spectator). */
    boolean canTransition();

    /** @return the number of hotbar slots that may be switched to. */
    int hotbarSize();

    /** @return the slot the player's inventory currently selects. */
    int selectedSlot();

    /**
     * @param slot the hotbar slot
     * @return the identity of the weapon in that slot, or {@link WeaponIdentity#NONE} when it holds none
     */
    WeaponIdentity identityAt(int slot);

    /** @return the identity of the weapon the selected slot holds, or {@link WeaponIdentity#NONE}. */
    WeaponIdentity selectedIdentity();

    /**
     * Applies the deferred selection. Called exactly once per completed transition, and only when the transition was the
     * thing that deferred it - never to simulate a visual effect.
     *
     * @param slot the slot to select
     */
    void selectSlot(int slot);

    /**
     * Tells the server that a transition started, so it can block weapon actions for the window and let other players hear
     * the holster. The server never owns the visual timing.
     *
     * @param targetSlot the slot the player is switching to
     */
    void notifySwitchStarted(int targetSlot);

    /** Tells the server that the transition was abandoned, so its action block ends immediately. */
    void notifySwitchCancelled();

    /** @return the transition clock, in seconds; only differences are meaningful. */
    double nowSeconds();

    /**
     * @return whether a higher-priority action - a reload, a shot, an inspection - currently owns the weapon's playback, so a
     *         switch must wait for it instead of cutting it
     */
    boolean actionOwnsPlayback();
}
