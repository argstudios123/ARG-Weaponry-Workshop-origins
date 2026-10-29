package com.arg_studios.arg_weaponry.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The outcome of comparing the server's weapon manifest with a client's.
 *
 * <p>Everything here is pure data and pure logic: no Minecraft types, no I/O, no player references, which is
 * what lets the whole comparison be verified headlessly. The report is produced by the server from two
 * manifests the server itself validated, so a client can influence the result only by what it actually
 * registered.</p>
 *
 * <h2>What blocks a connection</h2>
 * <p>Two classes of problem are <em>blocking</em>:</p>
 * <ul>
 *   <li>{@link IssueKind#INVALID_MANIFEST} — the manifest is unparseable, uses a schema this build does not
 *       understand, repeats an identifier, or contains an identifier or digest that is not well formed.
 *       Nothing about the client's content can be trusted in that case.</li>
 *   <li>{@link IssueKind#MISSING_ON_CLIENT} and {@link IssueKind#MISSING_ON_SERVER} — the two sides registered
 *       different weapon item identifiers. This is the registry-safety case: item stacks are synchronised by
 *       numeric registry id, so a different identifier set means the two registries are laid out differently
 *       and a stack can be decoded as a different item. The connection is refused rather than risking that.</li>
 * </ul>
 *
 * <p>Everything else is recorded and logged but does not block: a matching identifier whose gameplay digest or
 * pack fingerprint differs is a {@link Status#WARNINGS} result. Two sides can play together, but they are never
 * reported as identical — the client's copy of that weapon may behave differently, and a later gameplay
 * milestone must consult {@link WeaponCompatibility} before acting on it.</p>
 *
 * @param status        the overall outcome
 * @param issues        every difference found, in a deterministic order
 * @param matchedEntries how many identifiers both sides registered
 */
public record WeaponCompatibilityReport(Status status, List<Issue> issues, int matchedEntries) {

    /** The overall outcome of a comparison. */
    public enum Status {
        /** Both sides registered the same identifiers with identical digests and fingerprints. */
        COMPATIBLE,
        /** Both sides registered the same identifiers, but some content differs. Usable, recorded. */
        WARNINGS,
        /** The connection must be refused; see the issues. */
        BLOCKED
    }

    /** One difference between the two manifests. */
    public enum IssueKind {
        /** The manifest itself is unusable. */
        INVALID_MANIFEST,
        /** The server registered a weapon the client did not; blocking. */
        MISSING_ON_CLIENT,
        /** The client registered a weapon the server did not; blocking. */
        MISSING_ON_SERVER,
        /** Same identifier, different gameplay digest; recorded, not blocking. */
        DEFINITION_DIFFERS,
        /** Same identifier and digest, but the owning pack's file tree differs; recorded, not blocking. */
        PACK_DIFFERS
    }

    /**
     * One issue.
     *
     * @param kind   what kind of difference this is
     * @param itemId the weapon identifier, or {@code <invalid>} when the identifier itself was unusable
     * @param detail a short, bounded explanation that never contains unbounded untrusted text
     */
    public record Issue(IssueKind kind, String itemId, String detail) {

        /** @return whether this issue alone forbids the connection */
        public boolean blocks() {
            return kind == IssueKind.INVALID_MANIFEST || kind == IssueKind.MISSING_ON_CLIENT
                    || kind == IssueKind.MISSING_ON_SERVER;
        }
    }

    public WeaponCompatibilityReport {
        issues = List.copyOf(issues);
    }

    /** @return whether the connection must be refused */
    public boolean blocks() {
        return status == Status.BLOCKED;
    }

    /**
     * @param kind the kind to filter by
     * @return the issues of that kind, in report order
     */
    public List<Issue> issues(IssueKind kind) {
        List<Issue> matching = new ArrayList<>();
        for (Issue issue : issues) {
            if (issue.kind() == kind) {
                matching.add(issue);
            }
        }
        return matching;
    }

    /**
     * @param detail a bounded explanation
     * @return a blocking report for a manifest that could not be used at all
     */
    public static WeaponCompatibilityReport invalid(String detail) {
        return new WeaponCompatibilityReport(Status.BLOCKED,
                List.of(new Issue(IssueKind.INVALID_MANIFEST, "<invalid>", detail)), 0);
    }

    /**
     * Compares the server's manifest with the client's and decides what the difference means.
     *
     * <p>The comparison is order-independent: both manifests are indexed by identifier first. Duplicate and
     * malformed entries are reported before anything is matched, because a manifest that cannot be read must
     * not be used to decide that two identically named weapons are the same weapon.</p>
     *
     * @param server the server's own manifest
     * @param client the manifest the client reported
     * @return the verdict, never {@code null}
     */
    public static WeaponCompatibilityReport compare(WeaponManifest server, WeaponManifest client) {
        if (server == null || client == null) {
            return invalid("a manifest was missing");
        }

        List<Issue> issues = new ArrayList<>();
        checkSchema(issues, "server", server.schema());
        checkSchema(issues, "client", client.schema());
        checkDuplicates(issues, "server", server);
        checkDuplicates(issues, "client", client);
        checkEntries(issues, "server", server);
        checkEntries(issues, "client", client);

        Map<String, WeaponManifest.Entry> serverById = server.entriesById();
        Map<String, WeaponManifest.Entry> clientById = client.entriesById();

        int matched = 0;
        for (Map.Entry<String, WeaponManifest.Entry> entry : serverById.entrySet()) {
            String itemId = entry.getKey();
            WeaponManifest.Entry remote = clientById.get(itemId);
            if (remote == null) {
                issues.add(new Issue(IssueKind.MISSING_ON_CLIENT, safeId(itemId),
                        "the client did not register this weapon"));
                continue;
            }
            matched++;
            if (!entry.getValue().definitionDigest().equals(remote.definitionDigest())) {
                issues.add(new Issue(IssueKind.DEFINITION_DIFFERS, safeId(itemId),
                        "gameplay definition " + shortDigest(entry.getValue().definitionDigest()) + " on the server, "
                                + shortDigest(remote.definitionDigest()) + " on the client"));
            } else if (!entry.getValue().packFingerprint().equals(remote.packFingerprint())) {
                issues.add(new Issue(IssueKind.PACK_DIFFERS, safeId(itemId),
                        "the owning pack differs although the gameplay definition matches"));
            }
        }
        for (String itemId : clientById.keySet()) {
            if (!serverById.containsKey(itemId)) {
                issues.add(new Issue(IssueKind.MISSING_ON_SERVER, safeId(itemId),
                        "the client registered a weapon the server does not have"));
            }
        }

        boolean blocked = false;
        boolean warn = false;
        for (Issue issue : issues) {
            blocked |= issue.blocks();
            warn |= !issue.blocks();
        }
        Status status = blocked ? Status.BLOCKED : warn ? Status.WARNINGS : Status.COMPATIBLE;
        return new WeaponCompatibilityReport(status, issues, matched);
    }

    private static void checkSchema(List<Issue> issues, String side, int schema) {
        if (schema != WeaponManifest.SCHEMA) {
            issues.add(new Issue(IssueKind.INVALID_MANIFEST, "<invalid>",
                    "the " + side + " manifest declares schema " + schema + ", this build reads "
                            + WeaponManifest.SCHEMA));
        }
    }

    private static void checkDuplicates(List<Issue> issues, String side, WeaponManifest manifest) {
        Set<String> duplicates = manifest.duplicateIds();
        for (String duplicate : duplicates) {
            if (WeaponManifest.isValidIdentifier(duplicate)) {
                issues.add(new Issue(IssueKind.INVALID_MANIFEST, duplicate,
                        "the " + side + " manifest declares this identifier more than once"));
            }
        }
        if (!duplicates.isEmpty() && issues.stream().noneMatch(issue -> issue.kind() == IssueKind.INVALID_MANIFEST)) {
            issues.add(new Issue(IssueKind.INVALID_MANIFEST, "<invalid>",
                    "the " + side + " manifest declares " + duplicates.size() + " repeated identifier(s)"));
        }
    }

    private static void checkEntries(List<Issue> issues, String side, WeaponManifest manifest) {
        int invalid = 0;
        for (WeaponManifest.Entry entry : manifest.entries()) {
            boolean valid = WeaponManifest.isValidIdentifier(entry.itemId())
                    && WeaponManifest.isValidPackId(entry.packId())
                    && WeaponManifest.isValidHex(entry.packFingerprint())
                    && WeaponManifest.isValidHex(entry.definitionDigest());
            if (!valid) {
                invalid++;
            }
        }
        if (invalid > 0) {
            issues.add(new Issue(IssueKind.INVALID_MANIFEST, "<invalid>",
                    invalid + " entry(ies) of the " + side + " manifest are not well formed"));
        }
    }

    private static String safeId(String itemId) {
        return itemId != null && WeaponManifest.isValidIdentifier(itemId) ? itemId : "<invalid>";
    }

    private static String shortDigest(String digest) {
        return digest != null && WeaponManifest.isValidHex(digest) ? digest.substring(0, 12) : "?";
    }
}
