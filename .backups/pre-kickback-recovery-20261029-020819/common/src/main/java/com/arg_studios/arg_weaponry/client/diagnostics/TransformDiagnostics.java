package com.arg_studios.arg_weaponry.client.diagnostics;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.camera.CameraTransform;
import com.arg_studios.arg_weaponry.client.recoil.ViewShakeController;
import org.joml.Quaternionf;

/**
 * Opt-in tracing of the camera and recoil layers, for diagnosing transform problems without guessing.
 *
 * <h2>Why it exists</h2>
 * <p>The 10S jitter could not be attributed from the outside: the frame rate was healthy, the tests passed, and several
 * layers write to the camera. This class prints, at most {@link #MAX_LINES_PER_SECOND} times per second and only when it is
 * switched on, the values that distinguish the layers: the camera's own orientation before the weapon layers, the composed
 * orientation after the camera-bone animation, the camera-space transform itself, the screen-shake offset, and the recoil
 * layers. If a layer is applied twice, or an old absolute value is being re-applied, the trace shows it immediately.</p>
 *
 * <h2>How to switch it on</h2>
 * <p>Start the game with {@code -Darg_weaponry.debug_transforms=true} (or run {@code mvn}-style with
 * {@code -Darg_weaponry.debug_transforms}). Nothing is logged otherwise - no per-frame output in normal play, which is a hard
 * requirement of the hotfix.</p>
 */
public final class TransformDiagnostics {

    /** The system property that enables the trace. */
    public static final String PROPERTY = "arg_weaponry.debug_transforms";

    /** Hard cap on output, so even an enabled trace cannot flood the log. */
    public static final int MAX_LINES_PER_SECOND = 4;

    private static final long INTERVAL_NANOS = 1_000_000_000L / MAX_LINES_PER_SECOND;

    private static Boolean enabled;
    private static long lastCameraNanos;
    private static long lastRecoilNanos;
    private static int cameraLayersThisFrame;
    private static long frameStartNanos;

    private TransformDiagnostics() {
    }

    /** @return whether the trace is enabled for this session. */
    public static boolean enabled() {
        if (enabled == null) {
            enabled = Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
            if (enabled) {
                Constants.LOG.info("[ARG] Transform diagnostics enabled (-D{}=true); at most {} lines per second", PROPERTY,
                        MAX_LINES_PER_SECOND);
            }
        }
        return enabled;
    }

    /**
     * Records that the camera hook ran this frame.
     *
     * <p>Two calls within one rendered frame would mean the hook is applying the layers more than once, which is exactly the
     * kind of mistake the trace is meant to expose, so the count is part of the output.</p>
     */
    public static void beginFrame() {
        if (!enabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - frameStartNanos > 50_000_000L) {
            frameStartNanos = now;
            cameraLayersThisFrame = 1;
        } else {
            cameraLayersThisFrame++;
        }
    }

    /**
     * Traces one camera frame.
     *
     * @param partialTick the frame's partial tick
     * @param transform   the camera-bone transform, may be {@code null}
     * @param composedYaw the yaw the camera ends the frame with
     * @param composedPitch the pitch the camera ends the frame with
     * @param shake       the screen-shake offset of the frame
     */
    public static void camera(float partialTick, CameraTransform transform, float composedYaw, float composedPitch,
                              ViewShakeController.ShakeAngles shake) {
        if (!enabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastCameraNanos < INTERVAL_NANOS) {
            return;
        }
        lastCameraNanos = now;
        Quaternionf rotation = transform == null ? new Quaternionf()
                : new Quaternionf(transform.rotationX(), transform.rotationY(), transform.rotationZ(),
                        transform.rotationW());
        Constants.LOG.info("[ARG] camera frame: partial={} layers/frame={} composed=({}, {}) boneDelta=({}, {}, {}) "
                        + "quat=({}, {}, {}, {}) translation=({}, {}, {}) shake=({}, {}, {})",
                partialTick, cameraLayersThisFrame, composedYaw, composedPitch,
                transform == null ? 0.0F : transform.yaw(), transform == null ? 0.0F : transform.pitch(),
                transform == null ? 0.0F : transform.roll(),
                rotation.x, rotation.y, rotation.z, rotation.w,
                transform == null ? 0.0F : transform.translationX(), transform == null ? 0.0F : transform.translationY(),
                transform == null ? 0.0F : transform.translationZ(),
                shake.pitchDegrees(), shake.yawDegrees(), shake.rollDegrees());
    }

    /**
     * Traces one recoil tick.
     *
     * @param pitchBefore the player's pitch before this tick's recoil delta
     * @param pitchAfter  the player's pitch after it
     * @param yawBefore   the player's yaw before it
     * @param yawAfter    the player's yaw after it
     * @param deltaPitch  the delta applied this tick
     * @param deltaYaw    the delta applied this tick
     * @param kickback    the weapon kickback in blocks
     * @param shakeAmplitude the screen-shake amplitude in degrees
     */
    public static void recoil(float pitchBefore, float pitchAfter, float yawBefore, float yawAfter, double deltaPitch,
                              double deltaYaw, double kickback, double shakeAmplitude) {
        if (!enabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastRecoilNanos < INTERVAL_NANOS) {
            return;
        }
        lastRecoilNanos = now;
        Constants.LOG.info("[ARG] recoil tick: pitch {} -> {} (delta {}), yaw {} -> {} (delta {}), kickback {} blocks, "
                        + "shake {} degrees",
                pitchBefore, pitchAfter, deltaPitch, yawBefore, yawAfter, deltaYaw, kickback, shakeAmplitude);
    }

    /** Forgets the rate limiting, for tests. */
    public static void resetRateLimits() {
        lastCameraNanos = 0L;
        lastRecoilNanos = 0L;
        frameStartNanos = 0L;
        cameraLayersThisFrame = 0;
    }
}
