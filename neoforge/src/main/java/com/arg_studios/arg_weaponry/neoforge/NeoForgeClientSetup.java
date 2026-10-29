package com.arg_studios.arg_weaponry.neoforge;

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
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge client-only setup.
 *
 * <p>This class is only loaded when the game runs as a client, so no client-only type is touched on a
 * dedicated server. It registers the resource-reload hook that invalidates the weapon render caches and the
 * animation resource cache, mirroring the Fabric client entry point.</p>
 */
public final class NeoForgeClientSetup {

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

    private NeoForgeClientSetup() {
    }

    /**
     * Subscribes the client reload listener and, when the development tester is enabled, its key binding.
     *
     * @param modEventBus the mod event bus of this mod
     */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(NeoForgeClientSetup::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientSetup::onLoggingOut);
        // Reloading input is always registered; the development tester only adds its own binding.
        modEventBus.addListener(NeoForgeClientSetup::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientSetup::onClientTick);
        if (AnimationTester.instance().isEnabled()) {
            Constants.LOG.info("[ARG] Development animation tester enabled (shortcut: Ctrl + Alt + J while holding "
                    + "a weapon)");
        }
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(WeaponReloadKey.KEY);
        event.register(WeaponFireModeKey.KEY);
        event.register(WeaponInspectKey.KEY);
        // The two dedicated combat bindings: fire on the left button and aim on the right, both under the mod's own
        // category and both rebindable. Vanilla's attack and use actions are suppressed by the shared client mixins while a
        // weapon is held, so one click can never be processed by two paths.
        event.register(WeaponFireKey.KEY);
        event.register(WeaponAimKey.KEY);
        if (AnimationTester.instance().isEnabled()) {
            event.register(ANIMATION_TESTER_KEY);
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        WeaponReloadKey.handleQueuedPresses();
        WeaponFireModeKey.handleQueuedPresses();
        WeaponFireInput.clientTick(net.minecraft.client.Minecraft.getInstance());
        WeaponInspectKey.handleQueuedPresses();
        WeaponHeldLifecycle.clientTick(net.minecraft.client.Minecraft.getInstance());
        FirstPersonCamera.clientTick(net.minecraft.client.Minecraft.getInstance());
        // [DrawHolster hook] The equip state machine runs before the shared tick, so its clip is already playing when the
        // animation controller advances.
        com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.clientTick(
                net.minecraft.client.Minecraft.getInstance());
        // The shared tick: held-weapon animation timelines, the camera-bone transform, the aim transition, recoil recovery
        // and the fire key's edges. Fabric calls the same method from its own client tick.
        WeaponClientTick.tick(net.minecraft.client.Minecraft.getInstance());
        if (AnimationTester.instance().isEnabled()) {
            while (ANIMATION_TESTER_KEY.consumeClick()) {
                AnimationTester.instance().openScreenFromShortcut();
            }
        }
    }

    /**
     * Forgets the client-side firing state when leaving a world.
     *
     * @param event the disconnect event
     */
    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WeaponFireInput.reset();
        WeaponReloadInput.reset();
        WeaponFireModeInput.reset();
        WeaponInspectInput.reset();
        WeaponHeldLifecycle.reset();
        // [DrawHolster hook] Forgets the local equip windows, the pending switch and the first-draw guard.
        com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.clearClient();
        FirstPersonCamera.reset();
        WeaponClientTick.reset();
    }

    private static void onAddReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(RELOAD_LISTENER_ID, new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                // Single shared entry point: it also clears the animation cache and resets development playback.
                WeaponRenderer.invalidateCaches();
            }
        });
    }
}
