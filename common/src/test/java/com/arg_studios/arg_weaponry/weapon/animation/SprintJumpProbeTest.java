package com.arg_studios.arg_weaponry.weapon.animation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/** TEMPORARY diagnostic: drives a sprint-jump through the real shipped clips and prints frame deltas. */
class SprintJumpProbeTest {

    private static final double TICK = 0.05;
    private static final String PACK = "assets/arg_weaponry/weapon_packs/arg_default_pack/assets/arg_pack/";
    private static final String ANIMATIONS = PACK
            + "animations/weapons/dmrs/m100_dmr/m100_dmr.animation.json";

    private Vec3 lastPos;
    private Vec3 lastRot;
    private String lastState;
    private double lastY;

    @Test
    void probe() throws Exception {
        AnimationFile file;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(ANIMATIONS)) {
            file = BedrockAnimationParser.parse("arg_default_pack", new ResourceId("arg_pack", "m100_dmr"),
                    ANIMATIONS, JsoncReader.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject(),
                    new ValidationReport());
        }
        MovementLayer layer = new MovementLayer();
        layer.bind(file);

        double[] vy = {0.42, 0.33, 0.24, 0.15, 0.06, -0.03, -0.12, -0.21, -0.30, -0.39, -0.48, -0.57};
        // 40 ticks sprinting on the ground: reach RUNNING and settle.
        for (int i = 0; i < 40; i++) {
            step(layer, state(1, 0, true, false, 0.0), "warmup" + i);
            sample(layer, false);
        }
        // Repeat a full sprint-jump cycle: 12 airborne ticks, then 10 ground ticks.
        for (int jump = 0; jump < 4; jump++) {
            for (int i = 0; i < vy.length; i++) {
                step(layer, state(1, 0, true, true, vy[i]), "jump" + jump + "t" + i);
                sample(layer, false);
            }
            for (int i = 0; i < 10; i++) {
                step(layer, state(1, 0, true, false, 0.0), "ground" + jump + "t" + i);
                sample(layer, false);
            }
        }
    }

    @Test
    void descentTrace() throws Exception {
        AnimationFile file;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(ANIMATIONS)) {
            file = BedrockAnimationParser.parse("arg_default_pack", new ResourceId("arg_pack", "m100_dmr"),
                    ANIMATIONS, JsoncReader.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject(),
                    new ValidationReport());
        }
        MovementLayer layer = new MovementLayer();
        layer.bind(file);
        for (int i = 0; i < 40; i++) {
            layer.update(state(1, 0, true, false, 0.0), TICK);
        }
        double[] vy = {0.42, 0.33, 0.24, 0.15, 0.06, -0.03, -0.12, -0.21, -0.30, -0.39, -0.48, -0.57};
        for (int t = 0; t < vy.length; t++) {
            layer.update(state(1, 0, true, true, vy[t]), TICK);
            System.out.printf("TICK %d vy=%.2f resp=%.3f state=%s%n", t, vy[t], layer.verticalResponse(), layer.state());
            for (int i = 0; i < 8; i++) {
                double offset = i * TICK / 8.0;
                WeaponPose pose = layer.pose(offset);
                Vec3 p = pose.bone("main").position();
                System.out.printf("   i=%d off=%.4f y=%.3f x=%.3f rotY=%.2f%n", i, offset, p.y(), p.x(),
                        pose.bone("main").rotation().y());
            }
        }
    }

    @Test
    void scenarios() throws Exception {
        MovementLayer layer = scenarioLayer();
        // A: sprint forward, four full jumps with a normal ground gap.
        runScenario("A sprint forward", layer, 1, 0, 40, 12, 10, 8);
        // B: sprint + strafe, same cycle.
        layer = scenarioLayer();
        runScenario("B sprint forward+right", layer, 1, 1, 40, 12, 10, 8);
        // C: jump immediately after starting to sprint (the prepare phase is still running).
        layer = scenarioLayer();
        runScenario("C sprint from prepare", layer, 1, 0, 2, 12, 10, 8);
        // D: rapid jumps with only three ground ticks between them.
        layer = scenarioLayer();
        runScenario("D rapid sprint jumps", layer, 1, 0, 40, 12, 3, 8);
        // E: the same cycle sampled with only two frames per tick.
        layer = scenarioLayer();
        runScenario("E low frame rate sampling", layer, 1, 0, 40, 12, 10, 2);
        // F: the same cycle sampled with 32 frames per tick.
        layer = scenarioLayer();
        runScenario("F high frame rate sampling", layer, 1, 1, 40, 12, 10, 32);
    }

    private MovementLayer scenarioLayer() throws Exception {
        AnimationFile file;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(ANIMATIONS)) {
            file = BedrockAnimationParser.parse("arg_default_pack", new ResourceId("arg_pack", "m100_dmr"),
                    ANIMATIONS, JsoncReader.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject(),
                    new ValidationReport());
        }
        MovementLayer layer = new MovementLayer();
        layer.bind(file);
        return layer;
    }

    private void runScenario(String name, MovementLayer layer, double forward, double right, int warmupTicks,
                             int airTicks, int groundTicks, int frames) {
        for (int i = 0; i < warmupTicks; i++) {
            layer.update(state(forward, right, true, false, 0.0), TICK);
        }
        lastPos = null;
        lastRot = null;
        int worst = 0;
        double worstD = 0;
        double worstA = 0;
        for (int jump = 0; jump < 4; jump++) {
            for (int i = 0; i < airTicks; i++) {
                double progress = (double) i / airTicks;
                double vy = 0.42 - progress * 1.0;
                layer.update(state(forward, right, true, true, vy), TICK);
                double[] delta = sampleWorst(layer, frames);
                if (delta[0] > worstD) {
                    worstD = delta[0];
                }
                if (delta[1] > worstA) {
                    worstA = delta[1];
                }
                if (delta[0] > 0.35 || delta[1] > 8.0) {
                    worst++;
                }
            }
            for (int i = 0; i < groundTicks; i++) {
                layer.update(state(forward, right, true, false, 0.0), TICK);
                double[] delta = sampleWorst(layer, frames);
                if (delta[0] > worstD) {
                    worstD = delta[0];
                }
                if (delta[1] > worstA) {
                    worstA = delta[1];
                }
                if (delta[0] > 0.35 || delta[1] > 8.0) {
                    worst++;
                }
            }
        }
        System.out.printf("%-30s worst d=%.3f rot=%.1f frames>threshold=%d%n", name, worstD, worstA, worst);
    }

    /** @return the largest position and rotation step between consecutive sub-frames of this tick. */
    private double[] sampleWorst(MovementLayer layer, int frames) {
        double maxD = 0;
        double maxA = 0;
        for (int i = 0; i < frames; i++) {
            double offset = i * TICK / frames;
            WeaponPose pose = layer.pose(offset);
            if (pose == null || pose.bone("main") == null) {
                continue;
            }
            Vec3 p = pose.bone("main").position();
            Vec3 r = pose.bone("main").rotation();
            if (lastPos != null) {
                maxD = Math.max(maxD, Math.sqrt(Math.pow(p.x() - lastPos.x(), 2) + Math.pow(p.y() - lastPos.y(), 2)
                        + Math.pow(p.z() - lastPos.z(), 2)));
                maxA = Math.max(maxA, WeaponPoseBlend.angleDegrees(lastRot, r));
            }
            lastPos = p;
            lastRot = r;
        }
        return new double[]{maxD, maxA};
    }

    @Test
    void rapidTrace() throws Exception {
        MovementLayer layer = scenarioLayer();
        for (int i = 0; i < 40; i++) {
            layer.update(state(1, 0, true, false, 0.0), TICK);
        }
        lastPos = null;
        lastRot = null;
        java.lang.reflect.Field landField = MovementLayer.class.getDeclaredField("landClip");
        landField.setAccessible(true);
        for (int jump = 0; jump < 3; jump++) {
            for (int i = 0; i < 12; i++) {
                double vy = 0.42 - (double) i / 12;
                layer.update(state(1, 0, true, true, vy), TICK);
                traceTick(layer, landField, "j" + jump + "a" + i);
            }
            for (int i = 0; i < 3; i++) {
                layer.update(state(1, 0, true, false, 0.0), TICK);
                traceTick(layer, landField, "j" + jump + "g" + i);
            }
        }
    }

    private void traceTick(MovementLayer layer, java.lang.reflect.Field landField, String label) throws Exception {
        System.out.printf("TICK %-6s state=%s land=%s resp=%.3f%n", label, layer.state(), landField.get(layer),
                layer.verticalResponse());
        for (int i = 0; i < 8; i++) {
            double offset = i * TICK / 8.0;
            WeaponPose pose = layer.pose(offset);
            Vec3 p = pose.bone("main").position();
            System.out.printf("   i=%d y=%.3f x=%.3f rotX=%.2f rotY=%.2f%n", i, p.y(), p.x(),
                    pose.bone("main").rotation().x(), pose.bone("main").rotation().y());
        }
    }

    private static MovementState state(double forward, double right, boolean sprint, boolean air, double vy) {
        return new MovementState(forward, right, sprint, air, LocomotionIntent.NONE, vy);
    }


    private static void step(MovementLayer layer, MovementState state, String label) {
        LocomotionState before = layer.state();
        layer.update(state, TICK);
        if (before != layer.state()) {
            System.out.printf("TICK %-9s %s -> %s%n", label, before, layer.state());
        }
    }

    private void sample(MovementLayer layer, boolean print) {
        for (int i = 0; i < 8; i++) {
            double offset = i * TICK / 8.0;
            WeaponPose pose = layer.pose(offset);
            if (pose == null || pose.bone("main") == null) {
                continue;
            }
            Vec3 p = pose.bone("main").position();
            Vec3 r = pose.bone("main").rotation();
            if (lastPos != null) {
                double d = Math.sqrt(Math.pow(p.x() - lastPos.x(), 2) + Math.pow(p.y() - lastPos.y(), 2)
                        + Math.pow(p.z() - lastPos.z(), 2));
                double a = WeaponPoseBlend.angleDegrees(lastRot, r);
                if ((i == 0 && (d > 0.12 || a > 3.0)) || d > 0.5 || a > 15.0) {
                    System.out.printf("  f=%d %-16s %s main=(%.2f,%.2f,%.2f) dy=%.3f d=%.3f dRot=%.1f%n",
                            i, lastState, layer.state(), p.x(), p.y(), p.z(), p.y() - lastY, d, a);
                }
            }
            lastPos = p;
            lastRot = r;
            lastY = p.y();
            lastState = layer.state().name();
        }
    }
}
