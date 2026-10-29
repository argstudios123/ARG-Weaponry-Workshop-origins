package com.arg_studios.arg_weaponry.install;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bookkeeping for installed content packs, stored as {@code .install-state.json} in the install root.
 *
 * <p>For every pack the state records the bundled version and fingerprint we installed, the
 * fingerprint of the installed tree as we wrote it, and whether the folder is managed by the mod.
 * A folder that already existed without state is adopted with {@code managed = false} and is never
 * overwritten.</p>
 *
 * <p>Reading is tolerant: a missing or unreadable file simply means "no state", which triggers the
 * adoption path instead of an overwrite.</p>
 *
 * @param schema the state file schema version
 * @param packs  pack identity to entry
 */
public record InstallState(int schema, Map<String, PackEntry> packs) {

    public static final int CURRENT_SCHEMA = 1;

    public InstallState {
        packs = Map.copyOf(packs);
    }

    /**
     * @param bundledVersion  the {@code pack_version} of the bundled pack when the entry was written
     * @param bundledHash     fingerprint of the bundled pack that was installed
     * @param installedHash   fingerprint of the installed tree as written by the mod
     * @param managed         {@code true} when the mod created/owns the folder, {@code false} for adopted folders
     */
    public record PackEntry(String bundledVersion, String bundledHash, String installedHash, boolean managed) {
    }

    public static InstallState empty() {
        return new InstallState(CURRENT_SCHEMA, Map.of());
    }

    /**
     * @param file the state file
     * @return the parsed state, or empty when the file is absent or not usable
     */
    public static Optional<InstallState> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        try {
            JsonElement root = JsoncReader.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root instanceof JsonObject object)) {
                return Optional.empty();
            }

            int schema = JsonValues.asInt(object.get("schema")) == null ? CURRENT_SCHEMA : JsonValues.asInt(object.get("schema"));
            JsonElement packsElement = object.get("packs");
            if (!(packsElement instanceof JsonObject packsObject)) {
                return Optional.empty();
            }

            Map<String, PackEntry> entries = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : packsObject.entrySet()) {
                if (!(entry.getValue() instanceof JsonObject packObject)) {
                    continue;
                }
                String bundledHash = string(packObject, "bundledHash");
                String installedHash = string(packObject, "installedHash");
                if (bundledHash == null || installedHash == null) {
                    continue;
                }
                entries.put(entry.getKey(), new PackEntry(
                        string(packObject, "bundledVersion"),
                        bundledHash,
                        installedHash,
                        JsonValues.isBoolean(packObject.get("managed")) && packObject.get("managed").getAsBoolean()));
            }
            return Optional.of(new InstallState(schema, entries));
        } catch (JsoncReader.JsoncException | IOException e) {
            return Optional.empty();
        }
    }

    public Optional<PackEntry> entry(String packId) {
        return Optional.ofNullable(packs.get(packId));
    }

    public InstallState withPack(String packId, PackEntry entry) {
        Map<String, PackEntry> updated = new LinkedHashMap<>(packs);
        updated.put(packId, entry);
        return new InstallState(schema, updated);
    }

    /**
     * Writes the state file, creating parent directories as needed.
     *
     * <p>The JSON is written to a temporary file next to the target and only then moved into place, so an
     * interrupted write leaves either the previous state file or a leftover temporary file behind, never a
     * half-written state file. Atomic replacement is used where the file system supports it and a plain
     * replace otherwise; the temporary file is removed in both cases.</p>
     *
     * @param file the state file
     * @throws IOException when the file cannot be written
     */
    public void write(Path file) throws IOException {
        JsonObject packsObject = new JsonObject();
        packs.forEach((packId, entry) -> {
            JsonObject entryObject = new JsonObject();
            if (entry.bundledVersion() != null) {
                entryObject.addProperty("bundledVersion", entry.bundledVersion());
            }
            entryObject.addProperty("bundledHash", entry.bundledHash());
            entryObject.addProperty("installedHash", entry.installedHash());
            entryObject.addProperty("managed", entry.managed());
            packsObject.add(packId, entryObject);
        });

        JsonObject root = new JsonObject();
        root.addProperty("schema", schema);
        root.add("packs", packsObject);

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        writeAtomically(file, json + System.lineSeparator());
    }

    /**
     * Writes {@code contents} through a sibling temporary file and moves it onto the target.
     *
     * <p>If the target cannot be replaced atomically the write falls back to a plain replace. Either way an
     * interrupted write cannot produce a truncated state file, because the target is only touched by the
     * move that completes it.</p>
     *
     * @param file     the target file
     * @param contents the text to write
     * @throws IOException when the target cannot be replaced
     */
    private static void writeAtomically(Path file, String contents) throws IOException {
        Path target = file.toAbsolutePath();
        Path directory = target.getParent();
        if (directory == null) {
            throw new IOException("Refusing to write the install state without a parent directory: " + target);
        }

        Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Constants.LOG.debug("[ARG] Atomic replacement is not supported for {}; replacing it directly",
                        target);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return JsonValues.isString(element) ? element.getAsString() : null;
    }
}
