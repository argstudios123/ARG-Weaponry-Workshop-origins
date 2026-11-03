package com.arg_studios.arg_weaponry.client;

import com.arg_studios.arg_weaponry.client.aiming.WeaponAiming;
import com.arg_studios.arg_weaponry.client.animation.WeaponViewControllers;
import com.arg_studios.arg_weaponry.client.audio.ActionSoundHandles;
import com.arg_studios.arg_weaponry.client.camera.FirstPersonCamera;
import com.arg_studios.arg_weaponry.client.input.ClientReloadSequence;
import com.arg_studios.arg_weaponry.client.input.WeaponFireKey;
import com.arg_studios.arg_weaponry.client.recoil.WeaponRecoil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The single client-tick entry point of the mod's per-frame systems.
 *
 * <h2>Why one entry point</h2>
 * <p>Both loaders call exactly this method from their client tick. Combat and view features therefore cannot drift apart,
 * and a feature cannot be forgotten on one loader: Fabric and NeoForge run the same code in the same order.</p>
 *
 * <h2>What runs, and in which order</h2>
 * <ol>
 *   <li>the held weapons' animation timelines - the owner of playback time, which is what lets a reload or an inspection
 *   keep running while the first-person renderer is not drawing,</li>
 *   <li>the staged clips of an accepted reload - each clip starts at the moment the server planned, so a shell-by-shell
 *   reload animates stage by stage. It runs after the timelines so a clip that just expired is released first and the
 *   next stage starts on a clean controller instead of being swallowed by the same-clip no-op,</li>
 *   <li>the camera-bone transform, evaluated from the pose just advanced so the level camera and the view model share one
 *   frame,</li>
 *   <li>the aim-down-sights transition,</li>
 *   <li>recoil recovery,</li>
 *   <li>the fire key's press, hold and release edges,</li>
 *   <li>cleanup when the player dies or leaves.</li>
 * </ol>
 *
 * <p>Nothing here draws, and nothing here sends a packet on its own account: firing requests still go through the 10Q
 * trigger protocol, reload and inspection through their own server-authoritative requests.</p>
 */
public final class WeaponClientTick {

    private static long lastTickNanos;

    /** The perspective of the previous tick, so a change can clear state that belongs to the old one. */
    private static net.minecraft.client.CameraType lastCameraType;

    private WeaponClientTick() {
    }

    /**
     * Runs one client tick of every weapon view and input system.
     *
     * @param minecraft the client, may be {@code null}
     */
    public static void tick(Minecraft minecraft) {
        double delta = elapsed();
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            reset();
            return;
        }
        if (!player.isAlive()) {
            // Death ends every action: the clips, the tracked audio, the aim transition and the recoil response.
            ActionSoundHandles.reset();
            WeaponAiming.reset();
            WeaponRecoil.reset();
            FirstPersonCamera.clear();
            return;
        }
        // A perspective change must not leave a stale recoil transform or aim transition behind: entering or leaving first
        // person resets both layers, and the weapon repaints from the ordinary pose.
        net.minecraft.client.CameraType cameraType = minecraft.options.getCameraType();
        if (lastCameraType != null && lastCameraType != cameraType) {
            WeaponRecoil.reset();
        }
        lastCameraType = cameraType;

        WeaponViewControllers.instance().tickHeldWeapons();
        ClientReloadSequence.tick();
        WeaponAiming.clientTick(minecraft, delta);
        WeaponRecoil.clientTick(minecraft, delta);

        WeaponFireKey.handleQueuedPresses();
        WeaponFireKey.handleHeld();
        WeaponFireKey.handleReleased();
    }

    /**
     * Clears every client-side weapon system; called when leaving a world or a server.
     */
    public static void reset() {
        lastTickNanos = 0L;
        lastCameraType = null;
        WeaponViewControllers.instance().reset();
        WeaponAiming.reset();
        WeaponRecoil.reset();
        ActionSoundHandles.reset();
        FirstPersonCamera.clear();
    }

    /**
     * @return the seconds elapsed since the previous tick, defaulting to one tick when there is no previous sample
     */
    private static double elapsed() {
        long now = System.nanoTime();
        long previous = lastTickNanos;
        lastTickNanos = now;
        if (previous == 0L) {
            return 0.05;
        }
        double delta = (now - previous) / 1_000_000_000.0;
        return Double.isFinite(delta) && delta > 0.0 ? Math.min(delta, 0.25) : 0.05;
    }

}
