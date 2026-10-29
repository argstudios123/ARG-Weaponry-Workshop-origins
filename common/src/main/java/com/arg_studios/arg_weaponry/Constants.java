package com.arg_studios.arg_weaponry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants for ARG Weaponry Workshop Origins.
 *
 * <p>These are compile-time values on purpose: the mod id is used to build resource paths
 * ({@code assets/<mod id>/...}) and to look the mod file up through the loader APIs, so it must
 * stay identical to {@code mod_id} in {@code gradle.properties}.</p>
 */
public final class Constants {

    /** The mod id. Must match {@code mod_id} in {@code gradle.properties}. */
    public static final String MOD_ID = "arg_weaponry";

    /** The human readable mod name, used for logging. */
    public static final String MOD_NAME = "ARG Weaponry Workshop Origins";

    /** Shared logger for every ARG Weaponry subsystem. */
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Constants() {
    }
}
