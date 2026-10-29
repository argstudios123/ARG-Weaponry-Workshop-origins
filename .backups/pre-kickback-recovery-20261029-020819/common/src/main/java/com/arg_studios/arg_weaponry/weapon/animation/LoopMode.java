package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * The looping configuration of an animation.
 *
 * <p>The JSON property is optional and is preserved exactly: an animation that omits {@code loop}
 * keeps {@code null} rather than being given an invented default, and an explicit {@code false} is
 * distinguishable from an absent property.</p>
 */
public enum LoopMode {

    /** {@code "loop": false} — play once and stop at the end. */
    ONCE("false"),

    /** {@code "loop": true} — restart at the beginning after the last keyframe. */
    LOOP("true"),

    /**
     * {@code "loop": "hold_on_last_frame"} — recognised and preserved, but not implemented by this
     * engine yet. No asset in the default pack uses it.
     */
    HOLD_ON_LAST_FRAME("hold_on_last_frame");

    private final String jsonName;

    LoopMode(String jsonName) {
        this.jsonName = jsonName;
    }

    /** @return the value written in the animation JSON. */
    public String jsonName() {
        return jsonName;
    }
}
