package com.arg_studios.arg_weaponry.weapon.model;

import com.arg_studios.arg_weaponry.json.JsonValues;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/**
 * The JSON reading both geometry layers and the parser share.
 *
 * <p>Reading a vector, a list of vectors, a list of UVs or a number out of a Bedrock document is about the format, not about
 * cubes or {@code poly_mesh}, so it lives here rather than being duplicated in each geometry class. Every rejection is
 * reported through the {@link GeometryDiagnostics} of the parse instead of throwing, because one malformed value must not
 * abort a model load.</p>
 */
final class GeometryJson {

    private final GeometryDiagnostics diagnostics;

    GeometryJson(GeometryDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    JsonArray array(JsonElement element, String pointer) {
        if (element == null) {
            return null;
        }
        if (element instanceof JsonArray array) {
            return array;
        }
        diagnostics.error(pointer, "Expected an array but found " + JsonValues.describeType(element));
        return null;
    }

    List<Vec3> vectorList(JsonArray array, String pointer) {
        List<Vec3> vectors = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            Vec3 vector = vector(array.get(index), pointer + "[" + index + "]", null, true);
            if (vector == null) {
                return List.of();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    List<float[]> uvList(JsonArray array, String pointer) {
        List<float[]> uvs = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            String elementPointer = pointer + "[" + index + "]";
            if (!(array.get(index) instanceof JsonArray uv) || uv.size() < 2) {
                diagnostics.error(elementPointer, "Expected [u, v] but found "
                        + JsonValues.describeType(array.get(index)));
                return List.of();
            }
            Double u = JsonValues.asDouble(uv.get(0));
            Double v = JsonValues.asDouble(uv.get(1));
            if (u == null || v == null) {
                diagnostics.error(elementPointer, "The UV coordinates are not numbers");
                return List.of();
            }
            uvs.add(new float[]{u.floatValue(), v.floatValue()});
        }
        return uvs;
    }

    Vec3 vector(JsonElement element, String pointer, Vec3 fallback, boolean required) {
        if (element == null) {
            return fallback;
        }
        if (!(element instanceof JsonArray array) || array.size() < 3) {
            if (required) {
                diagnostics.error(pointer, "Expected an array of three numbers but found "
                        + JsonValues.describeType(element));
            } else {
                diagnostics.warning(pointer, "Expected an array of three numbers but found "
                        + JsonValues.describeType(element) + "; the value is treated as zero");
            }
            return fallback;
        }
        Double x = JsonValues.asDouble(array.get(0));
        Double y = JsonValues.asDouble(array.get(1));
        Double z = JsonValues.asDouble(array.get(2));
        if (x == null || y == null || z == null) {
            diagnostics.error(pointer, "The vector components are not all numbers; "
                    + (required ? "the value is unusable" : "the value is treated as zero"));
            return fallback;
        }
        // Vec3 refuses non-finite components, and a value that overflows a float (1e400 in JSON) is exactly that. It is
        // reported here instead of thrown, so one absurd number in one cube cannot abort the whole model load - and it
        // can never reach the renderer, where a NaN vertex would poison the submitted buffer's bounds.
        float floatX = x.floatValue();
        float floatY = y.floatValue();
        float floatZ = z.floatValue();
        if (!Float.isFinite(floatX) || !Float.isFinite(floatY) || !Float.isFinite(floatZ)) {
            diagnostics.error(pointer, "A vector component is too large to represent as a number; "
                    + (required ? "the value is unusable" : "the value is treated as zero"));
            return fallback;
        }
        return new Vec3(floatX, floatY, floatZ);
    }

    float floatValue(JsonElement element, String pointer, float fallback) {
        if (element == null) {
            return fallback;
        }
        Double value = JsonValues.asDouble(element);
        if (value == null) {
            diagnostics.warning(pointer, "Expected a number but found "
                    + JsonValues.describeType(element) + "; " + fallback + " is used");
            return fallback;
        }
        if (!Float.isFinite(value.floatValue())) {
            diagnostics.warning(pointer, "The number is too large to represent; " + fallback
                    + " is used");
            return fallback;
        }
        return value.floatValue();
    }
}
