package com.arg_studios.arg_weaponry.compat;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.install.PackFingerprint;
import com.arg_studios.arg_weaponry.item.WeaponRegistration;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.AmmoCapacity;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.fire.FiringModes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches this side's weapon manifest.
 *
 * <p>The snapshot is taken once, immediately after this side registered its weapon items, and then never
 * recomputed. That matters for three reasons:</p>
 * <ul>
 *   <li>item registration is the only moment at which the set of registered weapon identifiers is final, so
 *       the snapshot cannot miss an item or include one that was skipped;</li>
 *   <li>a pack fingerprint walks the whole pack tree, which must not happen on a network thread, per
 *       connection, or per tick — it happens exactly once per pack per process here;</li>
 *   <li>the payloads that carry the manifest are built from immutable data afterwards, so encoding cannot
 *       race with a reload.</li>
 * </ul>
 *
 * <h2>Pack fingerprints</h2>
 * <p>The fingerprint is {@link PackFingerprint}, the same implementation the installer uses, over the pack
 * directory that was actually loaded. The values recorded in {@code .install-state.json} are deliberately not
 * reused: they describe what the installer wrote, not what is on disk now, so an edit made after installation
 * would be missed. The cost is one tree walk per pack per launch, cached in {@link #fingerprintsByPack} and
 * shared by every connection.</p>
 *
 * <h2>Definition digests</h2>
 * <p>{@link #definitionDigest(WeaponDefinition)} hashes a canonical, explicitly formatted text form of the
 * gameplay-relevant definition fields. It never uses {@code toString()} of a record, a map's iteration order or
 * an object identity: every field is appended in a fixed order with a fixed separator, absent optional values
 * become {@code -}, and lists keep their authored order. Presentation-only data (display, sounds, animation
 * names, HUD, glowing parts) is excluded, because changing a texture must not make two weapons incompatible.</p>
 */
public final class WeaponManifestSnapshot {

    /** Field separator that cannot occur inside an identifier or a number. */
    private static final char SEPARATOR = '\u001f';

    /** Fingerprint prefix used when a pack tree cannot be read, so the value stays well formed. */
    private static final String UNREADABLE_PREFIX = "unreadable:";

    private static final Map<String, String> fingerprintsByPack = new ConcurrentHashMap<>();

    private static volatile WeaponManifest local = WeaponManifest.empty();

    private WeaponManifestSnapshot() {
    }

    /**
     * Freezes this side's manifest from the weapons that were actually registered.
     *
     * <p>Called once from {@link com.arg_studios.arg_weaponry.ArgWeaponry#registerItems()}. Calling it again
     * simply rebuilds the same snapshot from the same registry, so a duplicate loader callback is harmless.</p>
     */
    public static void build() {
        List<WeaponManifest.Entry> entries = new ArrayList<>();
        for (WeaponRegistration.RegisteredWeapon registered : WeaponRegistration.registeredWeapons()) {
            ContentPack pack = registered.plan().pack();
            entries.add(new WeaponManifest.Entry(
                    registered.itemId().toString(),
                    pack.id(),
                    fingerprintOf(pack),
                    definitionDigest(registered.plan().definition())));
        }
        entries.sort(Comparator.comparing(WeaponManifest.Entry::itemId));
        local = new WeaponManifest(WeaponManifest.SCHEMA, entries);
        Constants.LOG.info("[ARG] Weapon manifest: {} entr(ies) over {} content pack(s)", entries.size(),
                entries.stream().map(WeaponManifest.Entry::packId).distinct().count());
    }

    /** @return this side's frozen manifest, empty before {@link #build()} ran */
    public static WeaponManifest local() {
        return local;
    }

    /**
     * @param pack a content pack
     * @return the fingerprint of the pack tree currently loaded, cached for this process
     */
    public static String fingerprintOf(ContentPack pack) {
        String key = pack.id() + SEPARATOR + pack.root();
        return fingerprintsByPack.computeIfAbsent(key, ignored -> computeFingerprint(pack));
    }

    private static String computeFingerprint(ContentPack pack) {
        try {
            return PackFingerprint.of(pack.root());
        } catch (IOException e) {
            Constants.LOG.warn("[ARG] The file tree of content pack '{}' could not be read ({}); its manifest "
                    + "entry uses a placeholder fingerprint", pack.id(), e.getMessage());
            return sha256(UNREADABLE_PREFIX + pack.id());
        }
    }

    /**
     * @param definition a parsed weapon definition
     * @return hex SHA-256 over the definition's canonical gameplay configuration
     */
    public static String definitionDigest(WeaponDefinition definition) {
        StringBuilder canonical = new StringBuilder(256);
        canonical.append("type").append(SEPARATOR)
                .append(definition.type() == null ? "-" : definition.type().jsonName()).append(SEPARATOR);
        canonical.append("damage").append(SEPARATOR).append(definition.damage()).append(SEPARATOR);
        canonical.append("capacity").append(SEPARATOR).append(capacity(definition.ammoCapacity()))
                .append(SEPARATOR);
        canonical.append("modes").append(SEPARATOR)
                .append(String.join(",", FiringModes.names(definition.firingModes()))).append(SEPARATOR);
        canonical.append("burst").append(SEPARATOR).append(definition.burstCount()).append(SEPARATOR);
        canonical.append("continuousBurst").append(SEPARATOR).append(definition.continuousBurst())
                .append(SEPARATOR);
        canonical.append("burstGap").append(SEPARATOR).append(definition.timeBetweenBursts()).append(SEPARATOR);
        canonical.append("rpm").append(SEPARATOR).append(number(definition.rpm())).append(SEPARATOR);
        canonical.append("range").append(SEPARATOR).append(number(definition.range())).append(SEPARATOR);
        canonical.append("zoom").append(SEPARATOR).append(number(definition.aimZoom())).append(SEPARATOR);
        canonical.append("ammo").append(SEPARATOR).append(String.join(",", definition.ammoToUse()))
                .append(SEPARATOR);

        WeaponDefinition.Aiming aiming = definition.aiming();
        canonical.append("aiming").append(SEPARATOR).append(aiming == null ? "-"
                : aiming.enabled() + "," + aiming.noAimingOnInspect() + "," + aiming.noAimingOnReload() + ","
                        + number(aiming.zoom()) + "," + (aiming.zoomType() == null ? "-" : aiming.zoomType()))
                .append(SEPARATOR);

        WeaponDefinition.Recoil recoil = definition.recoil();
        canonical.append("recoil").append(SEPARATOR).append(recoil == null ? "-"
                : number(recoil.kickbackAmplitude()) + "," + number(recoil.vertical()) + ","
                        + number(recoil.horizontal()) + "," + number(recoil.horizontalLeft()) + ","
                        + number(recoil.horizontalRight()) + "," + number(recoil.smoothing()) + ","
                        + number(recoil.recoverySpeed()) + "," + number(recoil.aimMultiplier()) + ","
                        + number(recoil.crouchMultiplier())).append(SEPARATOR);

        WeaponDefinition.Accuracy accuracy = definition.accuracy();
        canonical.append("accuracy").append(SEPARATOR).append(accuracy == null ? "-"
                : number(accuracy.crouching()) + "," + number(accuracy.walking()) + ","
                        + number(accuracy.standing()) + "," + number(accuracy.aiming())).append(SEPARATOR);

        WeaponDefinition.SustainedRecoil sustained = definition.sustainedFiringRecoil();
        canonical.append("sustained").append(SEPARATOR).append(sustained == null ? "-"
                : number(sustained.positionZCap()) + "," + number(sustained.positionZIncrement()) + ","
                        + number(sustained.pitchIncrement()) + "," + number(sustained.positionYIncrement()))
                .append(SEPARATOR);

        canonical.append("reload").append(SEPARATOR);
        for (WeaponDefinition.ReloadStep step : definition.reloadSequences()) {
            canonical.append(text(step.phase())).append('|').append(text(step.animation())).append('|')
                    .append(step.duration()).append('|').append(text(step.when())).append('|')
                    .append(step.repeat() == null ? "-" : step.repeat()).append('|')
                    // The insertion point changes when a shell appears mid-clip, so two definitions that differ only in
                    // "insert_at" must not hash the same: the digest is what the compatibility handshake compares.
                    .append(step.insertAt() == null ? "-" : step.insertAt().toString()).append(';');
        }

        return sha256(canonical.toString());
    }

    private static String number(Number value) {
        return value == null ? "-" : value.toString();
    }

    /**
     * Serialises the ammunition capacity for the digest.
     *
     * <p>{@code 20} and {@code unlimited} must not collide, and an unlimited capacity must not be hashed as a numeric
     * fallback such as {@code -1} or {@code 0}, because the digest is what the compatibility handshake compares: two
     * sides that disagree about whether ammunition is consumed would otherwise agree on the hash. The token and the
     * decimal number are therefore both written verbatim, and both are bracketed by the digest's own separator.</p>
     *
     * @param capacity the declared capacity, or {@code null} when the definition declares none
     * @return the canonical text of that capacity
     */
    private static String capacity(AmmoCapacity capacity) {
        return capacity == null ? "-" : capacity.canonicalForm();
    }

    private static String text(String value) {
        return value == null ? "-" : value.replace(String.valueOf(SEPARATOR), " ");
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
