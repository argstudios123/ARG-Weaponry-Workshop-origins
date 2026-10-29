package com.arg_studios.arg_weaponry.pack;

import java.nio.file.Path;

/**
 * A directory that contains content packs, together with where it came from.
 *
 * @param directory the directory that holds pack folders
 * @param source    the origin of the packs in that directory
 */
public record PackLocation(Path directory, ContentPackSource source) {
}
