package com.arg_studios.arg_weaponry.item;

import java.util.Locale;

/**
 * The naming rules shared by everything this mod registers: item translation keys and the readable fallback that a
 * content pack gets when it ships no language file.
 *
 * <h2>Why a fallback exists at all</h2>
 * <p>A registered item's name is the translation key {@code item.<namespace>.<path>}, which Minecraft resolves from the
 * language files of every resource pack - including the content pack itself, whose {@code assets/<namespace>/lang/en_us.json}
 * is mounted as a resource pack by {@code ContentPackClientPack}. A pack that ships no language file therefore shows the raw
 * key, and a pack that ships an empty one used to break the whole language load. Both cases are covered here: the item asks
 * for its translation key and falls back to the name the creator already wrote, humanised, when no translation exists.</p>
 *
 * <h2>Nothing here touches Minecraft</h2>
 * <p>Only pure string work, so the naming rules are unit tested without a running game.</p>
 */
public final class ItemDisplayName {

    private ItemDisplayName() {
    }

    /**
     * @param namespace a resource namespace, for example {@code arg_pack}
     * @param path      the registry path, for example {@code m100_dmr}
     * @return the vanilla item translation key, {@code item.<namespace>.<path>}
     */
    public static String itemKey(String namespace, String path) {
        return "item." + namespace + "." + path;
    }

    /**
     * Turns a declared identifier into a readable fallback name.
     *
     * <p>Separators become spaces and every word is capitalised: {@code m100_dmr} becomes {@code M100 Dmr},
     * {@code creative_ammo} becomes {@code Creative Ammo}. A value that already reads like a display name (it contains a
     * space or an upper-case letter after the first character) is returned unchanged, so a creator who wrote
     * {@code "M100 DMR"} is never overridden by this rule.</p>
     *
     * @param declared the name a definition declared, may be {@code null}
     * @return a readable name, never {@code null}; the given value when it already looks like one
     */
    public static String humanize(String declared) {
        if (declared == null) {
            return "";
        }
        String trimmed = declared.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (looksLikeDisplayName(trimmed)) {
            return trimmed;
        }
        StringBuilder out = new StringBuilder(trimmed.length());
        boolean startOfWord = true;
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (character == '_' || character == '-' || character == '/' || character == '.') {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                startOfWord = true;
                continue;
            }
            if (character == ' ') {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                startOfWord = true;
                continue;
            }
            out.append(startOfWord ? Character.toUpperCase(character) : character);
            startOfWord = false;
        }
        return out.toString().trim();
    }

    /**
     * @param value a declared name
     * @return whether it already reads like a display name rather than an identifier
     */
    private static boolean looksLikeDisplayName(String value) {
        if (value.indexOf(' ') >= 0) {
            return true;
        }
        for (int index = 1; index < value.length(); index++) {
            if (Character.isUpperCase(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param value any identifier
     * @return the value lower-cased, for deterministic comparisons
     */
    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
