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
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Fabric client entry point.
 *
 * <p>Registers the client resource-reload hook that invalidates the weapon render and animation caches, and -
 * only when the development animation tester is enabled - the development-only key binding that opens it. The
 * rendering itself is loader-independent and lives in the common module.</p>
 */

public final class ArgWeaponryFabricClient implements ClientModInitializer {

    /** Identifier of the reload listener, so other mods can order against it. */
    public static final Identifier RELOAD_LISTENER_ID =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_render_caches");

    /**
     * Development-only key that opens the animation tester; registered only when the tester is enabled.
     *
     * <p>The shortcut is <strong>Ctrl + Alt + J</strong>: this mapping supplies the J and
     * {@link AnimationTester#openScreenFromShortcut()} requires Ctrl and Alt to be held. Both loaders build the
     * binding from {@link AnimationTester#SHORTCUT_KEY}, so the key cannot drift between them.</p>
     */
    private static final KeyMapping ANIMATION_TESTER_KEY = new KeyMapping("key.arg_weaponry.animation_tester",
            AnimationTester.SHORTCUT_KEY, WeaponKeyCategory.CATEGORY);

    @Override
    public void onInitializeClient() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new WeaponCacheReloadListener());

        // Client half of the weapon handshake and firing: the receiver that answers the server's manifest, the
        // receiver that animates accepted shots, and the reset of the client-side firing state between worlds.
        FabricWeaponNetworking.registerClient();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            WeaponFireInput.reset();
            WeaponReloadInput.reset();
            WeaponFireModeInput.reset();
            WeaponInspectInput.reset();
            WeaponHeldLifecycle.reset();
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
     */
    private static final class WeaponCacheReloadListener implements SimpleSynchronousResourceReloadListener {

        @Override
        public Identifier getFabricId() {
            return RELOAD_LISTENER_ID;
        }

        @Override
        public void onResourceManagerReload(ResourceManager resourceManager) {
            // Single shared entry point: it also clears the animation cache and resets development playback.
            WeaponRenderer.invalidateCaches();
        }
    }
}