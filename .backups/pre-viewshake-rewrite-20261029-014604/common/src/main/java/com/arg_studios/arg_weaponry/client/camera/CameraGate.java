package com.arg_studios.arg_weaponry.client.camera;

/**
 * Decides whether a camera-bone animation may touch the camera at all.
 *
 * <p>The rule is deliberately tiny and explicit, and it is the one thing that must never be relaxed: a camera animation is
 * a <strong>first-person</strong> feature. It applies only when</p>
 * <ol>
 *   <li>the active perspective is first person (front or rear third-person never qualifies, and the vanilla
 *   {@code CameraType#isMirrored} rear view is excluded by that same test),</li>
 *   <li>the camera is not detached, which is how this version marks "not the player's own eyes",</li>
 *   <li>the camera follows the local player - not a spectated entity, a free camera or a mount,</li>
 *   <li>and a valid transform exists for the weapon that is actually held.</li>
 * </ol>
 * <p>Keeping the test here, instead of inside the mixin, is what makes it verifiable without a running client: the mixin only
 * asks this class, and the tests drive every combination.</p>
 */
public final class CameraGate {

    private CameraGate() {
    }

    /**
     * @param firstPerson            whether the active camera type is first person
     * @param detached               whether the camera is detached from its entity
     * @param cameraFollowsLocalPlayer whether the camera entity is the client's own player
     * @param hasTransform           whether a valid transform is available for the held weapon
     * @return whether the transform may be applied to the camera this frame
     */
    public static boolean mayApply(boolean firstPerson, boolean detached, boolean cameraFollowsLocalPlayer,
                                   boolean hasTransform) {
        return firstPerson && !detached && cameraFollowsLocalPlayer && hasTransform;
    }

    /**
     * @param hasTransform whether a valid transform exists at all
     * @return whether the first-person view model has to compensate for it
     */
    public static boolean mayApplyToViewModel(boolean hasTransform) {
        return hasTransform;
    }
}
