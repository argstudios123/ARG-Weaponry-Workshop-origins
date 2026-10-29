package com.arg_studios.arg_weaponry.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * Small type helpers shared by the configuration readers.
 *
 * <p>These deliberately do not produce diagnostics themselves; the calling code knows which JSON
 * path and file a value came from and can report a precise finding.</p>
 */
public final class JsonValues {

    private JsonValues() {
    }

    /** @return a description of the JSON type of {@code element}, for diagnostics. */
    public static String describeType(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonObject()) {
            return "an object";
        }
        if (element.isJsonArray()) {
            return "an array";
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                return "a string";
            }
            if (primitive.isNumber()) {
                return "a number";
            }
            if (primitive.isBoolean()) {
                return "a boolean";
            }
        }
        return "an unsupported value";
    }

    public static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    public static boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    public static boolean isBoolean(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean();
    }

    /** @return the numeric value, or {@code null} when the element is not a number. */
    public static Double asDouble(JsonElement element) {
        return isNumber(element) ? element.getAsDouble() : null;
    }

    /** @return the value as an {@code int}, or {@code null} when it is not an integral number in range. */
    public static Integer asInt(JsonElement element) {
        Double value = asDouble(element);
        if (value == null || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) (double) value;
    }
}
