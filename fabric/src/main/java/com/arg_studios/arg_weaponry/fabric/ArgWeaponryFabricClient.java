package com.arg_studios.arg_weaponry.fabric;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.animation.AnimationTester;
import com.arg_studios.arg_weaponry.client.WeaponClientTick;
import com.arg_studios.arg_weaponry.client.input.WeaponAimKey;
import com.arg_studios.arg_weaponry.client.input.WeaponFireInput;
import com.arg_studios.arg_weaponry.client.input.WeaponFireKey;
import com.arg_studios.arg_weaponry.client.audio.WeaponHeldLifecycle;
import com.arg_studios.arg_weaponry.client.camera.FirstPersonCamera;
import com.arg_studios.arg_weaponry.client.input.WeaponFireModeInput;
import com.arg_studios.arg_weaponry.client.input.WeaponInspectInput;
import com.arg_studios.arg_weaponry.client.input.WeaponInspectKey;
import com.arg_studios.arg_weaponry.client.input.WeaponKeyCategory;
import com.arg_studios.arg_weaponry.client.input.WeaponFireModeKey;
import com.arg_studios.arg_weaponry.client.input.WeaponReloadInput;
import com.arg_studios.arg_weaponry.client.input.WeaponReloadKey;
import com.arg_studios.arg_weaponry.client.render.WeaponRenderer;
import com.arg_studios.arg_weaponry.fabric.network.FabricWeaponNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;



public final class ArgWeaponryFabricClient implements ClientModInitializer {

    /** Identifier of the reload listener, so other mods can order against it. */
    public static final Identifier RELOAD_LISTENER_ID =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_render_caches");


    private static final KeyMapping ANIMATION_TESTER_KEY = new KeyMapping("key.arg_weaponry.animation_tester",
            AnimationTester.SHORTCUT_KEY, WeaponKeyCategory.CATEGORY);

    @Override
    public void onInitializeClient() {
        // Fabric API 0.160+ moved reload listeners to the versioned ResourceLoader: its
        // ResourceManagerHelper/PackType API is deprecated. The listener id is the same one other mods can
        // order against, and the callback still runs on the client resource-reload thread.
        ResourceLoader.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(RELOAD_LISTENER_ID, new WeaponCacheReloadListener());

        // Client half of the weapon handshake and firing: the receiver that answers the server's manifest, the
        // receiver that animates accepted shots, and the reset of the client-side firing state between worlds.
        FabricWeaponNetworking.registerClient();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            WeaponFireInput.reset();
            WeaponReloadInput.reset();
            WeaponFireModeInput.reset();
            WeaponInspectInput.reset();
            WeaponHeldLifecycle.reset();
            // [DrawHolster hook] Forgets the local equip windows, the pending switch and the first-draw guard.
            com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.clearClient();
            FirstPersonCamera.reset();
            WeaponClientTick.reset();
        });

        // Firing and aiming get their own rebindable bindings under the ARG Weaponry category. Firing drives the existing
        // 10Q trigger protocol (press, hold and release), aiming is hold-to-aim; vanilla's attack and use actions are
        // suppressed by the shared client mixins while a weapon is held, so a click is never processed twice.
        KeyMappingHelper.registerKeyMapping(WeaponFireKey.KEY);
        KeyMappingHelper.registerKeyMapping(WeaponAimKey.KEY);

        // Reloading input: the R binding is always registered, and its queued presses are drained once per
        // client tick so holding the key cannot repeat a request.
        KeyMappingHelper.registerKeyMapping(WeaponReloadKey.KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> WeaponReloadKey.handleQueuedPresses());

        // Firing-mode selection: B cycles the held weapon's authored firing modes. The queued presses are drained once
        // per client tick, and the trigger safety net runs every tick so a lost input never leaves a weapon firing.
        KeyMappingHelper.registerKeyMapping(WeaponFireModeKey.KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            WeaponFireModeKey.handleQueuedPresses();
            WeaponFireInput.clientTick(client);
        });

        // Inspection (I) and the weapon lifecycle that the draw/holster sounds hang off, plus the camera-animation
        // staleness check. None of these touch the reload (R), fire-mode (B) or animation-tester bindings.
        KeyMappingHelper.registerKeyMapping(WeaponInspectKey.KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            WeaponInspectKey.handleQueuedPresses();
            WeaponHeldLifecycle.clientTick(client);
            FirstPersonCamera.clientTick(client);
            // [DrawHolster hook] The equip state machine runs before the shared tick, so its clip is already playing when the
            // animation controller advances.
            com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.clientTick(client);
            // The shared tick: held-weapon animation timelines, the camera-bone transform, the aim transition, recoil
            // recovery and the fire key's edges. Both loaders call this same method, in the same order.
            WeaponClientTick.tick(client);
        });

        if (AnimationTester.instance().isEnabled()) {
            KeyMappingHelper.registerKeyMapping(ANIMATION_TESTER_KEY);
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                while (ANIMATION_TESTER_KEY.consumeClick()) {
                    AnimationTester.instance().openScreenFromShortcut();
                }
            });
            Constants.LOG.info("[ARG] Development animation tester enabled (shortcut: Ctrl + Alt + J while holding "
                    + "a weapon)");
        }
    }

    /**
     * Drops the parsed model, display, texture and animation caches whenever client resources are reloaded.
     *
     * <p>Content packs are read from their own directory rather than through the vanilla resource
     * manager, so this listener is the single point at which edited pack files are picked up.</p>
     *
     * <p>It implements vanilla's {@link ResourceManagerReloadListener} rather than the retired Fabric
     * {@code SimpleSynchronousResourceReloadListener}: the identifier now comes from
     * {@link ResourceLoader#registerReloadListener(Identifier, net.minecraft.server.packs.resources.PreparableReloadListener)},
     * which is the supported API in Fabric API 0.160+.</p>
     */
    private static final class WeaponCacheReloadListener
            implements net.minecraft.server.packs.resources.ResourceManagerReloadListener {

        @Override
        public void onResourceManagerReload(ResourceManager resourceManager) {
            // Single shared entry point: it also clears the animation cache and resets development playback.
            WeaponRenderer.invalidateCaches();
        }
    }
}