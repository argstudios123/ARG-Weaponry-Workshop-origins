package com.arg_studios.arg_weaponry.client.display;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

/**
 * Canonicalises the {@code slot} reference of a weapon display configuration.
 *
 * <h2>The one thing this class decides</h2>
 * <p>A display file names its HUD slot icon as a <em>texture id</em> ({@code arg_pack:gui/hud/slots/m100_dmr}), which is what
 * both consumers need: the file the pack contains is {@code assets/<namespace>/textures/<path>.png}, and the model document
 * that is synthesised for the item uses the id as its {@code layer0}. Authors write the path they see in the pack instead -
 * {@code arg_pack:textures/gui/hud/slots/m100_dmr}, usually with the {@code .png} extension - and Minecraft then looks for
 * {@code assets/arg_pack/textures/textures/gui/hud/slots/m100_dmr.png}, which does not exist. The declared texture was
 * therefore dropped, the item kept the placeholder model, and the icon never appeared.</p>
 *
 * <p>Both spellings, with or without the extension, are accepted here, so existing content packs keep working unchanged and a
 * file-style path resolves to the icon its author meant.</p>
 */
public final class SlotTextureId {

    private static final String TEXTURES_PREFIX = "textures/";
    private static final String ASSETS_PREFIX = "assets/";
    private static final String PNG_EXTENSION = ".png";

    private SlotTextureId() {
    }

    /**
     * @param declared the declared slot reference, may be {@code null}
     * @return the same id with a leading {@code textures/} (and an optional {@code assets/<namespace>/textures/} prefix) and a
     *         trailing {@code .png} removed, or {@code null} when nothing was declared
     */
    public static ResourceId canonical(ResourceId declared) {
        if (declared == null) {
            return null;
        }
        String path = declared.path();
        boolean changed = false;
        if (path.endsWith(PNG_EXTENSION)) {
            path = path.substring(0, path.length() - PNG_EXTENSION.length());
            changed = true;
        }
        String assetsPrefix = ASSETS_PREFIX + declared.namespace() + "/";
        if (path.startsWith(assetsPrefix)) {
            path = path.substring(assetsPrefix.length());
            changed = true;
        }
        if (path.startsWith(TEXTURES_PREFIX)) {
            path = path.substring(TEXTURES_PREFIX.length());
            changed = true;
        }
        if (!changed || path.isBlank()) {
            return declared;
        }
        return new ResourceId(declared.namespace(), path);
    }
}
