package com.arg_studios.arg_weaponry.client.input;

import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireSessions;

import java.util.Optional;

/**
 * The pure state machine behind the automatic/burst trigger: what to send, and when.
 *
 * <p>Deliberately free of Minecraft types, because the interesting behaviour - one press is one start packet, a held
 * trigger heartbeats instead of spamming, a session that ended cannot be reopened by the same held button - is exactly
 * the part that must be verifiable without a running client. {@link WeaponFireInput} is the thin adapter around it: it
 * resolves the player, the held weapon and its mode, and turns the {@link Packet}s this class returns into
 * {@code WeaponTriggerPayload}s.</p>
 *
 * <h2>Rules encoded here</h2>
 * <ul>
 *   <li>{@link #press()} allocates one sequence number per press and returns the start packet; while a press is already
 *   tracked it returns nothing, so a repeated callback can never open a second session;</li>
 *   <li>{@link #heartbeat()} returns a packet with the <em>same</em> sequence exactly once every
 *   {@link WeaponFireSessions#HEARTBEAT_TICKS} calls, which is what keeps the server-side lease alive without a packet
 *   per tick;</li>
 *   <li>{@link #release()} returns the stop packet once and forgets the press;</li>
 *   <li>{@link #onSessionUpdate(boolean)} marks the press as finished when the server reports no running session; from
 *   then on nothing is sent until the button is released and pressed again;</li>
 *   <li>{@link #suppress()} does the same for a reload, which owns the weapon.</li>
 * </ul>
 */
public final class TriggerState {

    /** One packet the client should send. */
    public record Packet(boolean pressed, int sequence) {
    }

    private int nextSequence;
    private int sequence;
    private int ticksSincePacket;
    private boolean held;
    private boolean sessionActive;
    private boolean suppressedUntilRelease;

    /**
     * Starts a press.
     *
     * @return the start packet, or empty when a press is already tracked
     */
    public Optional<Packet> press() {
        if (held) {
            return Optional.empty();
        }
        held = true;
        sequence = ++nextSequence;
        ticksSincePacket = 0;
        suppressedUntilRelease = false;
        sessionActive = true;
        return Optional.of(new Packet(true, sequence));
    }

    /**
     * Advances one client tick of a held trigger.
     *
     * @return a heartbeat packet when one is due, otherwise empty
     */
    public Optional<Packet> heartbeat() {
        if (!held || suppressedUntilRelease) {
            return Optional.empty();
        }
        if (++ticksSincePacket < WeaponFireSessions.HEARTBEAT_TICKS) {
            return Optional.empty();
        }
        ticksSincePacket = 0;
        if (!sessionActive) {
            // The server has already told us this press is over; nothing left to keep alive.
            return Optional.empty();
        }
        return Optional.of(new Packet(true, sequence));
    }

    /**
     * Ends the tracked press.
     *
     * @return the release packet when the server may still hold a session for it, otherwise empty
     */
    public Optional<Packet> release() {
        if (!held) {
            return Optional.empty();
        }
        boolean send = sessionActive && !suppressedUntilRelease;
        int releasedSequence = sequence;
        clear();
        return send ? Optional.of(new Packet(false, releasedSequence)) : Optional.empty();
    }

    /**
     * Applies the server's answer about the tracked session.
     *
     * @param active whether a session is running afterwards
     */
    public void onSessionUpdate(boolean active) {
        if (!held) {
            return;
        }
        sessionActive = active;
        if (!active) {
            suppressedUntilRelease = true;
        }
    }

    /** Marks the press as finished from the server side, for example because a reload started. */
    public void suppress() {
        if (held) {
            suppressedUntilRelease = true;
            sessionActive = false;
        }
    }

    /** @return whether a press is currently tracked */
    public boolean isHeld() {
        return held;
    }

    /** @return the sequence of the tracked press, or {@code 0} */
    public int sequence() {
        return sequence;
    }

    /** @return whether the server last reported a running session for the tracked press */
    public boolean isSessionActive() {
        return sessionActive;
    }

    /** @return whether the tracked press can no longer open a session until the button is released */
    public boolean isSuppressed() {
        return suppressedUntilRelease;
    }

    /** Forgets everything; the sequence counter keeps increasing. */
    public void reset() {
        clear();
    }

    private void clear() {
        held = false;
        sequence = 0;
        ticksSincePacket = 0;
        sessionActive = false;
        suppressedUntilRelease = false;
    }
}
