package com.arg_studios.arg_weaponry.weapon.handoff;

/**
 * The visual weapon side of a handoff: what is being drawn, and how its animation is started, observed and ended.
 *
 * <p>One implementation exists per hand and owns the visual weapon registry; the state machine never touches a stack, a
 * model or a pose. The contract is intentionally narrow:</p>
 * <ul>
 *     <li>{@link #adopt(WeaponIdentity)} and {@link #release()} are the <em>only</em> two ways the visual weapon can change,
 *     and both are called only at the handoff boundary or on a reset - never mid-frame from a renderer;</li>
 *     <li>{@link #playHolster()} and {@link #playDraw()} report whether the weapon actually has a usable clip, so a
 *     missing animation is a decision the state machine makes, not a silently stuck weapon;</li>
 *     <li>{@link #holsterFinished()} and {@link #drawFinished()} are the only completion signals. They are polled, so a
 *     completion cannot arrive twice or after its session was superseded;</li>
 *     <li>{@link #settleDraw()} returns the incoming weapon to its idle playback using that weapon's own animator.</li>
 * </ul>
 * <p>Every method is called from the client tick thread only.</p>
 */
public interface HandoffVisuals {

    /** @return the identity of the weapon currently drawn, or {@link WeaponIdentity#NONE} when the live hand is drawn. */
    WeaponIdentity activeIdentity();

    /**
     * Retires the outgoing visual weapon and makes the given one active, with its own animator, before the next frame is
     * rendered. {@link WeaponIdentity#NONE} releases the visual weapon entirely, so rendering falls back to the live hand.
     *
     * @param identity the weapon that becomes the visual weapon
     */
    void adopt(WeaponIdentity identity);

    /** Drops the visual weapon; rendering falls back to the live hand. */
    void release();

    /**
     * Starts the active weapon's holster clip from its first frame.
     *
     * @return the authored length of the clip that started, or {@code 0} when the weapon declares no holster clip that the
     *         animation resource can provide
     */
    double playHolster();

    /** @return {@code true} when the running holster clip has reached its end. */
    boolean holsterFinished();

    /** Stops the running holster clip and returns the weapon to idle playback, without changing the visual weapon. */
    void stopHolster();

    /**
     * Starts the active weapon's draw clip from its first frame. The weapon must have been adopted before this call, so its
     * animator is brand new and has nothing on screen to fade from: the draw starts at its authored first frame.
     *
     * @return the authored length of the clip that started, or {@code 0} when the weapon declares no draw clip that the
     *         animation resource can provide
     */
    double playDraw();

    /** @return {@code true} when the running draw clip has reached its end. */
    boolean drawFinished();

    /** Ends the draw clip and continues the weapon's idle playback. */
    void settleDraw();
}
