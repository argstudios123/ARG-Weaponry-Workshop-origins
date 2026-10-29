package com.arg_studios.arg_weaponry.install;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.pack.ContentPackScanner;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Installs the content packs that are bundled inside the mod into {@code <gameDir>/arg_weaponry/}
 * and keeps them up to date without ever discarding a creator's or user's edits.
 *
 * <p>Policies:</p>
 * <ul>
 *     <li><b>Fresh install</b> — the bundled pack is staged in {@code .staging/}, verified against the
 *     bundled fingerprint and then moved into place.</li>
 *     <li><b>Adoption</b> — a folder that exists without install state is recorded as
 *     {@code managed = false} and left completely untouched. Adopted folders are never updated
 *     automatically; a changed bundled version is only reported.</li>
 *     <li><b>Unmodified, bundled changed</b> — a verified backup is written to
 *     {@code .backups/<pack>/<timestamp>/} before the folder is replaced. If the backup or the staged
 *     copy cannot be verified the update is aborted and the existing folder stays as it is.</li>
 *     <li><b>Modified locally</b> — nothing is written; the situation is reported and the user is told
 *     how to refresh the pack.</li>
 * </ul>
 *
 * <p>Every recursive delete verifies that the target is a path this class created below the install
 * root, so a failed update can never remove an unrelated directory.</p>
 */
public final class BundledPackInstaller {

    /** Folder name of the bundled default pack, both inside the mod and in the install root. */
    public static final String DEFAULT_PACK_ID = "arg_default_pack";

    /** Name of the install-state file inside the install root. */
    public static final String STATE_FILE_NAME = ".install-state.json";

    private static final String STAGING_DIRECTORY = ".staging";
    private static final String BACKUP_DIRECTORY = ".backups";
    private static final int MAX_BACKUPS = 3;
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private BundledPackInstaller() {
    }

    /** @return the install root for the given game directory. */
    public static Path installRoot(Path gameDir) {
        return gameDir.resolve(Constants.MOD_ID);
    }

    /**
     * Installs or updates the bundled packs and returns the install root, which is always the location
     * that content packs are discovered from (whether or not it exists).
     *
     * @param gameDir  the game directory
     * @param modRoots the mod's content roots, used to read the bundled pack
     * @param report   the report to collect diagnostics in
     * @return the install root
     */
    public static Path install(Path gameDir, List<Path> modRoots, ValidationReport report) {
        Path root = installRoot(gameDir);
        Path bundled = findBundledPack(modRoots);
        if (bundled == null) {
            report.error(ValidationReport.frameworkScope(), ContentPackScanner.PACKS_DIRECTORY + "/" + DEFAULT_PACK_ID,
                    "The bundled content pack was not found inside the mod, so nothing was installed");
            return root;
        }

        String bundledHash;
        try {
            bundledHash = PackFingerprint.of(bundled);
        } catch (IOException e) {
            report.error(DEFAULT_PACK_ID, bundled.toString(),
                    "Could not read the bundled content pack: " + e.getMessage() + ". Nothing was installed");
            return root;
        }

        String bundledVersion = readVersion(bundled);

        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            report.error(DEFAULT_PACK_ID, root.toString(), "Could not create the install directory: " + e.getMessage()
                    + ". The bundled copy inside the mod will be used directly");
            return root;
        }

        cleanStaging(root, report);

        Path target = root.resolve(DEFAULT_PACK_ID);
        Path stateFile = root.resolve(STATE_FILE_NAME);
        Optional<InstallState> storedState = InstallState.read(stateFile);
        if (storedState.isEmpty() && Files.isRegularFile(stateFile)) {
            report.warning(DEFAULT_PACK_ID, stateFile.toString(),
                    "The install state file exists but could not be read, so this run cannot tell whether the "
                            + "content pack folder was installed by the mod and it is treated as an adoption. The "
                            + "folder is left untouched and will not be updated automatically until a valid '"
                            + STATE_FILE_NAME + "' is restored");
        }
        InstallState state = storedState.orElseGet(InstallState::empty);

        applyPolicy(root, target, bundled, bundledHash, bundledVersion, stateFile, state, report);
        return root;
    }

    private static Path findBundledPack(List<Path> modRoots) {
        for (Path modRoot : modRoots) {
            Path candidate = modRoot.resolve(ContentPackScanner.PACKS_DIRECTORY).resolve(DEFAULT_PACK_ID);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String readVersion(Path packRoot) {
        Path file = packRoot.resolve("pack_id.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonElement root = JsoncReader.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (root instanceof JsonObject object && JsonValues.isString(object.get("pack_version"))) {
                return object.get("pack_version").getAsString();
            }
        } catch (JsoncReader.JsoncException | IOException e) {
            Constants.LOG.debug("[ARG] Could not read the bundled pack version from {}", file);
        }
        return null;
    }

    // --- update policy ---

    private static void applyPolicy(Path root, Path target, Path bundled, String bundledHash, String bundledVersion,
                                    Path stateFile, InstallState state, ValidationReport report) {
        if (!Files.exists(target)) {
            installFresh(root, target, bundled, bundledHash, bundledVersion, stateFile, state, report);
            return;
        }

        if (!Files.isDirectory(target)) {
            report.error(DEFAULT_PACK_ID, target.toString(),
                    "The install location exists but is not a directory; the bundled pack was not installed");
            return;
        }

        String installedHash;
        try {
            installedHash = PackFingerprint.of(target);
        } catch (IOException e) {
            report.error(DEFAULT_PACK_ID, target.toString(),
                    "Could not read the installed content pack: " + e.getMessage() + ". It was left untouched");
            return;
        }

        Optional<InstallState.PackEntry> entry = state.entry(DEFAULT_PACK_ID);
        if (entry.isEmpty()) {
            InstallState adopted = state.withPack(DEFAULT_PACK_ID,
                    new InstallState.PackEntry(bundledVersion, bundledHash, installedHash, false));
            writeState(adopted, stateFile, report);
            report.info(DEFAULT_PACK_ID, target.toString(),
                    "Found an existing content pack folder that this mod did not install; it was adopted without "
                            + "changes. Adopted folders are never overwritten: when the bundled pack changes, delete "
                            + "this folder to receive the bundled version");
            return;
        }

        InstallState.PackEntry current = entry.get();
        boolean modifiedLocally = !current.installedHash().equals(installedHash);
        boolean bundledChanged = !current.bundledHash().equals(bundledHash);

        if (!current.managed()) {
            if (bundledChanged) {
                report.warning(DEFAULT_PACK_ID, target.toString(),
                        "This content pack folder is adopted (not installed by the mod) and the bundled pack changed"
                                + versionChange(current.bundledVersion(), bundledVersion)
                                + ". It was not modified; delete the folder to receive the bundled version");
            }
            return;
        }

        if (modifiedLocally) {
            report.warning(DEFAULT_PACK_ID, target.toString(), bundledChanged
                    ? "The installed content pack was modified locally and the bundled pack changed"
                    + versionChange(current.bundledVersion(), bundledVersion)
                    + ". The local files were preserved and the update was skipped; delete the folder to receive "
                    + "the bundled version"
                    : "The installed content pack was modified locally, so it was not updated. Delete the folder to "
                    + "restore the bundled version");
            return;
        }

        if (!bundledChanged) {
            Constants.LOG.debug("[ARG] Installed content pack '{}' is up to date", DEFAULT_PACK_ID);
            return;
        }

        try {
            backup(root, target, installedHash);
        } catch (IOException e) {
            report.error(DEFAULT_PACK_ID, target.toString(),
                    "Could not create a verified backup of the installed content pack: " + e.getMessage()
                            + ". The update was aborted and the existing pack was preserved");
            return;
        }

        try {
            stageAndSwap(root, target, bundled, bundledHash);
        } catch (IOException e) {
            report.error(DEFAULT_PACK_ID, target.toString(), "Failed to update the installed content pack: "
                    + e.getMessage() + ". A backup was kept in " + root.resolve(BACKUP_DIRECTORY)
                    + " and the install root was not modified further");
            return;
        }

        writeState(state.withPack(DEFAULT_PACK_ID,
                        new InstallState.PackEntry(bundledVersion, bundledHash, bundledHash, true)),
                stateFile, report);
        report.info(DEFAULT_PACK_ID, target.toString(), "Updated the installed content pack"
                + versionChange(current.bundledVersion(), bundledVersion));
    }

    // --- fresh install and state writing ---

    private static void installFresh(Path root, Path target, Path bundled, String bundledHash, String bundledVersion,
                                     Path stateFile, InstallState state, ValidationReport report) {
        try {
            stageAndSwap(root, target, bundled, bundledHash);
        } catch (IOException e) {
            report.error(DEFAULT_PACK_ID, target.toString(), "Failed to install the bundled content pack: "
                    + e.getMessage() + ". The bundled copy inside the mod will be used instead");
            return;
        }

        writeState(state.withPack(DEFAULT_PACK_ID,
                        new InstallState.PackEntry(bundledVersion, bundledHash, bundledHash, true)),
                stateFile, report);
        report.info(DEFAULT_PACK_ID, target.toString(), "Installed the bundled content pack"
                + (bundledVersion == null ? "" : " (version " + bundledVersion + ")"));
    }

    private static void writeState(InstallState state, Path stateFile, ValidationReport report) {
        try {
            state.write(stateFile);
        } catch (IOException e) {
            report.warning(DEFAULT_PACK_ID, stateFile.toString(), "Could not write the install state: "
                    + e.getMessage() + ". The pack is installed, but update tracking is unavailable until the next "
                    + "successful write");
        }
    }

    private static String versionChange(String previous, String current) {
        if (previous == null && current == null) {
            return "";
        }
        if (previous == null) {
            return " (version " + current + ")";
        }
        if (current == null) {
            return " (previously version " + previous + ")";
        }
        if (previous.equals(current)) {
            return " (version " + current + ")";
        }
        return " (version " + previous + " -> " + current + ")";
    }

    // --- file operations ---

    /**
     * Copies the bundled pack into {@code .staging/}, verifies it, then swaps it into place. The
     * previous folder is only removed after the new folder is in place; if the swap fails it is
     * restored.
     */
    private static void stageAndSwap(Path root, Path target, Path bundled, String bundledHash) throws IOException {
        Path stagingRoot = root.resolve(STAGING_DIRECTORY);
        Path staged = stagingRoot.resolve(DEFAULT_PACK_ID);
        Path previous = stagingRoot.resolve(DEFAULT_PACK_ID + ".old");

        Files.createDirectories(stagingRoot);
        deleteRecursively(staged, root);
        deleteRecursively(previous, root);

        copyTree(bundled, staged);
        String stagedHash = PackFingerprint.of(staged);
        if (!stagedHash.equals(bundledHash)) {
            deleteRecursively(staged, root);
            throw new IOException("the staged copy does not match the bundled content pack");
        }

        boolean hadExistingFolder = Files.isDirectory(target);
        if (hadExistingFolder) {
            Files.move(target, previous);
        }
        try {
            Files.move(staged, target);
        } catch (IOException e) {
            if (hadExistingFolder && Files.isDirectory(previous)) {
                Files.move(previous, target);
            }
            throw e;
        }
        deleteRecursively(previous, root);
        removeIfEmpty(stagingRoot);
    }

    /** Removes an internal directory when it is empty, so stray folders never look like content packs. */
    private static void removeIfEmpty(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            if (children.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        } catch (IOException e) {
            Constants.LOG.debug("[ARG] Could not remove the empty staging directory {}", directory);
        }
    }

    /**
     * Copies the current installed pack into {@code .backups/} and verifies the copy against the
     * fingerprint of the folder that is about to be replaced.
     */
    private static void backup(Path root, Path target, String expectedHash) throws IOException {
        Path backupsRoot = root.resolve(BACKUP_DIRECTORY).resolve(DEFAULT_PACK_ID);
        Files.createDirectories(backupsRoot);

        Path backup = backupsRoot.resolve(LocalDateTime.now().format(BACKUP_TIMESTAMP));
        deleteRecursively(backup, root);
        copyTree(target, backup);

        if (!PackFingerprint.of(backup).equals(expectedHash)) {
            deleteRecursively(backup, root);
            throw new IOException("the backup could not be verified against the installed content pack");
        }
        pruneBackups(backupsRoot, root);
    }

    private static void pruneBackups(Path backupsRoot, Path root) throws IOException {
        List<Path> backups;
        try (Stream<Path> children = Files.list(backupsRoot)) {
            backups = children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        }
        for (int index = MAX_BACKUPS; index < backups.size(); index++) {
            deleteRecursively(backups.get(index), root);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.sorted().toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Deletes a tree, but only when it is a path this installer created below the install root. The
     * install root itself can never be deleted.
     */
    private static void deleteRecursively(Path path, Path root) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }

        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (normalizedPath.equals(normalizedRoot)) {
            throw new IOException("refusing to delete the install root itself");
        }
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new IOException("refusing to delete a path outside the install root: " + path);
        }

        try (Stream<Path> walk = Files.walk(path)) {
            for (Path child : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }

    private static void cleanStaging(Path root, ValidationReport report) {
        Path staging = root.resolve(STAGING_DIRECTORY);
        try {
            deleteRecursively(staging, root);
        } catch (IOException e) {
            report.warning(DEFAULT_PACK_ID, staging.toString(),
                    "Could not remove a leftover staging directory: " + e.getMessage());
        }
    }
}

