package com.arg_studios.arg_weaponry.client.display;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

import java.util.Map;

/**
 * A parsed ammunition display definition: how one ammunition item should look.
 *
 * <p>The ammunition identity definition only names a display through its {@code display} reference; this
 * record is the separate document that reference points at, and it is where the rendering mode is chosen.</p>
 *
 * <h2>Two modes</h2>
 * <ul>
 *   <li>{@link Mode#ITEM} — a conventional 2D Minecraft item: the item is drawn by Minecraft's normal item
 *       model path. {@link #itemModel()} names the item id whose baked item model is reused, so no texture,
 *       geometry or new renderer is involved. This is the mode every ammunition item can use today.</li>
 *   <li>{@link Mode#MODEL} — a custom 3D model, using the project's established Bedrock geometry and texture
 *       resources ({@link #model()}, {@link #texture()}, optional {@link #animations()}). The definition is
 *       parsed, validated and resolved so the data is ready, but this milestone's item rendering still shows
 *       a 2D model: drawing Bedrock geometry as an item would mean a second item-model implementation inside
 *       the finished weapon renderer. A definition in this mode may also declare {@code item_model} to choose
 *       the 2D icon used meanwhile.</li>
 * </ul>
 *
 * <p>Per-context transforms are parsed with the same {@link DisplayTransform} type the weapon display uses and
 * keyed by vanilla's serialized {@code ItemDisplayContext} names.</p>
 *
 * @param id         the display resource this was read from
 * @param mode       which rendering mode the definition selected
 * @param itemModel  the item id whose baked model is used in {@link Mode#ITEM}, or {@code null}
 * @param model      the geometry resource for {@link Mode#MODEL}, or {@code null}
 * @param texture    the texture resource for {@link Mode#MODEL}, or {@code null}
 * @param animations the animation resource, or {@code null}
 * @param transforms the per-context transforms, keyed by serialized context name
 */
public record AmmunitionDisplay(ResourceId id, Mode mode, ResourceId itemModel, ResourceId model, ResourceId texture,
                                ResourceId animations, Map<String, DisplayTransform> transforms) {

    /** How an ammunition item should be drawn. */
    public enum Mode {

        /** Conventional 2D Minecraft item model. */
        ITEM,

        /** Custom Bedrock geometry (parsed and resolved; not drawn by an item renderer in this milestone). */
        MODEL
    }

    public AmmunitionDisplay {
        transforms = Map.copyOf(transforms);
    }

    /** @return {@code true} when the definition chooses a conventional 2D item. */
    public boolean isItemMode() {
        return mode == Mode.ITEM;
    }

    /** @return {@code true} when the definition chooses a custom 3D model. */
    public boolean isModelMode() {
        return mode == Mode.MODEL;
    }

    /** @return {@code true} when the 3D resources needed to build a mesh are both declared. */
    public boolean isThreeDimensionalRenderable() {
        return isModelMode() && model != null && texture != null;
    }

    /**
     * @param contextName a serialized display-context name
     * @return the declared transform, or {@link DisplayTransform#IDENTITY} when the context declares none
     */
    public DisplayTransform transform(String contextName) {
        return transforms.getOrDefault(contextName, DisplayTransform.IDENTITY);
    }
}
