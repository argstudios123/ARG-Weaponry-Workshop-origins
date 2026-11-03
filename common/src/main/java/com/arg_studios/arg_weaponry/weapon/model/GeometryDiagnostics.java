package com.arg_studios.arg_weaponry.weapon.model;

/**
 * The diagnostics channel one geometry layer reports through.
 *
 * <p>It exists so {@link BedrockCubeGeometry} and {@link PolyMeshGeometry} can be independent of the parser and of
 * {@link com.arg_studios.arg_weaponry.core.ValidationReport}: a layer describes what is wrong at a JSON pointer, and the
 * parser decides where that ends up. That keeps a geometry layer usable from a test with no report at all, and it keeps
 * the pack id and file name out of the geometry code.</p>
 */
interface GeometryDiagnostics {

    /**
     * @param pointer the JSON pointer of the offending value
     * @param message what is wrong with it
     */
    void error(String pointer, String message);

    void warning(String pointer, String message);

    void info(String pointer, String message);

    /**
     * A construct that is recognised but deliberately not applied.
     *
     * <p>Separate from {@link #warning} because the parser reports these at most once per pointer and a caller may want
     * to keep that de-duplication.</p>
     */
    void unsupported(String pointer, String message);
}
