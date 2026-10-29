package com.arg_studios.arg_weaponry.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;

/**
 * Strict JSON reader with JSONC extensions, used for every ARG Weaponry configuration file.
 *
 * <p>Supported on top of plain JSON:</p>
 * <ul>
 *     <li>{@code //} line comments,</li>
 *     <li>{@code /*} block comments,</li>
 *     <li>a single trailing comma in objects and arrays.</li>
 * </ul>
 *
 * <p>Everything else is rejected: unquoted keys, single quoted strings, unquoted strings,
 * {@code NaN}/{@code Infinity}, hexadecimal numbers, leading zeros, duplicate keys and trailing
 * content after the top-level value. Content-pack input is never executed, it is only parsed into
 * Gson's JSON tree, which keeps the parser reusable for the weapon, display and animation
 * definitions that follow in later milestones.</p>
 *
 * <p>Comments are handled by the tokenizer itself rather than by rewriting the input text first, so
 * reported line and column numbers always refer to the original file exactly as the creator wrote
 * it.</p>
 */
public final class JsoncReader {

    /** Guards against stack exhaustion from maliciously nested content-pack input. */
    private static final int MAX_DEPTH = 512;

    private final String text;
    private int index;
    private int line = 1;
    private int column = 1;

    private JsoncReader(String text) {
        this.text = text;
    }

    /**
     * Parses a JSONC document.
     *
     * @param text the file content
     * @return the parsed JSON tree
     * @throws JsoncException if the document is not valid JSONC, with line and column information
     */
    public static JsonElement parse(String text) throws JsoncException {
        JsoncReader reader = new JsoncReader(stripByteOrderMark(text));
        reader.skipTrivia();
        if (reader.atEnd()) {
            throw reader.error("Empty JSON document");
        }

        JsonElement value = reader.readValue(0);
        reader.skipTrivia();
        if (!reader.atEnd()) {
            throw reader.error("Unexpected trailing content after the top-level value");
        }
        return value;
    }

    private static String stripByteOrderMark(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private boolean atEnd() {
        return index >= text.length();
    }

    private char current() {
        return text.charAt(index);
    }

    private char lookAhead(int offset) {
        int position = index + offset;
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private void advance() {
        char c = text.charAt(index++);
        if (c == '\r') {
            if (index < text.length() && text.charAt(index) == '\n') {
                index++;
            }
            line++;
            column = 1;
        } else if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
    }

    /** Skips whitespace and comments. */
    private void skipTrivia() throws JsoncException {
        while (!atEnd()) {
            char c = current();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '/' && lookAhead(1) == '/') {
                skipLineComment();
            } else if (c == '/' && lookAhead(1) == '*') {
                skipBlockComment();
            } else {
                return;
            }
        }
    }

    private void skipLineComment() {
        advance();
        advance();
        while (!atEnd() && current() != '\n' && current() != '\r') {
            advance();
        }
    }

    private void skipBlockComment() throws JsoncException {
        int startLine = line;
        int startColumn = column;
        advance();
        advance();
        while (!atEnd()) {
            if (current() == '*' && lookAhead(1) == '/') {
                advance();
                advance();
                return;
            }
            advance();
        }
        throw errorAt("Unterminated block comment opened at line " + startLine + ", column " + startColumn,
                startLine, startColumn);
    }

    private JsonElement readValue(int depth) throws JsoncException {
        if (depth > MAX_DEPTH) {
            throw error("Maximum nesting depth of " + MAX_DEPTH + " exceeded");
        }
        if (atEnd()) {
            throw error("Unexpected end of document, expected a value");
        }

        char c = current();
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> new JsonPrimitive(readString());
            case 't' -> {
                expectLiteral("true");
                yield new JsonPrimitive(Boolean.TRUE);
            }
            case 'f' -> {
                expectLiteral("false");
                yield new JsonPrimitive(Boolean.FALSE);
            }
            case 'n' -> {
                expectLiteral("null");
                yield JsonNull.INSTANCE;
            }
            default -> {
                if (c == '-' || isDigit(c)) {
                    yield readNumber();
                }
                throw error("Unexpected character '" + describeChar(c) + "', expected a value");
            }
        };
    }

    private JsonObject readObject(int depth) throws JsoncException {
        int openLine = line;
        int openColumn = column;
        advance(); // '{'

        JsonObject object = new JsonObject();
        skipTrivia();
        if (!atEnd() && current() == '}') {
            advance();
            return object;
        }

        while (true) {
            skipTrivia();
            if (atEnd()) {
                throw errorAt("Unterminated object opened at line " + openLine + ", column " + openColumn,
                        openLine, openColumn);
            }
            if (current() != '"') {
                throw error("Expected a quoted object key but found '" + describeChar(current()) + "'");
            }

            int keyLine = line;
            int keyColumn = column;
            String key = readString();

            skipTrivia();
            expect(':');
            skipTrivia();

            JsonElement value = readValue(depth + 1);
            if (object.has(key)) {
                throw errorAt("Duplicate object key '" + key + "'", keyLine, keyColumn);
            }
            object.add(key, value);

            skipTrivia();
            if (atEnd()) {
                throw errorAt("Unterminated object opened at line " + openLine + ", column " + openColumn,
                        openLine, openColumn);
            }

            char c = current();
            if (c == ',') {
                advance();
                skipTrivia();
                if (!atEnd() && current() == '}') {
                    advance();
                    return object;
                }
                continue;
            }
            if (c == '}') {
                advance();
                return object;
            }
            throw error("Expected ',' or '}' in object but found '" + describeChar(c) + "'");
        }
    }

    private JsonArray readArray(int depth) throws JsoncException {
        int openLine = line;
        int openColumn = column;
        advance(); // '['

        JsonArray array = new JsonArray();
        skipTrivia();
        if (!atEnd() && current() == ']') {
            advance();
            return array;
        }

        while (true) {
            skipTrivia();
            array.add(readValue(depth + 1));

            skipTrivia();
            if (atEnd()) {
                throw errorAt("Unterminated array opened at line " + openLine + ", column " + openColumn,
                        openLine, openColumn);
            }

            char c = current();
            if (c == ',') {
                advance();
                skipTrivia();
                if (!atEnd() && current() == ']') {
                    advance();
                    return array;
                }
                continue;
            }
            if (c == ']') {
                advance();
                return array;
            }
            throw error("Expected ',' or ']' in array but found '" + describeChar(c) + "'");
        }
    }

    private String readString() throws JsoncException {
        int openLine = line;
        int openColumn = column;
        advance(); // opening quote

        StringBuilder builder = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw errorAt("Unterminated string opened at line " + openLine + ", column " + openColumn,
                        openLine, openColumn);
            }

            char c = current();
            if (c == '"') {
                advance();
                return builder.toString();
            }
            if (c == '\\') {
                advance();
                if (atEnd()) {
                    throw errorAt("Unterminated string opened at line " + openLine + ", column " + openColumn,
                            openLine, openColumn);
                }
                char escape = current();
                switch (escape) {
                    case '"' -> {
                        builder.append('"');
                        advance();
                    }
                    case '\\' -> {
                        builder.append('\\');
                        advance();
                    }
                    case '/' -> {
                        builder.append('/');
                        advance();
                    }
                    case 'b' -> {
                        builder.append('\b');
                        advance();
                    }
                    case 'f' -> {
                        builder.append('\f');
                        advance();
                    }
                    case 'n' -> {
                        builder.append('\n');
                        advance();
                    }
                    case 'r' -> {
                        builder.append('\r');
                        advance();
                    }
                    case 't' -> {
                        builder.append('\t');
                        advance();
                    }
                    case 'u' -> {
                        advance();
                        if (index + 4 > text.length()) {
                            throw error("Truncated \\u escape sequence in string");
                        }
                        int codePoint = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(current(), 16);
                            if (digit < 0) {
                                throw error("Invalid hexadecimal digit in \\u escape sequence");
                            }
                            codePoint = codePoint * 16 + digit;
                            advance();
                        }
                        builder.append((char) codePoint);
                    }
                    default -> throw error("Invalid escape sequence '\\" + describeChar(escape) + "' in string");
                }
                continue;
            }
            if (c < 0x20) {
                throw error("Unescaped control character U+" + String.format("%04X", (int) c) + " in string");
            }
            builder.append(c);
            advance();
        }
    }

    private JsonPrimitive readNumber() throws JsoncException {
        int start = index;
        if (!atEnd() && current() == '-') {
            advance();
        }

        if (atEnd() || !isDigit(current())) {
            throw error("Invalid number: expected a digit");
        }
        if (current() == '0') {
            advance();
        } else {
            while (!atEnd() && isDigit(current())) {
                advance();
            }
        }

        if (!atEnd() && current() == '.') {
            advance();
            if (atEnd() || !isDigit(current())) {
                throw error("Invalid number: expected a digit after the decimal point");
            }
            while (!atEnd() && isDigit(current())) {
                advance();
            }
        }

        if (!atEnd() && (current() == 'e' || current() == 'E')) {
            advance();
            if (!atEnd() && (current() == '+' || current() == '-')) {
                advance();
            }
            if (atEnd() || !isDigit(current())) {
                throw error("Invalid number: expected a digit in the exponent");
            }
            while (!atEnd() && isDigit(current())) {
                advance();
            }
        }

        String literal = text.substring(start, index);
        if (literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0) {
            try {
                return new JsonPrimitive(Long.parseLong(literal));
            } catch (NumberFormatException ignored) {
                // Falls through to BigDecimal for integers that do not fit into a long.
            }
        }
        try {
            return new JsonPrimitive(new BigDecimal(literal));
        } catch (NumberFormatException e) {
            throw error("Invalid number '" + literal + "'");
        }
    }

    private void expectLiteral(String literal) throws JsoncException {
        if (!text.startsWith(literal, index)) {
            throw error("Unexpected token, expected '" + literal + "'");
        }
        for (int i = 0; i < literal.length(); i++) {
            advance();
        }
    }

    private void expect(char expected) throws JsoncException {
        if (atEnd()) {
            throw error("Unexpected end of document, expected '" + expected + "'");
        }
        if (current() != expected) {
            throw error("Expected '" + expected + "' but found '" + describeChar(current()) + "'");
        }
        advance();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String describeChar(char c) {
        if (c >= 0x20 && c != 0x7F) {
            return String.valueOf(c);
        }
        return String.format("\\u%04X", (int) c);
    }

    private JsoncException error(String message) {
        return new JsoncException(message, line, column);
    }

    private JsoncException errorAt(String message, int errorLine, int errorColumn) {
        return new JsoncException(message, errorLine, errorColumn);
    }

    /** Thrown when a document is not valid JSONC; carries the position in the original file. */
    public static final class JsoncException extends Exception {

        private final int line;
        private final int column;

        public JsoncException(String message, int line, int column) {
            super(message);
            this.line = line;
            this.column = column;
        }

        /** @return the one-based line, or {@code -1} when unknown. */
        public int line() {
            return line;
        }

        /** @return the one-based column, or {@code -1} when unknown. */
        public int column() {
            return column;
        }

        /** @return the message prefixed with the position, for logging. */
        public String describe() {
            if (line <= 0) {
                return getMessage();
            }
            return "line " + line + ", column " + column + ": " + getMessage();
        }

        /** @return the position as a short string, or {@code null} when unknown. */
        public String describeLocation() {
            return line <= 0 ? null : "line " + line + ", column " + column;
        }
    }
}
