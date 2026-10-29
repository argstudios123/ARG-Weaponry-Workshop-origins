package com.arg_studios.arg_weaponry.weapon.equip;

/**
 * The three states of the draw/holster state machine.
 *
 * <pre>
 *   IDLE --(weapon becomes held)------------------&gt; DRAWING --(clip finished)--&gt; IDLE
 *   IDLE --(player switches away, holster clip)---&gt; HOLSTERING --(slot applied)--&gt; DRAWING / IDLE
 *   DRAWING --(player switches away)--------------&gt; HOLSTERING (or straight to the new DRAWING)
 * </pre>
 *
 * <p>{@link #DRAWING} and {@link #HOLSTERING} are the two states in which weapon actions are blocked; {@link #IDLE} means
 * "nothing was intercepted", which is also the state of every weapon the feature does not handle.</p>
 */
public enum EquipState {

    /** No draw or holster is running: actions are unrestricted and no slot change is intercepted. */
    IDLE,

    /** The weapon that just became held is playing its draw clip; actions are blocked until it ends. */
    DRAWING,

    /** A player-initiated slot switch is waiting for the holster clip to end; actions are blocked. */
    HOLSTERING;

    /**
     * @return whether weapon actions (fire, reload, aim, inspect, fire-mode) must be refused in this state
     */
    public boolean isBusy() {
        return this != IDLE;
    }
}
