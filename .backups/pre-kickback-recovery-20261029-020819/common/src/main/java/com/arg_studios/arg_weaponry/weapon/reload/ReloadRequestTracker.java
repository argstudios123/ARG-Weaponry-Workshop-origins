package com.arg_studios.arg_weaponry.weapon.reload;

import net.minecraft.world.InteractionHand;

import java.util.EnumMap;
import java.util.Map;

/**
 * The client-side record of one reload request and of its outcome.
 *
 * <p>Milestone 10M kept this as a single "last sequence handled" number per hand and dropped every outcome whose
 * sequence was not strictly greater than the last one. That looks like replay protection but it is not: the
 * <em>start</em> and the <em>end</em> of one reload carry the same sequence number, so the completion of the very
 * first reload was discarded as a duplicate, the in-flight flag was never cleared, and the client refused to send
 * another reload request for the rest of the session. Reloading therefore worked exactly once. This class is the
 * corrected state machine, extracted so the lifecycle can be verified without a running client.</p>
 *
 * <h2>Sequences</h2>
 * <p>The server's rejection rules are unchanged: a request sequence must increase for a hand or the server treats
 * it as a replay. The client mirrors that, but it tracks three separate facts instead of one:</p>
 * <ul>
 *     <li>{@code lastStarted} - the highest sequence whose start was applied to this hand,</li>
 *     <li>{@code lastEnded} - the highest sequence whose end was already applied,</li>
 *     <li>the request that is <em>in flight</em>: the hand and the exact sequence the server accepted.</li>
 * </ul>
 * <p>A start is applied when it is newer than every start already applied. An end is applied when it names the
 * in-flight request, or when it is newer than every end already applied - a terminal outcome is idempotent and
 * must never be lost, because losing it is what stranded the player. A refusal is applied when it is not older
 * than the in-flight request, so a stale refusal cannot cancel a reload that started afterwards.</p>
 *
 * <h2>Never permanently blocked</h2>
 * <p>{@link #inFlight(InteractionHand)} is what the input path checks before sending a request. The in-flight
 * record carries a deadline derived from the accepted reload duration, and {@link #expire(long)} drops it once
 * that deadline has passed, so a terminal payload that never arrives can delay the next reload but can never
 * forbid it. The clock is passed in rather than read, which keeps this class pure and testable.</p>
 *
 * <h2>Threading</h2>
 * <p>Every method is {@code synchronized}: outcomes arrive on the client thread from the networking layer, the
 * input path runs on that same thread, and the render path only reads {@link #activeHand()}.</p>
 */
public final class ReloadRequestTracker {

    /** How long an in-flight reload may outlive its declared duration before it is dropped. */
    public static final long EXPIRY_GRACE_MILLIS = 10_000L;

    /** What an outcome says about the request it answers. */
    public enum Kind {

        /** The server accepted the request and a session is running. */
        STARTED,

        /** The session finished or was cancelled. */
        ENDED,

        /** The request was refused before any session started. */
        REFUSED
    }

    /** What the client should do with an outcome. */
    public enum Decision {

        /** Apply it: a reload started, so animate it. */
        APPLY_START,

        /** Apply it: the reload ended, so clean up and restore the idle pose. */
        APPLY_END,

        /** Apply it: report the refusal, change nothing else. */
        APPLY_REFUSAL,

        /** Ignore it: it is a replay, a duplicate or a stale outcome. */
        IGNORE
    }

    private final Map<InteractionHand, Integer> lastStarted = new EnumMap<>(InteractionHand.class);
    private final Map<InteractionHand, Integer> lastEnded = new EnumMap<>(InteractionHand.class);

    private InteractionHand inFlightHand;
    private int inFlightSequence;
    private long inFlightDeadlineMillis;

    /**
     * Applies one outcome to the state machine.
     *
     * @param hand      the hand named by the outcome
     * @param sequence  the request sequence it answers
     * @param kind      what the outcome says
     * @param nowMillis the current client clock in milliseconds
     * @return what the caller should do with the outcome
     */
    public synchronized Decision accept(InteractionHand hand, int sequence, Kind kind, long nowMillis) {
        expire(nowMillis);
        if (hand == null || sequence < 1 || kind == null) {
            return Decision.IGNORE;
        }

        switch (kind) {
            case STARTED -> {
                int previous = lastStarted.getOrDefault(hand, 0);
                if (sequence <= previous) {
                    return Decision.IGNORE;
                }
                lastStarted.put(hand, sequence);
                inFlightHand = hand;
                inFlightSequence = sequence;
                return Decision.APPLY_START;
            }
            case ENDED -> {
                boolean matchesInFlight = inFlightHand == hand && sequence >= inFlightSequence;
                int previousEnd = lastEnded.getOrDefault(hand, 0);
                if (!matchesInFlight && sequence <= previousEnd) {
                    return Decision.IGNORE;
                }
                if (matchesInFlight) {
                    clearInFlight();
                }
                lastEnded.put(hand, Math.max(previousEnd, sequence));
                return Decision.APPLY_END;
            }
            case REFUSED -> {
                boolean namesInFlight = inFlightHand == hand && sequence >= inFlightSequence;
                boolean newerThanStart = sequence > lastStarted.getOrDefault(hand, 0);
                return namesInFlight || newerThanStart ? Decision.APPLY_REFUSAL : Decision.IGNORE;
            }
            default -> {
                return Decision.IGNORE;
            }
        }
    }

    /**
     * Arms the expiry of the request that is currently in flight.
     *
     * <p>Called right after a start is applied, with the duration the server reported. A non-positive duration
     * leaves the deadline unset, so {@link #expire(long)} never drops the request on its own.</p>
     *
     * @param durationMillis the accepted reload duration, or a non-positive value for "unknown"
     * @param nowMillis      the current client clock in milliseconds
     */
    public synchronized void armExpiry(long durationMillis, long nowMillis) {
        if (inFlightHand == null || durationMillis <= 0L) {
            inFlightDeadlineMillis = 0L;
            return;
        }
        inFlightDeadlineMillis = nowMillis + durationMillis + EXPIRY_GRACE_MILLIS;
    }

    /**
     * Drops an in-flight request whose deadline has passed, so a lost outcome cannot block reloading forever.
     *
     * @param nowMillis the current client clock in milliseconds
     * @return {@code true} when a stale request was dropped
     */
    public synchronized boolean expire(long nowMillis) {
        if (inFlightHand == null || inFlightDeadlineMillis <= 0L || nowMillis < inFlightDeadlineMillis) {
            return false;
        }
        clearInFlight();
        return true;
    }

    /** @return the hand with an accepted reload in flight, or {@code null} when a new request may be sent. */
    public synchronized InteractionHand activeHand() {
        return inFlightHand;
    }

    /**
     * @param hand the hand to test
     * @return {@code true} when that hand already has a request in flight
     */
    public synchronized boolean inFlight(InteractionHand hand) {
        return hand != null && inFlightHand == hand;
    }

    /** @return the sequence of the request in flight, or {@code 0} when none is. */
    public synchronized int inFlightSequence() {
        return inFlightSequence;
    }

    /** @return the highest sequence this hand was started with, {@code 0} when none. */
    public synchronized int lastStarted(InteractionHand hand) {
        return hand == null ? 0 : lastStarted.getOrDefault(hand, 0);
    }

    /** @return the highest sequence this hand had an end applied for, {@code 0} when none. */
    public synchronized int lastEnded(InteractionHand hand) {
        return hand == null ? 0 : lastEnded.getOrDefault(hand, 0);
    }

    /**
     * Forgets every record; called when the client leaves a world.
     *
     * <p>The caller keeps its request counter running, because the server clears its own per-connection sequence
     * record on disconnect and a counter that only ever increases stays replay-safe across connections.</p>
     */
    public synchronized void reset() {
        lastStarted.clear();
        lastEnded.clear();
        clearInFlight();
    }

    private void clearInFlight() {
        inFlightHand = null;
        inFlightSequence = 0;
        inFlightDeadlineMillis = 0L;
    }
}
