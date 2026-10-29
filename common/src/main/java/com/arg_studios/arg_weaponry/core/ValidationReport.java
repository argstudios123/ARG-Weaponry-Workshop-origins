package com.arg_studios.arg_weaponry.core;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.core.PackDiagnostic.Severity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects {@link PackDiagnostic}s during content-pack discovery and validation and reports them in
 * a stable order.
 *
 * <p>Errors mean the affected input could not be used in full: either it was skipped, or it was kept as
 * data while one of its uses was rejected. A weapon definition whose {@code weapon_id} is missing or
 * malformed is the established example of the second case: its resources remain usable and it is listed
 * among the loaded definitions, but no Minecraft item can be registered for it, so the problem is still
 * reported as an error. Warnings mean the input was used but is probably not what the creator intended.
 * Nothing is ever silently repaired without a diagnostic.</p>
 */
public final class ValidationReport {

    private static final String FRAMEWORK_SCOPE = "arg_weaponry";

    private final List<PackDiagnostic> diagnostics = new ArrayList<>();

    public void add(PackDiagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    public void info(String packId, String source, String message) {
        add(PackDiagnostic.info(packId, source, message));
    }

    public void warning(String packId, String source, String message) {
        add(PackDiagnostic.warning(packId, source, message));
    }

    public void error(String packId, String source, String message) {
        add(PackDiagnostic.error(packId, source, message));
    }

    public void error(String packId, String source, String location, String message) {
        add(PackDiagnostic.error(packId, source, location, message));
    }

    public void warning(String packId, String source, String location, String message) {
        add(PackDiagnostic.warning(packId, source, location, message));
    }

    public void info(String packId, String source, String location, String message) {
        add(PackDiagnostic.info(packId, source, location, message));
    }

    public List<PackDiagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public int count(Severity severity) {
        int count = 0;
        for (PackDiagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == severity) {
                count++;
            }
        }
        return count;
    }

    public boolean hasErrors() {
        return count(Severity.ERROR) > 0;
    }

    /**
     * Logs every collected diagnostic. Informational findings are logged at debug level so normal
     * logs stay readable, warnings and errors are always visible.
     */
    public void logAll() {
        for (PackDiagnostic diagnostic : diagnostics) {
            switch (diagnostic.severity()) {
                case INFO -> Constants.LOG.debug("[ARG] {}", diagnostic.format());
                case WARNING -> Constants.LOG.warn("[ARG] {}", diagnostic.format());
                case ERROR -> Constants.LOG.error("[ARG] {}", diagnostic.format());
            }
        }
    }

    /** @return the scope name used for diagnostics that do not belong to a specific content pack. */
    public static String frameworkScope() {
        return FRAMEWORK_SCOPE;
    }
}
