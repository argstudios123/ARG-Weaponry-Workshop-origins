package com.arg_studios.arg_weaponry.client.display;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

import java.util.Map;

/**
 * A parsed display configuration: which model, texture and animation resources belong to a weapon and
 * how the weapon is transformed in each display context.
 *
 * <p>The context keys are the vanilla serialized names ({@code thirdperson_righthand}, {@code gui},
 * {@code on_shelf}, …). They are kept as strings so that this data stays independent of the client and
 * can be parsed and tested without a running game; the renderer looks transforms up with
 * {@code ItemDisplayContext.getSerializedName()}.</p>
 *
 * @param id          the display resource this was read from
 * @param model       the geometry resource, or {@code null}
 * @param texture     the texture resource, or {@code null}
 * @param animations  the animation resource, or {@code null}
 * @param slot        the HUD slot texture resource, or {@code null}
 * @param transforms  the transforms per display context, keyed by serialized context name
 */
public record WeaponDisplay(ResourceId id, ResourceId model, ResourceId texture, ResourceId animations,
                            ResourceId slot, Map<String, DisplayTransform> transforms) {

    public WeaponDisplay {
        transforms = Map.copyOf(transforms);
    }

    /**
     * @param contextName a serialized display-context name, may be {@code null}
     * @return the declared transform, or {@link DisplayTransform#IDENTITY} when the context declares none
     */
    public DisplayTransform transform(String contextName) {
        if (contextName == null) {
            // An immutable map throws on a null key, and a caller asking about no context at all must get the safe default
            // rather than an exception from inside a renderer.
            return DisplayTransform.IDENTITY;
        }
        return transforms.getOrDefault(contextName, DisplayTransform.IDENTITY);
    }

    /** @return {@code true} when this display configuration can actually be rendered. */
    public boolean isRenderable() {
        return model != null && texture != null;
    }
}
