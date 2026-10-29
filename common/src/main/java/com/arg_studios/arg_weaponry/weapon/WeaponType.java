package com.arg_studios.arg_weaponry.weapon;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The weapon category declared by the JSON {@code type} property.
 *
 * <p>The existing schema uses {@code "type": "gun"} and its comment documents {@code gun},
 * {@code melee} and {@code throwable}. Only firearms are implemented by this milestone; the other
 * categories are represented so that the data model does not need to change later, but their
 * properties are not validated yet.</p>
 */
public enum WeaponType {

    GUN("gun"),
    MELEE("melee"),
    THROWABLE("throwable");

    private final String jsonName;

    WeaponType(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /** @return the matching type, or {@code null} when the value is not a known category. */
    public static WeaponType fromJson(String value) {
        for (WeaponType type : values()) {
            if (type.jsonName.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }

    /** @return the accepted values, for diagnostics. */
    public static String knownNames() {
        return Arrays.stream(values()).map(WeaponType::jsonName).collect(Collectors.joining(", "));
    }
}
