package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The equip clips of the shipped weapon end where their keyframes end.
 *
 * <p>A draw or holster clip is the clock of the handoff: the coordinator waits for "the clip finished" and only then commits
 * the slot change. Padding an {@code animation_length} past the last keyframe therefore adds real time in which nothing
 * animates - the raised weapon freezes, and a stowed weapon is simply not on screen - before the next weapon appears. That
 * is the "the weapon disappears and then snaps back" symptom in content form, so it is guarded here rather than only being
 * noticed in game.</p>
 *
 * <p>Only the clips this weapon declares for its own draw and holster are checked; looping clips such as the idle selection
 * are excluded, because a loop legitimately has no meaningful end.</p>
 */
class ShippedEquipClipsHaveNoDeadTailTest {

    /** Classpath prefix of the bundled default pack. */
    private static final String PACK = "assets/arg_weaponry/weapon_packs/arg_default_pack/assets/";

    private static final String WEAPON = PACK + "arg_pack/weapons_registry/weapons/dmrs/m100_dmr.json";

    /** How far an authored length may differ from its last keyframe; floating point only, not a tuning margin. */
    private static final double EPSILON = 1.0E-3;

    @Test
    void everyDrawAndHolsterClipEndsOnItsLastKeyframe() throws Exception {
        JsonObject weapon = readJson(WEAPON).getAsJsonObject();
        JsonObject animations = animationsOf(weapon);

        List<String> clips = new ArrayList<>();
        collectNames(weapon.getAsJsonArray("draw_anims"), clips);
        collectNames(weapon.getAsJsonArray("holster_anims"), clips);
        assertTrue(!clips.isEmpty(), "the shipped weapon declares no draw or holster clip");

        for (String clip : clips) {
            JsonObject animation = animations.getAsJsonObject(clip);
            assertNotNull(animation, "the animation resource does not contain '" + clip + "'");
            double length = animation.get("animation_length").getAsDouble();
            double lastKeyframe = lastKeyframe(animation.getAsJsonObject("bones"));
            assertEquals(lastKeyframe, length, EPSILON,
                    "'" + clip + "' declares " + length + " s but its last keyframe is at " + lastKeyframe
                            + " s, so the remaining time is a frozen pose or an off-screen gap");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void collectNames(JsonElement selections, List<String> into) {
        if (selections == null || !selections.isJsonArray()) {
            return;
        }
        for (JsonElement element : selections.getAsJsonArray()) {
            JsonObject selection = element.getAsJsonObject();
            JsonElement name = selection.get("name");
            if (name != null) {
                into.add(name.getAsString());
            }
        }
    }

    private static JsonObject animationsOf(JsonObject weapon) throws Exception {
        ResourceId displayId = parse(weapon.get("display").getAsString());
        JsonObject display = readJson(PACK + displayId.namespace() + "/display/" + displayId.path() + ".json")
                .getAsJsonObject();
        ResourceId animationId = parse(display.get("animations").getAsString());
        JsonObject root = readJson(PACK + animationId.namespace() + "/animations/" + animationId.path()
                + ".animation.json").getAsJsonObject();
        JsonObject animations = root.getAsJsonObject("animations");
        assertNotNull(animations, "the animation resource has no 'animations' object");
        return animations;
    }

    /**
     * @param bones the bones of one animation
     * @return the largest keyframe time on any channel of any bone, or {@code 0} when nothing is keyframed
     */
    private static double lastKeyframe(JsonObject bones) {
        double largest = 0.0;
        if (bones == null) {
            return largest;
        }
        for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
            largest = Math.max(largest, largestTimeIn(bone.getValue()));
        }
        return largest;
    }

    private static double largestTimeIn(JsonElement element) {
        double largest = 0.0;
        if (element == null) {
            return largest;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                Double time = timeKey(entry.getKey());
                if (time != null) {
                    largest = Math.max(largest, time);
                }
                largest = Math.max(largest, largestTimeIn(entry.getValue()));
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                largest = Math.max(largest, largestTimeIn(child));
            }
        }
        return largest;
    }

    private static Double timeKey(String key) {
        try {
            return Double.valueOf(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ResourceId parse(String value) {
        ResourceId.Result parsed = ResourceId.tryParse(value);
        assertTrue(parsed.isValid(), "'" + value + "' is not a valid resource identifier: " + parsed.error());
        return parsed.id();
    }

    private static JsonElement readJson(String resource) throws Exception {
        try (InputStream stream = ShippedEquipClipsHaveNoDeadTailTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(stream, "the shipped resource " + resource + " is not on the test classpath");
            return JsoncReader.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
