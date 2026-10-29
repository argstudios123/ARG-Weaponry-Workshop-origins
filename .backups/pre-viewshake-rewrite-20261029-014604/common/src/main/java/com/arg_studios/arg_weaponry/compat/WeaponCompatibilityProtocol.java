package com.arg_studios.arg_weaponry.compat;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.network.INetworkService;
import com.arg_studios.arg_weaponry.network.WeaponManifestPayload;
import com.arg_studios.arg_weaponry.network.WeaponManifestReplyPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ConfigurationTask;

import java.util.List;
import java.util.UUID;

/**
 * The content-pack compatibility handshake, in loader-independent form.
 *
 * <p>The protocol has four steps and no more: the server sends its manifest, the client answers with its own,
 * the server compares them itself, and the server records the outcome (or refuses the connection). The client
 * never sends a verdict, so a modified client cannot claim compatibility it does not have.</p>
 *
 * <h2>When it runs</h2>
 * <p>In the <strong>configuration phase</strong>, from a configuration task both loaders add for every
 * connection. That is the earliest point at which a mod payload can travel, it happens before the client is
 * told to join the world, and on NeoForge it happens after the frozen-registry sync, which is exactly where the
 * item registry layout is already fixed. A play-phase packet would arrive after the client's registry has been
 * used to decode item stacks, so it could not protect against an incompatible registry — that is why the
 * handshake is not implemented there.</p>
 *
 * <h2>Registry safety</h2>
 * <p>The two sides must register the same weapon item identifiers. Where they do not,
 * {@link WeaponCompatibilityReport} marks the connection as blocking and this class disconnects it with the
 * reason, before the client is allowed into the world.</p>
 */
public final class WeaponCompatibilityProtocol {

    /**
     * The protocol version reported to NeoForge's channel negotiation.
     *
     * <p>Bumped to {@code 2} by Milestone 10Q, which added the trigger, trigger-result, fire-mode and fire-mode-result
     * payload types. NeoForge refuses a connection whose channel version differs, so an older client is told it is
     * incompatible instead of silently failing to receive those payloads. The value is independent of the
     * gameplay-definition digest, which continues to be computed from the weapon definitions themselves.</p>
     */
    public static final String PROTOCOL_VERSION = "2";

    /** The id of the configuration task both loaders add. */
    public static final Identifier TASK_ID =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_compatibility");

    /** The configuration task type the reply finishes. */
    public static final ConfigurationTask.Type TASK_TYPE = new ConfigurationTask.Type(TASK_ID.toString());

    /** A configuration task that is never answered is given up on after this many server ticks (30 s). */
    public static final int TIMEOUT_TICKS = 600;

    private static final int MAX_REPORTED_ISSUES = 3;

    private WeaponCompatibilityProtocol() {
    }

    /**
     * Declares both handshake payload types through the networking service.
     *
     * @param network the loader's networking service
     * @param client  {@code true} on a physical client, which also needs the clientbound handler
     */
    public static void declarePayloads(INetworkService network, boolean client) {
        network.declareServerbound(INetworkService.Phase.CONFIGURATION,
                WeaponManifestReplyPayload.TYPE, WeaponManifestReplyPayload.CODEC,
                WeaponCompatibilityProtocol::onReply);
        network.declareClientbound(INetworkService.Phase.CONFIGURATION,
                WeaponManifestPayload.TYPE, WeaponManifestPayload.CODEC,
                client ? WeaponCompatibilityProtocol::onManifest : null);
    }

    /** @return the payload the server starts the handshake with */
    public static WeaponManifestPayload manifestPayload() {
        return new WeaponManifestPayload(WeaponManifestSnapshot.local());
    }

    /** @return the type of the configuration task that carries the handshake */
    public static ConfigurationTask.Type taskType() {
        return TASK_TYPE;
    }

    /**
     * Client side: answers the server's manifest with this client's own.
     *
     * <p>Runs on the client thread. The comparison that happens here is diagnostic only — it lets the client
     * log a useful warning before the server's verdict arrives — and never influences the server's decision.</p>
     *
     * @param payload the server's manifest
     * @param reply   the channel the reply has to be sent through
     */
    public static void onManifest(WeaponManifestPayload payload, INetworkService.ReplySender reply) {
        WeaponManifest local = WeaponManifestSnapshot.local();
        WeaponCompatibilityReport preview = WeaponCompatibilityReport.compare(payload.manifest(), local);
        if (preview.blocks()) {
            Constants.LOG.warn("[ARG] The server's weapon content does not match this client's: {}",
                    summarise(preview));
        } else if (preview.status() == WeaponCompatibilityReport.Status.WARNINGS) {
            Constants.LOG.info("[ARG] Weapon content matches by identifier but differs in detail: {}",
                    summarise(preview));
        } else {
            Constants.LOG.info("[ARG] Weapon content matches the server ({} weapon(s))", local.entries().size());
        }
        reply.send(new WeaponManifestReplyPayload(local));
    }

    /**
     * Server side: validates the client's manifest, records the verdict and lets the connection continue or
     * closes it.
     *
     * <p>Runs on the server thread. A repeated or late reply for a connection that already has a verdict is
     * ignored, so a client cannot overwrite its result by answering twice.</p>
     *
     * @param payload the client's manifest
     * @param context the connection the reply arrived on
     */
    public static void onReply(WeaponManifestReplyPayload payload, INetworkService.ServerContext context) {
        UUID sender = context.senderId();
        WeaponCompatibilityReport report =
                WeaponCompatibilityReport.compare(WeaponManifestSnapshot.local(), payload.manifest());
        if (!WeaponCompatibility.record(sender, report)) {
            return;
        }

        Constants.LOG.info("[ARG] Weapon content check for {}: {} ({} matching identifier(s), {} issue(s))",
                sender, report.status(), report.matchedEntries(), report.issues().size());
        for (WeaponCompatibilityReport.Issue issue : report.issues()) {
            if (report.blocks()) {
                Constants.LOG.warn("[ARG]   {} {}: {}", issue.kind(), issue.itemId(), issue.detail());
            } else {
                Constants.LOG.info("[ARG]   {} {}: {}", issue.kind(), issue.itemId(), issue.detail());
            }
        }

        if (report.blocks()) {
            context.disconnect(disconnectReason(report));
            return;
        }
        context.finishConfigurationTask();
    }

    /**
     * Server side: records that a connection never answered.
     *
     * <p>The connection is not closed for this — a slow client is not a hostile one — but the verdict is a
     * blocking one, so {@link WeaponCompatibility#isUsable(UUID)} stays {@code false} and any later gameplay
     * action involving a weapon is refused rather than executed against unverified content.</p>
     *
     * @param playerId the profile UUID of the silent connection
     */
    public static void onTimeout(UUID playerId) {
        if (WeaponCompatibility.record(playerId, WeaponCompatibilityReport.invalid(
                "the client did not answer the weapon compatibility request within " + TIMEOUT_TICKS
                        + " ticks"))) {
            Constants.LOG.warn("[ARG] No weapon compatibility reply from {}; weapon actions will be refused "
                    + "for this connection", playerId);
        }
    }

    /**
     * @param report a blocking report
     * @return the message shown to the client that is being refused
     */
    public static Component disconnectReason(WeaponCompatibilityReport report) {
        return Component.literal(Constants.MOD_NAME + ": incompatible weapon content — " + summarise(report));
    }

    /** Builds a bounded one-line summary; only validated identifiers and counts are included. */
    private static String summarise(WeaponCompatibilityReport report) {
        if (report.issues().isEmpty()) {
            return report.status().name().toLowerCase(java.util.Locale.ROOT);
        }
        StringBuilder summary = new StringBuilder();
        List<WeaponCompatibilityReport.Issue> issues = report.issues();
        int shown = Math.min(MAX_REPORTED_ISSUES, issues.size());
        for (int index = 0; index < shown; index++) {
            WeaponCompatibilityReport.Issue issue = issues.get(index);
            if (index > 0) {
                summary.append("; ");
            }
            summary.append(issue.kind().name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(issue.itemId());
        }
        if (issues.size() > shown) {
            summary.append("; +").append(issues.size() - shown).append(" more");
        }
        return summary.toString();
    }
}
