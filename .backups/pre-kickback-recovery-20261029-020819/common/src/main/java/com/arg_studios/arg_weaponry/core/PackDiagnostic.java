package com.arg_studios.arg_weaponry.core;

/**
 * A single finding produced while validating content-pack input.
 *
 * <p>Diagnostics are deliberately structured instead of being plain log lines: they identify the
 * affected pack, the file that produced them and (where known) the position inside that file, so a
 * creator can fix the problem without guessing.</p>
 *
 * @param severity how serious the finding is
 * @param packId   the content pack the finding belongs to, or a descriptive placeholder when the
 *                 finding is not attached to a single pack
 * @param source   the file the finding refers to, relative to the pack root where possible
 * @param location a human readable position inside {@code source} (for example
 *                 {@code "line 12, column 5"}), or {@code null} when the position is unknown
 * @param message  the description of the problem
 */
public record PackDiagnostic(Severity severity, String packId, String source, String location, String message) {

    /** Severity of a diagnostic, ordered from least to most serious. */
    public enum Severity {
        /** Informational; logged at debug level. */
        INFO,
        /** The input is usable, but probably not what the creator intended. */
        WARNING,
        /** The input could not be used. */
        ERROR
    }

    public static PackDiagnostic info(String packId, String source, String message) {
        return new PackDiagnostic(Severity.INFO, packId, source, null, message);
    }

    public static PackDiagnostic warning(String packId, String source, String message) {
        return new PackDiagnostic(Severity.WARNING, packId, source, null, message);
    }

    public static PackDiagnostic error(String packId, String source, String message) {
        return new PackDiagnostic(Severity.ERROR, packId, source, null, message);
    }

    public static PackDiagnostic error(String packId, String source, String location, String message) {
        return new PackDiagnostic(Severity.ERROR, packId, source, location, message);
    }

    public static PackDiagnostic warning(String packId, String source, String location, String message) {
        return new PackDiagnostic(Severity.WARNING, packId, source, location, message);
    }

    public static PackDiagnostic info(String packId, String source, String location, String message) {
        return new PackDiagnostic(Severity.INFO, packId, source, location, message);
    }

    /** @return a single line representation used for logging. */
    public String format() {
        StringBuilder builder = new StringBuilder();
        builder.append(severity.name()).append(' ');
        builder.append('[').append(packId).append("] ");
        builder.append(source);
        if (location != null && !location.isEmpty()) {
            builder.append(" (").append(location).append(')');
        }
        builder.append(": ").append(message);
        return builder.toString();
    }
}
