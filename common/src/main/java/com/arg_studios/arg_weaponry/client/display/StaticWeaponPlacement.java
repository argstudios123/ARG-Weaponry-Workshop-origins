package com.arg_studios.arg_weaponry.client.display;

/**
 * Composes the placement a static (non-first-person) weapon render uses.
 *
 * <h2>Two halves, applied in two places</h2>
 * <p>The placement is split so that each half lives where it belongs:</p>
 * <ol>
 *   <li><strong>the per-context display transform</strong> - authored by the content pack in {@code display_views} - becomes the
 *   item render state's <em>local transform</em> ({@link DisplayTransformMatrix}). Vanilla applies it before it calls the
 *   special renderer and also applies it to the reported extents, so the model and its bounding box cannot disagree;</li>
 *   <li><strong>the model-to-item normalisation</strong> - the anchor, the project scale and the Bedrock X handedness - is the
 *   first-person anchor frame ({@code ViewBoneTransform.placement(model, default_view)}), applied by the static renderer itself.
 *   Reusing that frame is what keeps the third-person gun the same size and orientation as the first-person one, and it is why
 *   this class does not carry a second set of magic numbers.</li>
 * </ol>
 *
 * <p>Both halves are unit-tested; the only thing that cannot be proven without the game running is whether a pack's authored
 * offsets place the model where its author intended, because that is an authoring choice, not a code path.</p>
 */
public final class StaticWeaponPlacement {

    private StaticWeaponPlacement() {
    }

    /**
     * @param display     the parsed display configuration
     * @param contextName the serialized display-context name, may be {@code null}
     * @return {@code true} when that context resolves to a transform that would draw nothing
     */
    public static boolean isHidden(WeaponDisplay display, String contextName) {
        if (display == null) {
            return true;
        }
        return DisplayTransformMatrix.isCollapsed(DisplayTransformMatrix.of(display.transform(contextName)));
    }
}
