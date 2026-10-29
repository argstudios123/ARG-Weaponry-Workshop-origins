package com.arg_studios.arg_weaponry.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * An immutable index of the resource files a content pack provides, grouped by resource namespace.
 *
 * <p>This is intentionally a small building block, not the full resource resolver: it only answers
 * "does this pack contain this path in this namespace, and is it empty", which is what reference
 * validation needs today. It reads whatever physical layout the pack uses below
 * {@code assets/<namespace>/}, so no creator is forced to copy the default pack's folder structure.</p>
 *
 * <p>Paths are stored relative to the namespace root with {@code /} separators, for example
 * {@code display/weapons/dmrs/m100_dmr/dmr_ammo.json}.</p>
 */
public final class PackResourceIndex {

    private final Map<String, Map<String, Long>> namespaceFiles;
    private final List<String> readErrors;

    private PackResourceIndex(Map<String, Map<String, Long>> namespaceFiles, List<String> readErrors) {
        this.namespaceFiles = namespaceFiles;
        this.readErrors = List.copyOf(readErrors);
    }

    /**
     * Builds the index by walking every {@code assets/<namespace>} directory of the pack.
     *
     * @param pack the pack to index
     */
    public static PackResourceIndex of(ContentPack pack) {
        Map<String, Map<String, Long>> namespaceFiles = new LinkedHashMap<>();
        List<String> readErrors = new ArrayList<>();

        for (String namespace : pack.resourceNamespaces()) {
            Path namespaceRoot = pack.root().resolve("assets").resolve(namespace);
            Map<String, Long> files = new LinkedHashMap<>();
            if (Files.isDirectory(namespaceRoot)) {
                try (Stream<Path> walk = Files.walk(namespaceRoot)) {
                    for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                        String relativePath = namespaceRoot.relativize(file).toString().replace('\\', '/');
                        long size;
                        try {
                            size = Files.size(file);
                        } catch (IOException e) {
                            size = -1L;
                        }
                        files.put(relativePath, size);
                    }
                } catch (IOException e) {
                    readErrors.add("Failed to index namespace '" + namespace + "': " + e.getMessage());
                }
            }
            namespaceFiles.put(namespace, Collections.unmodifiableMap(files));
        }

        return new PackResourceIndex(namespaceFiles, readErrors);
    }

    /** @return the namespaces this index knows about. */
    public Set<String> namespaces() {
        return Collections.unmodifiableSet(namespaceFiles.keySet());
    }

    /** @return {@code true} when the namespace exists in this pack (even if it contains no files). */
    public boolean hasNamespace(String namespace) {
        return namespaceFiles.containsKey(namespace);
    }

    /**
     * @param namespace    the resource namespace
     * @param relativePath the path below the namespace root, using {@code /}
     * @return {@code true} when the pack contains exactly that file
     */
    public boolean contains(String namespace, String relativePath) {
        Map<String, Long> files = namespaceFiles.get(namespace);
        return files != null && files.containsKey(relativePath);
    }

    /**
     * @param namespace         the resource namespace
     * @param relativePath      the path below the namespace root, using {@code /}
     * @param candidateSuffixes suffixes to append in order, for example {@code .json} and
     *                          {@code .animation.json}
     * @return the first existing path, or {@code null} when none exists
     */
    public String findFirstExisting(String namespace, String relativePath, List<String> candidateSuffixes) {
        Map<String, Long> files = namespaceFiles.get(namespace);
        if (files == null) {
            return null;
        }
        for (String suffix : candidateSuffixes) {
            String candidate = relativePath + suffix;
            if (files.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** @return the file size, or {@code -1} when the file is unknown. */
    public long size(String namespace, String relativePath) {
        Map<String, Long> files = namespaceFiles.get(namespace);
        if (files == null) {
            return -1L;
        }
        Long size = files.get(relativePath);
        return size == null ? -1L : size;
    }

    /** @return {@code true} when the file exists and contains no bytes. */
    public boolean isEmpty(String namespace, String relativePath) {
        return size(namespace, relativePath) == 0L;
    }

    /** @return the candidate paths that would be tried, for diagnostics. */
    public static List<String> describeCandidates(String relativePath, List<String> candidateSuffixes) {
        List<String> candidates = new ArrayList<>(candidateSuffixes.size());
        for (String suffix : candidateSuffixes) {
            candidates.add(relativePath + suffix);
        }
        return candidates;
    }

    /** @return problems encountered while building the index, if any. */
    public List<String> readErrors() {
        return readErrors;
    }
}
