package com.arg_studios.arg_weaponry.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The deterministic description of the weapons one side has registered.
 *
 * <p>This is the payload of the compatibility handshake, and it is deliberately small: one entry per
 * registered weapon item, identified by the same {@code namespace:path} the item registry uses, plus the
 * owning pack, that pack's fingerprint and a digest of the weapon's gameplay configuration. No geometry,
 * texture bytes, animation document or pack file is ever part of it.</p>
 *
 * <h2>Determinism</h2>
 * <p>The list order is not part of the contract — {@link #entriesById()} makes every comparison
 * order-independent — but the snapshot builder sorts by item identifier anyway, so two identical sides produce
 * byte-identical payloads and network captures stay comparable.</p>
 *
 * <h2>Untrusted input</h2>
 * <p>Everything in a manifest that arrives over the network is untrusted. The codec enforces the structural
 * bounds (entry count, identifier length, digest length) while decoding, so a hostile payload cannot make the
 * server allocate more than {@link #MAX_ENTRIES} small strings; everything else — identifier syntax, digest
 * format, duplicates — is judged by {@link WeaponCompatibilityReport}, so the server can refuse the connection
 * with a readable reason instead of dying in the decoder.</p>
 *
 * @param schema  the manifest schema version the sender used
 * @param entries one entry per registered weapon, in the sender's order
 */
public record WeaponManifest(int schema, List<Entry> entries) {

    /** The schema version this build writes. */
    public static final int SCHEMA = 1;

    /** Hard cap on the number of entries a manifest may declare. */
    public static final int MAX_ENTRIES = 256;

    /** Hard cap on an identifier or pack id, matching what the codec will read. */
    public static final int MAX_IDENTIFIER_LENGTH = 128;

    /** Length of a SHA-256 value as lowercase hexadecimal. */
    public static final int MAX_HEX_LENGTH = 64;

    /**
     * One manifest entry.
     *
     * @param itemId           the registered item identifier, {@code namespace:path}
     * @param packId           the id of the content pack that owns the weapon
     * @param packFingerprint  hex SHA-256 of the owning pack's file tree, see
     *                         {@link com.arg_studios.arg_weaponry.install.PackFingerprint}
     * @param definitionDigest hex SHA-256 over the weapon definition's gameplay configuration
     */
    public record Entry(String itemId, String packId, String packFingerprint, String definitionDigest) {
    }

    public WeaponManifest {
        entries = List.copyOf(entries);
    }

    /** @return a manifest that declares no weapons */
    public static WeaponManifest empty() {
        return new WeaponManifest(SCHEMA, List.of());
    }

    /**
     * @param itemId the item identifier to look up
     * @return the first entry with that identifier, or empty
     */
    public Optional<Entry> entry(String itemId) {
        for (Entry entry : entries) {
            if (entry.itemId().equals(itemId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** @return the entries keyed by item identifier; when an identifier repeats, the first occurrence wins */
    public Map<String, Entry> entriesById() {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry entry : entries) {
            byId.putIfAbsent(entry.itemId(), entry);
        }
        return byId;
    }

    /** @return the identifiers that appear more than once, in the order they first repeated */
    public Set<String> duplicateIds() {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (!seen.add(entry.itemId())) {
                duplicates.add(entry.itemId());
            }
        }
        return duplicates;
    }

    /** @return every declared identifier, in sender order and including duplicates */
    public Set<String> itemIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Entry entry : entries) {
            ids.add(entry.itemId());
        }
        return ids;
    }

    /**
     * @param value a candidate identifier
     * @return whether the value is a well-formed, canonically written {@code namespace:path} identifier
     */
    public static boolean isValidIdentifier(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_IDENTIFIER_LENGTH) {
            return false;
        }
        Identifier parsed = Identifier.tryParse(value);
        return parsed != null && parsed.toString().equals(value);
    }

    /**
     * @param value a candidate pack id
     * @return whether the value is a plausible pack id: non-empty, bounded, and free of control characters
     */
    public static boolean isValidPackId(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_IDENTIFIER_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param value a candidate digest or fingerprint
     * @return whether the value is exactly {@link #MAX_HEX_LENGTH} lowercase hexadecimal characters
     */
    public static boolean isValidHex(String value) {
        if (value == null || value.length() != MAX_HEX_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hex = (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Network codec.
     *
     * <p>Bounds are enforced while reading: a count outside {@code 0..}{@link #MAX_ENTRIES} and a string longer
     * than its limit abort the decode, which the network layer turns into a protocol error on that one
     * connection. Nothing is allocated before the count has been checked.</p>
     */
    public static final StreamCodec<FriendlyByteBuf, WeaponManifest> CODEC = StreamCodec.of(
            WeaponManifest::write,
            WeaponManifest::read);

    private static void write(FriendlyByteBuf buffer, WeaponManifest manifest) {
        buffer.writeVarInt(manifest.schema);
        buffer.writeVarInt(manifest.entries.size());
        for (Entry entry : manifest.entries) {
            buffer.writeUtf(entry.itemId(), MAX_IDENTIFIER_LENGTH);
            buffer.writeUtf(entry.packId(), MAX_IDENTIFIER_LENGTH);
            buffer.writeUtf(entry.packFingerprint(), MAX_HEX_LENGTH);
            buffer.writeUtf(entry.definitionDigest(), MAX_HEX_LENGTH);
        }
    }

    private static WeaponManifest read(FriendlyByteBuf buffer) {
        int schema = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("A weapon manifest may not declare " + count + " entries; the "
                    + "limit is " + MAX_ENTRIES);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(
                    buffer.readUtf(MAX_IDENTIFIER_LENGTH),
                    buffer.readUtf(MAX_IDENTIFIER_LENGTH),
                    buffer.readUtf(MAX_HEX_LENGTH),
                    buffer.readUtf(MAX_HEX_LENGTH)));
        }
        return new WeaponManifest(schema, entries);
    }
}
