package com.arg_studios.arg_weaponry.weapon.fire;

/**
 * The facts a hit carries that the client needs for feedback, derived from the authoritative hit result.
 *
 * <p>Two questions are answered here, and only here: was the shot a headshot, and did it kill? Both are computed on the
 * server from the real ray/entity result and the real damage application, so a client can never claim either. The
 * arithmetic is kept free of Minecraft types so it can be verified headlessly.</p>
 *
 * <h2>What counts as a headshot</h2>
 * <p>The hit point is compared with the entity's own bounding box: the top quarter of the box is the head region. That
 * is a property of the hitbox rather than a guess about a mob's anatomy, it works for every entity size, and it needs no
 * per-entity table. The eye height is deliberately <em>not</em> used: a chicken and a player have very different eye
 * heights relative to their box, while the top quarter of the box is the head for both.</p>
 */
public final class HitFacts {

    /**
     * Share of an entity's bounding box, measured from its top, that counts as the head region.
     *
     * <p>A quarter of a player's 1.8-block box is the top 0.45 blocks, which contains the head model and sits above the
     * shoulders.</p>
     */
    public static final double HEADSHOT_FRACTION = 0.25;

    private HitFacts() {
    }

    /**
     * @param boxMinY  the lowest Y of the entity's bounding box
     * @param boxHeight the height of the entity's bounding box; must be positive to be meaningful
     * @param hitY     the Y coordinate of the hit
     * @return whether the hit lies in the head region
     */
    public static boolean isHeadshot(double boxMinY, double boxHeight, double hitY) {
        if (!(boxHeight > 0.0) || !Double.isFinite(boxMinY) || !Double.isFinite(hitY)) {
            return false;
        }
        double headBoundary = boxMinY + boxHeight * (1.0 - HEADSHOT_FRACTION);
        return hitY >= headBoundary;
    }

    /**
     * @param healthBefore the entity's health before the shot
     * @param damage       the damage the shot dealt
     * @return whether that damage is lethal
     */
    public static boolean isLethal(double healthBefore, double damage) {
        if (!Double.isFinite(healthBefore) || !Double.isFinite(damage)) {
            return false;
        }
        return damage >= healthBefore;
    }
}
