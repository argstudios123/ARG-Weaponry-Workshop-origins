package com.arg_studios.arg_weaponry.weapon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;


public final class WeaponDefinitionDiscovery {

    public static final String JSON_EXTENSION = ".json";

    private WeaponDefinitionDiscovery() {
    }
// Optimizes and fixes gun definition

    public static List<Path> listDefinitionFiles(Path weaponsRoot) throws IOException {
        if (weaponsRoot == null || !Files.isDirectory(weaponsRoot)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(weaponsRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(JSON_EXTENSION))
                    .sorted()
                    .toList();
        }
    }


    public static ResourceId registryIdentity(String namespace, Path weaponsRoot, Path file) {
        String relative = weaponsRoot.relativize(file).toString().replace('\\', '/');
        String withoutExtension = relative.endsWith(JSON_EXTENSION)
                ? relative.substring(0, relative.length() - JSON_EXTENSION.length())
                : relative;
        return new ResourceId(namespace, "weapons/" + withoutExtension);
    }


    public static List<Definition> discover(String namespace, Path weaponsRoot) throws IOException {
        return listDefinitionFiles(weaponsRoot).stream()
                .map(file -> new Definition(file, registryIdentity(namespace, weaponsRoot, file)))
                .toList();
    }

    public record Definition(Path file, ResourceId identity) {
    }
}
