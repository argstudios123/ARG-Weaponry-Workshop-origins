package com.arg_studios.arg_weaponry.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The running muzzle flashes of a keyed owner, with no Minecraft types so the lifecycle is testable.
 *
 * <p>This is the part of {@link MuzzleFlash} that decides <em>which</em> flashes are running: it appends one record per
 * accepted shot, reports the ones that are still inside their lifetime, and drops the expired ones. It deliberately knows
 * nothing about items, hands, rendering or the game clock - the key and the owner are whatever the caller uses (an
 * interaction hand and a weapon item in production) - which is the same split the rest of the client code follows for its
 * pure rules.</p>
 *
 * <h2>Per-flash lifetime</h2>
 * <p>Each record keeps the lifetime it was created with, so one shot can be a 60 ms flash and the next a 400 ms one, and
 * a configuration's {@code duration_ms} is what decides when a flash is forgotten. Records are pruned on the same rule,
 * so an expiration never leaves state behind.</p>
 *
 * <h2>Why a list per key</h2>
 * <p>The reference implementation appends every shot's effect to the gun state and removes it once it has expired, so two
 * shots that overlap are two independent animations. A single "current flash" per hand would instead restart the
 * animation on every shot, which is a visible difference at any fire rate faster than the flash itself.</p>
 *
 * @param <K> the key that owns the flashes, an interaction hand in production
 * @param <O> the owner a flash belongs to, a weapon item in production
 */
final class MuzzleFlashState<K, O> {

    /**
     * The lifetime a flash gets when its configuration declares none, in seconds.
     *
     * <p>An authored {@code duration_ms} always wins; this is the fallback for packs that predate it. It also keeps a
     * record from being queried forever, for example when a weapon's model or texture is missing and the renderer
     * therefore never reaches the frame calculation.</p>
     */
    static final double DEFAULT_LIFETIME_SECONDS = 1.0D;

    /**
     * The most flashes one key may have running at once.
     *
     * <p>A backstop, not a gameplay limit: a flash lives for its configured duration at most, so a list this long can only
     * be reached by something firing faster than the flashes last. The oldest flash is dropped first.</p>
     */
    static final int MAX_PER_KEY = 8;

    /**
     * One running flash.
     *
     * @param owner      the weapon that fired
     * @param bone       the attachment bone chosen when the shot happened
     * @param startNanos when the animation starts, in nanoseconds on the caller's clock
     * @param seed       the random seed this shot drew
     * @param lifetimeSeconds how long this flash lives, in seconds: the authored {@code duration_ms}, or
     *                        {@link #DEFAULT_LIFETIME_SECONDS} when the configuration declares none
     */
    record Flash<O>(O owner, String bone, long startNanos, int seed, double lifetimeSeconds) {
    }

    private final Map<K, List<Flash<O>>> byKey = new HashMap<>();

    /**
     * Appends one flash and forgets the ones that have already expired.
     *
     * @param key        the owner key
     * @param owner      the owner the flash belongs to
     * @param bone       the attachment bone
     * @param startNanos when the animation starts
     * @param seed            the seed this shot's sprite selection will use
     * @param lifetimeSeconds how long this flash lives, in seconds; a non-positive or non-finite value falls back to
     *                        {@link #DEFAULT_LIFETIME_SECONDS}
     */
    synchronized void add(K key, O owner, String bone, long startNanos, int seed, double lifetimeSeconds) {
        List<Flash<O>> flashes = byKey.computeIfAbsent(key, ignored -> new ArrayList<>(2));
        purge(flashes, startNanos);
        if (flashes.size() >= MAX_PER_KEY) {
            flashes.remove(0);
        }
        double lifetime = Double.isFinite(lifetimeSeconds) && lifetimeSeconds > 0.0D
                ? lifetimeSeconds : DEFAULT_LIFETIME_SECONDS;
        flashes.add(new Flash<>(owner, bone, startNanos, seed, lifetime));
    }

    /**
     * @param key      the owner key
     * @param owner    the owner to report flashes for
     * @param nowNanos the current time on the same clock {@link #add} was given
     * @return the flashes of that owner that are still inside their lifetime, oldest first; never {@code null}
     */
    synchronized List<Flash<O>> active(K key, O owner, long nowNanos) {
        List<Flash<O>> flashes = byKey.get(key);
        if (flashes == null) {
            return List.of();
        }
        purge(flashes, nowNanos);
        if (flashes.isEmpty()) {
            byKey.remove(key);
            return List.of();
        }
        List<Flash<O>> active = new ArrayList<>(flashes.size());
        for (Flash<O> flash : flashes) {
            if (flash.owner() != owner) {
                // A different owner under the same key means those flashes are no longer this one's.
                continue;
            }
            double ageSeconds = ageSeconds(flash, nowNanos);
            if (!Double.isFinite(ageSeconds) || ageSeconds < 0.0D || ageSeconds > flash.lifetimeSeconds()) {
                continue;
            }
            active.add(flash);
        }
        return active.isEmpty() ? List.of() : active;
    }

    /** Forgets every flash. */
    synchronized void clear() {
        byKey.clear();
    }

    /**
     * @param key the owner key
     * @return how many flashes are recorded for that key, including ones about to expire
     */
    synchronized int size(K key) {
        List<Flash<O>> flashes = byKey.get(key);
        return flashes == null ? 0 : flashes.size();
    }

    private void purge(List<Flash<O>> flashes, long nowNanos) {
        flashes.removeIf(flash -> ageSeconds(flash, nowNanos) > flash.lifetimeSeconds());
    }

    private static <O> double ageSeconds(Flash<O> flash, long nowNanos) {
        return (nowNanos - flash.startNanos()) / 1.0E9D;
    }
}
