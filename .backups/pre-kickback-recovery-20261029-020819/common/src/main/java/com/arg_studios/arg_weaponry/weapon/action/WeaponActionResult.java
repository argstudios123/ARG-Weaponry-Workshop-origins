package com.arg_studios.arg_weaponry.weapon.action;

/**
 * The outcome of validating one weapon action against the server's authoritative held stack.
 *
 * <p>A result answers exactly one question: <em>may this request be executed?</em> It is produced by
 * validation only, so {@link #OK} means "the request is well formed and nothing known forbids it" — never
 * "a shot was fired" or "a reload completed". The operations that will produce those effects are a later
 * milestone and are expected to report their own outcome.</p>
 *
 * <p>The values are explicit so that future input and networking handlers can branch on the value itself
 * instead of parsing a message: no constant in this enum carries text, a player, a stack or any other
 * state, which keeps the type free of Minecraft classes and therefore usable from the common module on
 * both loaders. A handler-side message, log line or sound is derived from the value, never stored in it.</p>
 *
 * <h2>Values</h2>
 * <ul>
 *   <li>{@link #OK} — the request may be executed.</li>
 *   <li>{@link #INVALID_CONTEXT} — the player or the execution context cannot perform weapon actions at
 *       all; nothing about the stack matters.</li>
 *   <li>{@link #NOT_A_WEAPON} — the requested hand does not hold a registered weapon item, so there is
 *       nothing to validate.</li>
 *   <li>{@link #INVALID_WEAPON_STATE} — the held weapon carries no readable state, so no rule about
 *       ammunition can be decided.</li>
 *   <li>{@link #MAGAZINE_EMPTY} — a firing request against an empty magazine.</li>
 *   <li>{@link #MAGAZINE_FULL} — a reloading request against a magazine that has no room left.</li>
 *   <li>{@link #UNSUPPORTED} — the currently available definition does not support the requested action at
 *       all (for example a weapon type that declares no magazine).</li>
 *   <li>{@link #PREREQUISITE_UNAVAILABLE} — the request is well formed, but a system it depends on does not
 *       exist in this build yet, so it must not be approved.</li>
 *   <li>{@link #FIRE_MODE_UNSUPPORTED} — the stored fire mode cannot be executed by this build.</li>
 *   <li>{@link #CADENCE_BLOCKED} — the weapon's own rate limit, or its anti-replay counter, rejected the
 *       request.</li>
 *   <li>{@link #CONTENT_MISMATCH} — the connection's content-pack compatibility was never verified or was
 *       blocked, so no weapon may act on that connection.</li>
 *   <li>{@link #RELOAD_NOT_APPLICABLE} — a reload was requested for a weapon whose ammunition is unlimited, so
 *       there is nothing to reload.</li>
 * </ul>
 */
public enum WeaponActionResult {

    /** The request is valid and may be executed. */
    OK,

    /**
     * The player or the execution context is not in a state that may perform weapon actions: no player, no
     * requested hand, a dead player or a spectator.
     */
    INVALID_CONTEXT,

    /** The requested hand does not hold a registered weapon item. */
    NOT_A_WEAPON,

    /** The held weapon carries no readable weapon state, so ammunition cannot be judged. */
    INVALID_WEAPON_STATE,

    /** A firing request was made against an empty magazine. */
    MAGAZINE_EMPTY,

    /** A reloading request was made against a magazine that already holds at least its capacity. */
    MAGAZINE_FULL,

    /**
     * The player is already inspecting the weapon, so a second inspection request is refused.
     *
     * <p>Added by Milestone 10R with the inspection action. Holding the key cannot start a second copy of the clip, and
     * neither can a client that simply repeats the request.</p>
     */
    INSPECT_IN_PROGRESS,

    /**
     * The weapon declares no inspection animation that applies to its current state.
     *
     * <p>Distinct from {@link #UNSUPPORTED}: a weapon with no {@code inspect_anims} at all is {@code UNSUPPORTED}, while a
     * weapon whose declared entries all fail their condition reports this instead.
     */
    INSPECTION_UNAVAILABLE,

    /** The currently available definition does not support the requested action (for example, no magazine). */
    UNSUPPORTED,

    /**
     * The request is well formed but depends on a system this build does not have yet, so it can neither be
     * approved nor rejected on its contents.
     *
     * <p>Today this is what a reload reports once the magazine has room: the reload operation itself — the one
     * that would consume reserve ammunition — is not implemented. Reporting this instead of {@link #OK} keeps
     * the missing prerequisite visible; "the magazine is not full" and "the player carries compatible rounds"
     * must never be mistaken for "the reload may proceed".</p>
     */
    PREREQUISITE_UNAVAILABLE,

    /**
     * The stored fire mode cannot be executed by this build.
     *
     * <p>Only {@code FireMode.SINGLE} is implemented. A stored {@code BURST} or {@code AUTO} is reported as
     * this value rather than being silently downgraded to a single shot, so choosing an unimplemented mode can
     * never produce a shot with the wrong cadence.</p>
     */
    FIRE_MODE_UNSUPPORTED,

    /**
     * The weapon's own rate limit rejected the request: either the configured cadence has not elapsed yet, or
     * the request repeated a sequence number that was already handled.
     *
     * <p>This is a normal, expected answer for a player who clicks faster than the weapon can fire. Nothing
     * was consumed and nothing was damaged.</p>
     */
    CADENCE_BLOCKED,

    /**
     * The connection's content-pack compatibility was never verified or was blocked, so no weapon may act on
     * it.
     *
     * <p>See {@code WeaponCompatibility}: a client that never answered the manifest handshake, or answered
     * with a different set of weapon identifiers, must not be able to fire through this system.</p>
     */
    CONTENT_MISMATCH,

    /**
     * A reloading request was made while the magazine has room but no compatible reserve ammunition is
     * carried.
     *
     * <p>Distinct from {@link #PREREQUISITE_UNAVAILABLE}: the ammunition system exists and was consulted, and
     * its answer was "nothing to load".</p>
     */
    NO_RESERVE_AMMUNITION,

    /**
     * A reload is already running for this weapon, so a firing or reloading request cannot be accepted.
     *
     * <p>Firing reports it as well: while a reload owns the weapon neither the magazine nor the reserve may
     * change, and a shot would do both.</p>
     */
    RELOAD_IN_PROGRESS,

    /**
     * The server accepted a reloading request and started a session.
     *
     * <p>This is an <em>outcome</em> rather than a validation verdict: nothing has been transferred yet and the
     * magazine is unchanged until the session completes.</p>
     */
    RELOAD_STARTED,

    /** A reload session finished and its transfer was committed. */
    RELOAD_COMPLETED,

    /**
     * A reload session ended without transferring anything: the request was superseded, the weapon or the
     * inventory changed, the player became ineligible, or the server stopped the session.
     */
    RELOAD_CANCELLED,

    /**
     * The request's sequence number does not increase for that hand, so it is a replay or a duplicate.
     *
     * <p>Reported for reloading requests; nothing is started and no state is touched.</p>
     */
    STALE_REQUEST,

    /**
     * A reloading request against a weapon whose ammunition is unlimited.
     *
     * <p>Added by Milestone 10P. Such a weapon never consumes ammunition, so there is nothing to reload: no session is
     * started, no animation or sound is played, no reserve ammunition is counted or consumed and the magazine state is
     * not touched. It is distinct from {@link #MAGAZINE_FULL} (a finite magazine that happens to be full) and from
     * {@link #UNSUPPORTED} (a weapon type that has no magazine at all), because the cause and the remedy differ: this
     * weapon is configured to need no reload at all.</p>
     */
    RELOAD_NOT_APPLICABLE;

    /**
     * @return {@code true} only for {@link #OK}, i.e. when the validated request may be executed
     */
    public boolean isAllowed() {
        return this == OK;
    }

    /**
     * The stable wire value of this result.
     *
     * <p>Declared explicitly, never derived from {@link #ordinal()}, exactly like {@code FireMode}: a result
     * that travels between a client and a server must keep its meaning when a constant is added later. Ids are
     * never renumbered or reused.</p>
     *
     * @return the wire id, unique and stable
     */
    public int networkId() {
        return switch (this) {
            case OK -> 1;
            case INVALID_CONTEXT -> 2;
            case NOT_A_WEAPON -> 3;
            case INVALID_WEAPON_STATE -> 4;
            case MAGAZINE_EMPTY -> 5;
            case MAGAZINE_FULL -> 6;
            case UNSUPPORTED -> 7;
            case PREREQUISITE_UNAVAILABLE -> 8;
            case FIRE_MODE_UNSUPPORTED -> 9;
            case CADENCE_BLOCKED -> 10;
            case CONTENT_MISMATCH -> 11;
            case NO_RESERVE_AMMUNITION -> 12;
            case RELOAD_IN_PROGRESS -> 13;
            case RELOAD_STARTED -> 14;
            case RELOAD_COMPLETED -> 15;
            case RELOAD_CANCELLED -> 16;
            case STALE_REQUEST -> 17;
            case RELOAD_NOT_APPLICABLE -> 18;
            case INSPECT_IN_PROGRESS -> 19;
            case INSPECTION_UNAVAILABLE -> 20;
        };
    }

    /**
     * @param id a wire value as written by {@link #networkId()}
     * @return the matching result
     * @throws IllegalArgumentException when no result declares that id, which means the stream is corrupt or
     *                                  comes from an incompatible version
     */
    public static WeaponActionResult byNetworkId(int id) {
        for (WeaponActionResult result : values()) {
            if (result.networkId() == id) {
                return result;
            }
        }
        throw new IllegalArgumentException("Unknown weapon action result id " + id
                + "; the stream is corrupt or was written by an incompatible version");
    }
}
