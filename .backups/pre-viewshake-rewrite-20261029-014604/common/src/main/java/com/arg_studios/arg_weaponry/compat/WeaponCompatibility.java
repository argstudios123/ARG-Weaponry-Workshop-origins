package com.arg_studios.arg_weaponry.compat;

import com.arg_studios.arg_weaponry.Constants;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-connection result of the content-pack handshake.
 *
 * <p>One entry per connected player, keyed by the profile UUID, which is known during the configuration phase
 * and is the same UUID the {@code ServerPlayer} has afterwards. The store therefore survives the
 * configuration-to-play transition without holding a {@code ServerPlayer}, a connection or any other
 * short-lived object — there is nothing to go stale.</p>
 *
 * <h2>Lifetime</h2>
 * <p>An entry exists only while its connection does. Both loaders clear it on disconnect, both clear everything
 * when the server stops, and a reconnect overwrites the entry during the new configuration phase, so a result
 * can never be inherited from an earlier connection.</p>
 *
 * <h2>For later gameplay code</h2>
 * <p>{@link #isUsable(UUID)} is the question a future firing or reload handler should ask before acting on a
 * weapon: it is {@code false} for a connection that was never verified and for one that failed verification.
 * {@link #isVerifiedCompatible(UUID)} is the stricter form, for anything that must only run when both sides
 * have byte-identical content. Gameplay code must always take the weapon definition and the held stack from
 * the server's own state — this store only says whether that content can be trusted to mean the same thing on
 * the client.</p>
 */
public final class WeaponCompatibility {

    private static final Map<UUID, WeaponCompatibilityReport> reports = new ConcurrentHashMap<>();

    private WeaponCompatibility() {
    }

    /**
     * Stores the report for a connection.
     *
     * @param playerId the profile UUID of the connection
     * @param report   the verdict
     * @return {@code false} when a report already existed, which happens for a repeated or late reply; the
     *         existing report is kept and the caller must not act on the duplicate
     */
    public static boolean record(UUID playerId, WeaponCompatibilityReport report) {
        if (playerId == null || report == null) {
            return false;
        }
        WeaponCompatibilityReport previous = reports.putIfAbsent(playerId, report);
        if (previous != null) {
            Constants.LOG.debug("[ARG] Ignoring a repeated weapon compatibility result for {}", playerId);
            return false;
        }
        return true;
    }

    /**
     * @param playerId the profile UUID
     * @return the recorded report, or empty when this connection was never verified
     */
    public static Optional<WeaponCompatibilityReport> reportFor(UUID playerId) {
        return playerId == null ? Optional.empty() : Optional.ofNullable(reports.get(playerId));
    }

    /**
     * @param playerId the profile UUID
     * @return {@code true} only when the handshake ran and both sides matched exactly
     */
    public static boolean isVerifiedCompatible(UUID playerId) {
        return reportFor(playerId).map(report -> report.status() == WeaponCompatibilityReport.Status.COMPATIBLE)
                .orElse(false);
    }

    /**
     * @param playerId the profile UUID
     * @return {@code true} when the handshake ran and did not block; a never-verified connection is not usable
     */
    public static boolean isUsable(UUID playerId) {
        return reportFor(playerId).map(report -> !report.blocks()).orElse(false);
    }

    /**
     * Drops the result of one connection.
     *
     * @param playerId the profile UUID, may be {@code null}
     */
    public static void clear(UUID playerId) {
        if (playerId != null) {
            reports.remove(playerId);
        }
    }

    /** Drops every result; called when the server stops and when it starts, so nothing is inherited. */
    public static void clearAll() {
        reports.clear();
    }

    /** @return the number of connections with a recorded result, for diagnostics */
    public static int size() {
        return reports.size();
    }
}
