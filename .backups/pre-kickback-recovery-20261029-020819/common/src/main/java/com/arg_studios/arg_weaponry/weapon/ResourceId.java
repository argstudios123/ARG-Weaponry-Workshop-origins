package com.arg_studios.arg_weaponry.weapon;

import java.util.regex.Pattern;

/**
 * A namespaced resource identifier as used by content packs, for example
 * {@code arg_pack:weapons/dmrs/m100_dmr/m100_dmr}.
 *
 * <p>This is deliberately <em>not</em> a Minecraft {@code Identifier}: it validates content-pack
 * input before anything is handed to the game, it never tolerates malformed values, and it carries
 * no meaning beyond "namespace + path". Separate identities stay separate:</p>
 * <ul>
 *     <li>content-pack identity - {@link com.arg_studios.arg_weaponry.pack.ContentPack#id()}</li>
 *     <li>resource namespace - {@link #namespace()}</li>
 *     <li>weapon registry identity - a {@code ResourceId} whose path starts with {@code weapons/}</li>
 *     <li>Minecraft item id - <em>not assigned yet</em> (a later milestone)</li>
 *     <li>model/texture/display/animation/sound resources - {@code ResourceId}s resolved by the
 *     owning content pack</li>
 * </ul>
 *
 * <p>The character rules mirror Minecraft's identifier rules so that a value accepted here can also
 * be used as a game identifier later, but they are enforced with explicit error messages instead of
 * assertions.</p>
 *
 * @param namespace the namespace, for example {@code arg_pack}
 * @param path      the path below the namespace, for example {@code weapons/dmrs/m100_dmr}
 */
public record ResourceId(String namespace, String path) implements Comparable<ResourceId> {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH_PATTERN = Pattern.compile("[a-z0-9/._-]{1,255}");
    private static final Pattern ALLOWED_NAMESPACE_CHARACTERS = Pattern.compile("[^a-z0-9_.-]");
    private static final Pattern ALLOWED_PATH_CHARACTERS = Pattern.compile("[^a-z0-9/._-]");

    public ResourceId {
        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid namespace '" + namespace + "'");
        }
        if (!PATH_PATTERN.matcher(path).matches() || path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            throw new IllegalArgumentException("Invalid path '" + path + "'");
        }
    }

    /**
     * Parses {@code namespace:path} without throwing.
     *
     * @param raw the raw value from a configuration file
     * @return the result, carrying either an identifier or a human readable reason
     */
    public static Result tryParse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Result.invalid("the value is empty; expected '<namespace>:<path>'");
        }

        int separator = raw.indexOf(':');
        if (separator < 0) {
            return Result.invalid("'" + raw + "' has no namespace; expected '<namespace>:<path>'");
        }

        String namespace = raw.substring(0, separator);
        String path = raw.substring(separator + 1);

        if (namespace.isEmpty()) {
            return Result.invalid("'" + raw + "' has an empty namespace");
        }
        String illegalNamespaceCharacters = illegalCharacters(namespace, ALLOWED_NAMESPACE_CHARACTERS);
        if (!illegalNamespaceCharacters.isEmpty()) {
            return Result.invalid("the namespace '" + namespace + "' contains characters that are not allowed: '"
                    + illegalNamespaceCharacters + "' (allowed: a-z 0-9 _ . -)");
        }
        if (namespace.length() > 64) {
            return Result.invalid("the namespace '" + namespace + "' is longer than 64 characters");
        }

        if (path.isEmpty()) {
            return Result.invalid("'" + raw + "' has an empty path");
        }
        String illegalPathCharacters = illegalCharacters(path, ALLOWED_PATH_CHARACTERS);
        if (!illegalPathCharacters.isEmpty()) {
            return Result.invalid("the path '" + path + "' contains characters that are not allowed: '"
                    + illegalPathCharacters + "' (allowed: a-z 0-9 / _ . -)");
        }
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            return Result.invalid("the path '" + path + "' has an empty segment");
        }
        if (path.length() > 255) {
            return Result.invalid("the path is longer than 255 characters");
        }

        return Result.valid(new ResourceId(namespace, path));
    }

    private static String illegalCharacters(String value, Pattern allowedCharacters) {
        StringBuilder illegal = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            String character = String.valueOf(value.charAt(i));
            if (allowedCharacters.matcher(character).matches() && illegal.indexOf(character) < 0) {
                illegal.append(character);
            }
        }
        return illegal.toString();
    }

    /** @return the canonical {@code namespace:path} form. */
    public String asString() {
        return namespace + ":" + path;
    }

    @Override
    public String toString() {
        return asString();
    }

    @Override
    public int compareTo(ResourceId other) {
        return asString().compareTo(other.asString());
    }

    /** @return the last path segment, for example {@code m100_dmr}. */
    public String fileName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The outcome of {@link #tryParse(String)}: either an identifier or a reason why it is invalid. */
    public record Result(ResourceId id, String error) {

        public static Result valid(ResourceId id) {
            return new Result(id, null);
        }

        public static Result invalid(String error) {
            return new Result(null, error);
        }

        public boolean isValid() {
            return id != null;
        }
    }
}
