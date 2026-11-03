package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Parses the Bedrock animation documents used by ARG weapon animations.
 *
 * <p>The parser is deliberately faithful: animation names, lengths, looping configuration, bone names,
 * keyframe times, {@code pre}/{@code post} values and interpolation metadata are preserved exactly as
 * written. Nothing is evaluated, interpolated, scheduled or applied here - this class only turns JSON
 * into immutable data, mirroring {@link com.arg_studios.arg_weaponry.weapon.model.BedrockGeometryParser}
 * for geometry.</p>
 *
 * <p>Supported document structure:</p>
 * <pre>
 * {
 *   "format_version": "1.8.0",
 *   "animations": {
 *     "anim.model.fire": {
 *       "animation_length": 0.04,
 *       "loop": false,
 *       "bones": {
 *         "bolt2": {
 *           "rotation": { "0.0": [0, 0, 0], "0.0167": [0, 0, 2.2] },
 *           "position": [0, 0, 2.0625],
 *           "scale": 1
 *         }
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>A channel is either a timestamp-keyed object (stored as {@link AnimationChannel.Keyframed}) or a
 * single value (stored as {@link AnimationChannel.Constant}); the two forms are never converted into
 * each other. A single number (for example {@code "scale": 0}) is only valid for {@code scale}, where
 * the format defines it as a uniform scale on all three axes.</p>
 *
 * <p>Reported rather than applied: {@code hold_on_last_frame} looping (preserved as {@link
 * LoopMode#HOLD_ON_LAST_FRAME} but not implemented), unknown interpolation modes (preserved verbatim on
 * the keyframe, never reinterpreted as linear), and any construct outside the supported format
 * ({@code sound_effects}, {@code particles}, {@code timeline}, animation controllers, unknown keys).
 * No Molang expression, animation controller, sound event, particle event or timeline event is
 * implemented at this stage.</p>
 *
 * <p>The parser accepts an already-parsed JSON document: resolving a content-pack resource and reading
 * its text stays the caller's job, exactly as for {@code BedrockGeometryParser}.</p>
 */
public final class BedrockAnimationParser {

    /** The animation-map key used by the format. */
    public static final String ANIMATIONS_KEY = "animations";

    private static final Set<String> KNOWN_ANIMATION_KEYS = Set.of("animation_length", "loop", "bones");
    private static final Set<String> KNOWN_KEYFRAME_KEYS = Set.of("pre", "post", "lerp_mode");

    private final String packId;
    private final ResourceId resourceId;
    private final String sourceName;
    private final ValidationReport report;

    private final Set<String> reportedPointers = new LinkedHashSet<>();
    private String formatVersion = "";
    private int keyframedChannelCount;
    private int constantChannelCount;
    private int keyframeCount;
    private int catmullromKeyframeCount;

    private BedrockAnimationParser(String packId, ResourceId resourceId, String sourceName, ValidationReport report) {
        this.packId = packId;
        this.resourceId = resourceId;
        this.sourceName = sourceName;
        this.report = report;
    }

    /**
     * Parses a Bedrock animation document.
     *
     * @param packId     the owning content pack, for diagnostics
     * @param resourceId the resource the document was read from, for diagnostics
     * @param sourceName the pack-relative file name, for diagnostics
     * @param root       the parsed document root
     * @param report     the report to collect diagnostics in
     * @return the parsed document, or {@code null} when it declares no usable {@code animations} object
     */
    public static AnimationFile parse(String packId, ResourceId resourceId, String sourceName, JsonObject root,
                                      ValidationReport report) {
        return new BedrockAnimationParser(packId, resourceId, sourceName, report).run(root);
    }

    // --- top level ---

    private AnimationFile run(JsonObject root) {
        if (root == null) {
            report.error(packId, sourceName, "$", "The animation document is empty; there is nothing to parse");
            return null;
        }

        readFormatVersion(root);

        JsonElement animationsElement = root.get(ANIMATIONS_KEY);
        if (!(animationsElement instanceof JsonObject animations)) {
            report.error(packId, sourceName, "$." + ANIMATIONS_KEY, "Expected an object of animation definitions "
                    + "but found " + JsonValues.describeType(animationsElement) + "; the document cannot be used");
            return null;
        }

        for (String key : root.keySet()) {
            if (!key.equals("format_version") && !key.equals(ANIMATIONS_KEY)) {
                reportUnsupported("$." + key, "Document property '" + key + "' is not interpreted by this engine");
            }
        }

        Map<String, BedrockAnimation> parsed = new LinkedHashMap<>();
        for (String name : animations.keySet()) {
            String pointer = "$." + ANIMATIONS_KEY + "[\"" + name + "\"]";
            if (name.isBlank()) {
                report.error(packId, sourceName, pointer, "The animation name is empty; the animation was skipped");
                continue;
            }
            JsonElement definitionElement = animations.get(name);
            if (!(definitionElement instanceof JsonObject definition)) {
                report.error(packId, sourceName, pointer, "Expected an animation object but found "
                        + JsonValues.describeType(definitionElement) + "; the animation was skipped");
                continue;
            }
            BedrockAnimation animation = readAnimation(name, definition, pointer);
            if (animation == null) {
                continue;
            }
            if (parsed.putIfAbsent(name, animation) != null) {
                report.error(packId, sourceName, pointer, "Duplicate animation name '" + name
                        + "'; the duplicate was skipped and the first definition is kept");
            }
        }

        if (parsed.isEmpty()) {
            report.error(packId, sourceName, "$." + ANIMATIONS_KEY, "The document declares no usable animation");
        } else {
            report.info(packId, sourceName, resourceId.asString(), "Parsed " + parsed.size() + " animation(s), "
                    + keyframedChannelCount + " timestamp-keyed channel(s) holding " + keyframeCount
                    + " keyframe(s) (of which " + catmullromKeyframeCount + " declare 'catmullrom') and "
                    + constantChannelCount + " constant channel(s)");
        }

        return new AnimationFile(resourceId, formatVersion, parsed);
    }

    private void readFormatVersion(JsonObject root) {
        JsonElement element = root.get("format_version");
        if (JsonValues.isString(element)) {
            formatVersion = element.getAsString();
            return;
        }
        if (JsonValues.isNumber(element)) {
            formatVersion = element.getAsString();
            report.warning(packId, sourceName, "$.format_version", "The document declares 'format_version' as a "
                    + "number; '" + formatVersion + "' is kept as written");
            return;
        }
        report.warning(packId, sourceName, "$.format_version", "The document declares no 'format_version' string; "
                + "its structure is parsed as written");
    }

    // --- one animation ---

    private BedrockAnimation readAnimation(String name, JsonObject definition, String pointer) {
        JsonElement lengthElement = definition.get("animation_length");
        double length;
        if (lengthElement == null) {
            report.warning(packId, sourceName, pointer + ".animation_length",
                    "The animation declares no 'animation_length'; a length of 0.0 is used");
            length = 0.0;
        } else {
            Double parsedLength = JsonValues.asDouble(lengthElement);
            if (parsedLength == null) {
                report.error(packId, sourceName, pointer + ".animation_length", "Expected a number but found "
                        + JsonValues.describeType(lengthElement) + "; the animation was skipped");
                return null;
            }
            if (!Double.isFinite(parsedLength)) {
                report.error(packId, sourceName, pointer + ".animation_length",
                        "The animation length is not a finite number; the animation was skipped");
                return null;
            }
            if (parsedLength < 0.0) {
                report.error(packId, sourceName, pointer + ".animation_length", "The animation length "
                        + parsedLength + " is negative; the animation was skipped");
                return null;
            }
            length = parsedLength;
        }

        LoopMode loopMode = readLoopMode(definition, pointer);
        Map<String, BoneAnimation> bones = readBones(definition, pointer);
        if (bones == null) {
            return null;
        }

        for (String key : definition.keySet()) {
            if (!KNOWN_ANIMATION_KEYS.contains(key)) {
                reportUnsupported(pointer + "." + key, "Animation property '" + key + "' is not interpreted; sound "
                        + "events, particle events, timelines and animation controllers are not implemented by this "
                        + "engine");
            }
        }

        return new BedrockAnimation(name, length, loopMode, bones);
    }

    /**
     * Reads {@code loop} without inventing a value: {@code true}/{@code false} map to {@link LoopMode#LOOP}
     * /{@link LoopMode#ONCE}, {@code "hold_on_last_frame"} is preserved as {@link
     * LoopMode#HOLD_ON_LAST_FRAME}, and an absent property stays {@code null}.
     */
    private LoopMode readLoopMode(JsonObject definition, String pointer) {
        JsonElement element = definition.get("loop");
        if (element == null) {
            return null;
        }
        if (JsonValues.isBoolean(element)) {
            return element.getAsBoolean() ? LoopMode.LOOP : LoopMode.ONCE;
        }
        if (JsonValues.isString(element)) {
            String raw = element.getAsString();
            if (raw.equalsIgnoreCase(LoopMode.HOLD_ON_LAST_FRAME.jsonName())) {
                report.info(packId, sourceName, pointer + ".loop", "'hold_on_last_frame' is preserved as written, "
                        + "but this engine does not implement holding the last frame yet");
                return LoopMode.HOLD_ON_LAST_FRAME;
            }
            report.error(packId, sourceName, pointer + ".loop", "'" + raw + "' is not a supported 'loop' value "
                    + "(expected true, false or 'hold_on_last_frame'); the animation is parsed as if 'loop' were "
                    + "absent");
            return null;
        }
        report.error(packId, sourceName, pointer + ".loop", "Expected a boolean or 'hold_on_last_frame' but found "
                + JsonValues.describeType(element) + "; the animation is parsed as if 'loop' were absent");
        return null;
    }

    // --- bones ---

    /**
     * Reads the {@code bones} object.
     *
     * @return the animated bones, or {@code null} when the property exists but is not an object
     */
    private Map<String, BoneAnimation> readBones(JsonObject definition, String pointer) {
        JsonElement bonesElement = definition.get("bones");
        if (bonesElement == null) {
            report.warning(packId, sourceName, pointer + ".bones",
                    "The animation declares no 'bones' object, so it animates nothing");
            return Map.of();
        }
        if (!(bonesElement instanceof JsonObject bones)) {
            report.error(packId, sourceName, pointer + ".bones", "Expected a bone object but found "
                    + JsonValues.describeType(bonesElement) + "; the animation was skipped");
            return null;
        }

        Map<String, BoneAnimation> parsed = new LinkedHashMap<>();
        for (String boneName : bones.keySet()) {
            String bonePointer = pointer + ".bones[\"" + boneName + "\"]";
            if (boneName.isBlank()) {
                report.error(packId, sourceName, bonePointer,
                        "The bone name is empty; the bone animation was skipped");
                continue;
            }
            JsonElement channelsElement = bones.get(boneName);
            if (!(channelsElement instanceof JsonObject channels)) {
                report.error(packId, sourceName, bonePointer, "Expected a bone animation object but found "
                        + JsonValues.describeType(channelsElement) + "; the bone animation was skipped");
                continue;
            }
            BoneAnimation bone = readBone(boneName, channels, bonePointer);
            if (bone != null) {
                parsed.put(boneName, bone);
            }
        }
        return parsed;
    }

    private BoneAnimation readBone(String boneName, JsonObject channels, String pointer) {
        AnimationChannel rotation = null;
        AnimationChannel position = null;
        AnimationChannel scale = null;

        for (String key : channels.keySet()) {
            ChannelKind kind = ChannelKind.fromJson(key);
            if (kind == null) {
                reportUnsupported(pointer + "." + key, "Bone animation property '" + key + "' is not interpreted; "
                        + "only 'rotation', 'position' and 'scale' are supported");
                continue;
            }
            AnimationChannel channel = readChannel(kind, channels.get(key), pointer + "." + key, boneName);
            if (channel == null) {
                continue;
            }
            switch (kind) {
                case ROTATION -> rotation = channel;
                case POSITION -> position = channel;
                case SCALE -> scale = channel;
            }
        }

        if (rotation == null && position == null && scale == null) {
            report.warning(packId, sourceName, pointer, "The bone animation of '" + boneName
                    + "' declares no usable channel; the bone animation was skipped");
            return null;
        }
        return new BoneAnimation(boneName, rotation, position, scale);
    }

    // --- channels ---

    /**
     * Reads one channel of one bone.
     *
     * <p>A timestamp-keyed object becomes {@link AnimationChannel.Keyframed}; a single value becomes
     * {@link AnimationChannel.Constant}. A bare number is only accepted for {@code scale}, where the format
     * defines it as a uniform scale on all three axes.</p>
     *
     * @return the channel, or {@code null} when it could not be read
     */
    private AnimationChannel readChannel(ChannelKind kind, JsonElement element, String pointer, String boneName) {
        if (element instanceof JsonObject keyframeMap) {
            return readKeyframedChannel(kind, keyframeMap, pointer, boneName);
        }
        if (JsonValues.isNumber(element)) {
            double scalar = element.getAsDouble();
            if (!Double.isFinite(scalar)) {
                report.error(packId, sourceName, pointer, "The '" + kind.jsonName() + "' value of bone '" + boneName
                        + "' is not a finite number; the channel was skipped");
                return null;
            }
            if (!kind.allowsScalar()) {
                report.error(packId, sourceName, pointer, "Bone '" + boneName + "' declares its '" + kind.jsonName()
                        + "' channel as a single number; only 'scale' may be written that way, so the channel was "
                        + "skipped");
                return null;
            }
            constantChannelCount++;
            return AnimationChannel.Constant.ofScalar(scalar);
        }
        if (element instanceof JsonArray) {
            Vec3 value = readVector(element, pointer);
            if (value == null) {
                return null;
            }
            constantChannelCount++;
            return AnimationChannel.Constant.ofVector(value);
        }
        report.error(packId, sourceName, pointer, "Expected a vector, a single number or a timestamp-keyed object "
                + "for the '" + kind.jsonName() + "' channel of bone '" + boneName + "' but found "
                + JsonValues.describeType(element) + "; the channel was skipped");
        return null;
    }

    /**
     * Reads a timestamp-keyed channel. Valid keyframes are kept even when other entries of the same channel
     * are malformed, and the surviving keyframes are ordered by ascending time.
     */
    private AnimationChannel readKeyframedChannel(ChannelKind kind, JsonObject keyframeMap, String pointer,
                                                  String boneName) {
        if (keyframeMap.keySet().isEmpty()) {
            report.error(packId, sourceName, pointer, "The '" + kind.jsonName() + "' channel of bone '" + boneName
                    + "' declares no keyframe; the channel was skipped");
            return null;
        }

        TreeMap<Double, Keyframe> ordered = new TreeMap<>();
        for (String rawTime : keyframeMap.keySet()) {
            String keyframePointer = pointer + "[\"" + rawTime + "\"]";
            Double time = readTimestamp(rawTime, keyframePointer, kind, boneName);
            if (time == null) {
                continue;
            }
            if (ordered.containsKey(time)) {
                report.error(packId, sourceName, keyframePointer, "The keyframe time '" + rawTime + "' resolves to "
                        + time + "s, which another entry of this channel already uses; the duplicate was skipped");
                continue;
            }
            Keyframe keyframe = readKeyframe(kind, keyframeMap.get(rawTime), keyframePointer, time, boneName);
            if (keyframe != null) {
                ordered.put(time, keyframe);
            }
        }

        if (ordered.isEmpty()) {
            report.error(packId, sourceName, pointer, "The '" + kind.jsonName() + "' channel of bone '" + boneName
                    + "' declares no usable keyframe; the channel was skipped");
            return null;
        }

        keyframedChannelCount++;
        keyframeCount += ordered.size();
        for (Keyframe keyframe : ordered.values()) {
            if (keyframe.lerpMode() == LerpMode.CATMULLROM) {
                catmullromKeyframeCount++;
            }
        }
        return new AnimationChannel.Keyframed(new ArrayList<>(ordered.values()));
    }

    // --- keyframes ---

    /**
     * Reads a keyframe time. The format stores times as object keys - decimal seconds such as
     * {@code "0.0167"} - so they are parsed from text.
     *
     * @return the time in seconds, or {@code null} when the key is not a usable timestamp
     */
    private Double readTimestamp(String rawTime, String pointer, ChannelKind kind, String boneName) {
        double time;
        try {
            time = Double.parseDouble(rawTime.trim());
        } catch (NumberFormatException exception) {
            report.error(packId, sourceName, pointer, "The keyframe time '" + rawTime + "' of the '"
                    + kind.jsonName() + "' channel of bone '" + boneName + "' is not a number; the keyframe was "
                    + "skipped");
            return null;
        }
        if (!Double.isFinite(time)) {
            report.error(packId, sourceName, pointer, "The keyframe time '" + rawTime + "' of the '"
                    + kind.jsonName() + "' channel of bone '" + boneName + "' is not a finite number; the keyframe "
                    + "was skipped");
            return null;
        }
        if (time < 0.0) {
            report.error(packId, sourceName, pointer, "The keyframe time '" + rawTime + "' of the '"
                    + kind.jsonName() + "' channel of bone '" + boneName + "' is negative; negative times are not "
                    + "supported, so the keyframe was skipped");
            return null;
        }
        return time;
    }

    /**
     * Reads one keyframe value: a plain vector, a bare number (scale only) or an object with {@code pre},
     * {@code post} and {@code lerp_mode}.
     *
     * @return the keyframe, or {@code null} when it could not be read
     */
    private Keyframe readKeyframe(ChannelKind kind, JsonElement element, String pointer, double time,
                                  String boneName) {
        if (element instanceof JsonArray) {
            Vec3 value = readVector(element, pointer);
            return value == null ? null : Keyframe.plain(time, value);
        }
        if (JsonValues.isNumber(element)) {
            double scalar = element.getAsDouble();
            if (!Double.isFinite(scalar)) {
                report.error(packId, sourceName, pointer, "The keyframe at " + time + "s of the '" + kind.jsonName()
                        + "' channel of bone '" + boneName + "' is not a finite number; the keyframe was skipped");
                return null;
            }
            if (!kind.allowsScalar()) {
                report.error(packId, sourceName, pointer, "The keyframe at " + time + "s of the '" + kind.jsonName()
                        + "' channel of bone '" + boneName + "' is a single number; only 'scale' may be written "
                        + "that way, so the keyframe was skipped");
                return null;
            }
            float component = (float) scalar;
            return Keyframe.plain(time, new Vec3(component, component, component));
        }
        if (element instanceof JsonObject keyframe) {
            Vec3 pre = null;
            Vec3 post = null;
            if (keyframe.has("pre")) {
                pre = readVector(keyframe.get("pre"), pointer + ".pre");
                if (pre == null) {
                    return null;
                }
            }
            if (keyframe.has("post")) {
                post = readVector(keyframe.get("post"), pointer + ".post");
                if (post == null) {
                    return null;
                }
            }
            if (pre == null && post == null) {
                report.error(packId, sourceName, pointer, "The keyframe at " + time + "s of the '"
                        + kind.jsonName() + "' channel of bone '" + boneName + "' declares neither 'pre' nor "
                        + "'post'; the keyframe was skipped");
                return null;
            }

            String declaredLerpMode = null;
            LerpMode lerpMode = LerpMode.LINEAR;
            JsonElement lerpElement = keyframe.get("lerp_mode");
            if (lerpElement != null) {
                if (JsonValues.isString(lerpElement)) {
                    declaredLerpMode = lerpElement.getAsString();
                    lerpMode = LerpMode.fromJson(declaredLerpMode);
                    if (lerpMode == LerpMode.UNKNOWN) {
                        report.error(packId, sourceName, pointer + ".lerp_mode", "The interpolation mode '"
                                + declaredLerpMode + "' of the keyframe at " + time + "s of bone '" + boneName
                                + "' is not implemented; the keyframe keeps it verbatim and is not treated as "
                                + "linear");
                    }
                } else {
                    report.error(packId, sourceName, pointer + ".lerp_mode", "Expected a string but found "
                            + JsonValues.describeType(lerpElement) + "; the keyframe is parsed with linear "
                            + "interpolation");
                }
            }

            for (String key : keyframe.keySet()) {
                if (!KNOWN_KEYFRAME_KEYS.contains(key)) {
                    reportUnsupported(pointer + "." + key, "Keyframe property '" + key + "' is not interpreted");
                }
            }

            return Keyframe.of(time, pre, post, lerpMode, declaredLerpMode);
        }
        report.error(packId, sourceName, pointer, "Expected a vector, a single number or a keyframe object for the "
                + "keyframe at " + time + "s of the '" + kind.jsonName() + "' channel of bone '" + boneName
                + "' but found " + JsonValues.describeType(element) + "; the keyframe was skipped");
        return null;
    }

    // --- values ---

    /**
     * Reads a three-component vector.
     *
     * <p>Non-finite components are rejected rather than passed to {@link Vec3}, which would throw: a
     * hand-built JSON tree can hold {@code NaN}, even though the content-pack reader rejects it before it
     * reaches this parser.</p>
     *
     * @return the vector, or {@code null} when the value is not a vector of three finite numbers
     */
    private Vec3 readVector(JsonElement element, String pointer) {
        if (!(element instanceof JsonArray array)) {
            report.error(packId, sourceName, pointer,
                    "Expected an array of three numbers but found " + JsonValues.describeType(element));
            return null;
        }
        if (array.size() != 3) {
            report.error(packId, sourceName, pointer, "Expected an array of exactly three numbers but found "
                    + array.size() + " entries");
            return null;
        }
        float[] components = new float[3];
        for (int index = 0; index < 3; index++) {
            String componentPointer = pointer + "[" + index + "]";
            Double value = JsonValues.asDouble(array.get(index));
            if (value == null) {
                report.error(packId, sourceName, componentPointer,
                        "Expected a number but found " + JsonValues.describeType(array.get(index)));
                return null;
            }
            if (!Double.isFinite(value)) {
                report.error(packId, sourceName, componentPointer, "The component is not a finite number");
                return null;
            }
            components[index] = value.floatValue();
        }
        return new Vec3(components[0], components[1], components[2]);
    }

    /** Reports a finding at most once per JSON pointer, so repeated structures do not flood the log. */
    private void reportUnsupported(String pointer, String message) {
        if (reportedPointers.add(pointer)) {
            report.warning(packId, sourceName, pointer, message);
        }
    }
}
