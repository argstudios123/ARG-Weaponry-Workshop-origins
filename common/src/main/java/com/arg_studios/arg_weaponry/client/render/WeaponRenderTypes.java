package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The two non-default pipelines ARG draws with, resolved once per texture.
 *
 * <h2>Why the mod goes through vanilla render types</h2>
 * <p>Neither Sodium nor Iris needs a compatibility hook for the weapon: ARG never issues an OpenGL call, never builds a
 * {@code RenderPipeline} of its own and never touches a GL buffer. It submits geometry through
 * {@link net.minecraft.client.renderer.SubmitNodeCollector} with a {@link RenderType}, exactly like vanilla's own entity
 * layers do. Whatever a rendering mod replaces underneath - Sodium's terrain and chunk passes, its vertex and buffer
 * handling, Iris' shader programs, a Vulkan backend's command encoder - stays vanilla's business, because both the
 * submissions and the pipelines are vanilla objects.</p>
 *
 * <p>Using the vanilla <em>factories</em> rather than hand-built render types also keeps two properties a hand-built type
 * would silently lose:</p>
 * <ul>
 *   <li>the matching order-independent-transparency pipeline set
 *   ({@code RenderPipelines.OIT_ENTITY_EMISSIVE} / {@code OIT_ENERGY_SWIRL}). Vanilla's improved-transparency mode and
 *   Sodium's translucency handling both select those sets; a render type without one is simply not drawn in that mode,
 *   which would look like a missing weapon;</li>
 *   <li>the vanilla memoization, so one texture produces one render type instance. Iris maps render types to shader
 *   programs by identity, and a freshly built instance per frame would grow that mapping without bound.</li>
 * </ul>
 *
 * <h2>Shader packs</h2>
 * <p>With Iris and a shader pack, the pack decides what {@code entity_translucent_emissive} and the additive emissive
 * pipeline look like. The emissive flag belongs to the pipeline the pack is handed, so a pack that supports emissive
 * geometry keeps the glow; a pack that replaces it with its own lighting model may render the section as ordinary
 * geometry. That is a property of the pack, not something this mod can or should override. The same applies to the muzzle
 * flash: the additive pipeline is the vanilla one, so a pack that supports additive sprites draws it, and one that does
 * not may draw it dimmer or not at all.</p>
 *
 * <h2>Graphics backends</h2>
 * <p>The same reasoning covers a graphics API other than OpenGL. Modern Minecraft - and therefore any backend mod, whether
 * it is Sodium's own renderer, a Vulkan backend such as VulkanMod, or something else - receives its geometry through
 * {@code RenderPipeline} and {@code GpuDevice} abstractions. ARG uses only those: it hands vertices to a
 * {@link net.minecraft.client.renderer.SubmitNodeCollector} with a {@link RenderType} and uploads its content-pack
 * textures through a {@code DynamicTexture}. There is not a single {@code GL11}/{@code GL30} call, no hand-written vertex
 * format and no manual buffer management anywhere in the mod, so the backend keeps full control of how that geometry
 * reaches the GPU.</p>
 *
 * <p>What ARG cannot do is promise that a backend supports a given <em>feature combination</em> - additive blending
 * against order-independent transparency, for example. If a backend or a renderer mod cannot create one of these
 * pipelines, the {@link PipelineFallback} chain degrades the effect to a plain vanilla type and logs it once, rather than
 * guessing at a backend-specific workaround. A limitation like that belongs upstream and is reported, not worked
 * around.</p>
 */
public final class WeaponRenderTypes {

    private static final Map<Identifier, RenderType> GLOW = new HashMap<>();
    private static final Map<Identifier, RenderType> FLASH = new HashMap<>();

    /** Findings already logged, so a degraded pipeline cannot spam the log once per frame. */
    private static final Set<String> REPORTED = new HashSet<>();

    private WeaponRenderTypes() {
    }

    /**
     * The pipeline for a glowing weapon section: vanilla's emissive entity type.
     *
     * <p>{@code RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE} defines {@code EMISSIVE} (the lightmap is not sampled, so the
     * section keeps the brightness of its own pixels), {@code ALPHA_CUTOUT} 0.1 (the atlas padding is still discarded),
     * {@code PER_FACE_LIGHTING} (the section keeps the weapon's shape shading) and a blending colour target (so it is drawn
     * after the opaque body).</p>
     *
     * <p><strong>Fallback.</strong> A rendering mod, a resource reload in progress or an incomplete pipeline registry can make
     * the factory throw.
     * Losing a weapon because a glow pipeline is unavailable is not acceptable, so the resolution falls back to the plain
     * cutout type the weapon body already uses: the section then renders as ordinary geometry instead of disappearing, and
     * the fallback is logged once per texture.</p>
     *
     * @param texture the weapon atlas
     * @return the emissive pipeline, or the cutout pipeline when the emissive one is unavailable
     */
    public static RenderType glowing(Identifier texture) {
        synchronized (GLOW) {
            RenderType cached = GLOW.get(texture);
            if (cached != null) {
                return cached;
            }
            RenderType resolved = resolve("glowing part", texture,
                    () -> RenderTypes.entityTranslucentEmissive(texture),
                    () -> RenderTypes.entityCutout(texture));
            GLOW.put(texture, resolved);
            return resolved;
        }
    }

    /**
     * The pipeline for a muzzle flash: vanilla's additive emissive entity type.
     *
     * <p>{@code energySwirl} is the public factory of {@code RenderPipelines.ENERGY_SWIRL} ({@code EMISSIVE},
     * {@code NO_CARDINAL_LIGHTING}, additive blending), i.e. a sprite that brightens what is behind it instead of being
     * shaded like metal. The offset passed is zero: the sprite-sheet frame is selected through the vertex UVs, so the
     * render type's texture transform stays the identity.</p>
     *
     * @param sheet the uploaded sprite sheet
     * @return the additive pipeline, or a plainer type when it is unavailable
     */
    public static RenderType muzzleFlash(Identifier sheet) {
        synchronized (FLASH) {
            RenderType cached = FLASH.get(sheet);
            if (cached != null) {
                return cached;
            }
            RenderType resolved = resolve("muzzle flash", sheet,
                    () -> RenderTypes.energySwirl(sheet, 0.0F, 0.0F),
                    () -> RenderTypes.entityTranslucentEmissive(sheet),
                    () -> RenderTypes.entityCutout(sheet));
            FLASH.put(sheet, resolved);
            return resolved;
        }
    }

    /** Drops every resolved type and finding; called on a resource reload, together with the texture cache. */
    public static void invalidate() {
        synchronized (GLOW) {
            GLOW.clear();
        }
        synchronized (FLASH) {
            FLASH.clear();
        }
        synchronized (REPORTED) {
            REPORTED.clear();
        }
    }

    /** @return the number of resolved glow pipelines, for diagnostics. */
    public static int glowCount() {
        synchronized (GLOW) {
            return GLOW.size();
        }
    }

    /** @return the number of resolved flash pipelines, for diagnostics. */
    public static int flashCount() {
        synchronized (FLASH) {
            return FLASH.size();
        }
    }

    /**
     * Resolves the first factory that works.
     *
     * @param what     the effect being resolved, for the diagnostic
     * @param texture  the texture the pipeline is bound to, for the diagnostic
     * @param attempts the factories, most preferred first; the last one is expected to be the plainest
     * @return the first pipeline that could be built
     * @throws IllegalStateException when even the last factory fails, which means no weapon can be drawn at all
     */
    @SafeVarargs
    private static RenderType resolve(String what, Identifier texture, Factory... attempts) {
        List<Supplier<RenderType>> chain = new ArrayList<>(attempts.length);
        for (Factory attempt : attempts) {
            chain.add(attempt::create);
        }
        return PipelineFallback.firstAvailable(what + " of texture '" + texture + "'", chain,
                failure -> report("fallback|" + what + "|" + texture, "The " + what + " pipeline for texture '" + texture
                        + "' fell back to a simpler vanilla render type because the preferred one could not be created ("
                        + failure + ")"));
    }

    private static void report(String key, String message) {
        synchronized (REPORTED) {
            if (REPORTED.add(key)) {
                Constants.LOG.warn("[ARG] {}", message);
            }
        }
    }

    /** One render-type factory attempt. */
    @FunctionalInterface
    private interface Factory {
        RenderType create();
    }
}
