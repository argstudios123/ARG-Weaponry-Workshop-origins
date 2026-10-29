package com.arg_studios.arg_weaponry.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Computes a deterministic fingerprint of a content-pack file tree.
 *
 * <p>The fingerprint covers the relative path and the bytes of every regular file, sorted by path, so
 * it changes when a file is added, removed, renamed or edited. It is used to detect whether the
 * bundled pack changed between mod versions and whether an installed copy was modified locally.</p>
 *
 * <p>Works for ordinary directories and for archive file systems, because both are just
 * {@link Path}s here.</p>
 */
public final class PackFingerprint {

    private static final int BUFFER_SIZE = 8192;
    private static final String ALGORITHM = "SHA-256";

    private PackFingerprint() {
    }

    /**
     * @param packRoot the pack directory
     * @return the hex fingerprint of every regular file below the directory
     * @throws IOException when the tree cannot be read
     */
    public static String of(Path packRoot) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(packRoot)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(file -> relativePath(packRoot, file)))
                    .toList();
        }

        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        for (Path file : files) {
            digest.update(relativePath(packRoot, file).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is not available in this JVM", e);
        }
    }
}
