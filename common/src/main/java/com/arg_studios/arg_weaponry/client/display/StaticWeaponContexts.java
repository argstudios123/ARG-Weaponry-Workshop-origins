package com.arg_studios.arg_weaponry.client.display;

import java.util.Set;

/**
 * Which display contexts draw the weapon as a <em>static model</em> instead of going through the animated first-person pass.
 *
 * <h2>The split</h2>
 * <p>ARG renders a held weapon itself in first person: the Bedrock model, the animated pose, the arms and the view anchor. Every
 * other context - another player's hand, the ground, an item frame, a shelf, a container - must show the authored 3D model and
 * <strong>nothing else</strong>: no first-person arms, no locomotion or action layers, no sway, no camera transform.</p>
 *
 * <p>The two contexts that stay with the vanilla item path on purpose are {@code gui} and {@code firstperson_*}: the GUI keeps
 * its existing icon rendering (a pack may provide its own item model, and the HUD slot texture is drawn separately), and first
 * person is owned by the animated renderer. Everything else uses the static model.</p>
 *
 * <h2>Why the names and not the enum</h2>
 * <p>The decision is kept as a string test so it can be verified without a client, exactly like the display loader keeps its
 * context keys as strings. {@code ItemDisplayContext.getSerializedName()} is the only thing the caller has to supply.</p>
 */
public final class StaticWeaponContexts {

    /** The contexts rendered from the weapon's own geometry as a plain item model. */
    private static final Set<String> STATIC = Set.of(
            "thirdperson_righthand",
            "thirdperson_lefthand",
            "ground",
            "fixed",
            "on_shelf",
            "none");

    /** The two contexts the animated first-person renderer owns. */
    private static final Set<String> FIRST_PERSON = Set.of("firstperson_righthand", "firstperson_lefthand");

    private StaticWeaponContexts() {
    }

    /**
     * @param contextName a serialized display-context name, may be {@code null}
     * @return whether that context draws the weapon as a static model
     */
    public static boolean rendersStatically(String contextName) {
        return contextName != null && STATIC.contains(contextName);
    }

    /**
     * @param contextName a serialized display-context name, may be {@code null}
     * @return whether that context belongs to the animated first-person renderer
     */
    public static boolean isFirstPerson(String contextName) {
        return contextName != null && FIRST_PERSON.contains(contextName);
    }

    /**
     * @return the contexts this policy renders statically, for diagnostics and tests
     */
    public static Set<String> staticContexts() {
        return STATIC;
    }
}
