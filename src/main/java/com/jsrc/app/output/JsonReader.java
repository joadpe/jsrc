package com.jsrc.app.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser with no external dependencies.
 * Supports strings, numbers (long), booleans, nulls, arrays, and objects.
 */
public final class JsonReader {

    private final String input;
    private int pos;

    private JsonReader(String input) {
        this.input = input;
        this.pos = 0;
    }

    /**
     * Parses a JSON string into Java objects.
     *
     * @return String, Long, Boolean, null, List, or Map
     */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        JsonReader reader = new JsonReader(json.trim());
        return reader.readValue();
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= input.length()) return null;

        char c = input.charAt(pos);
        return switch (c) {
            case '"' -> readString();
            case '{' -> readObject();
            case '[' -> readArray();
            case 't', 'f' -> readBoolean();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    private String readString() {
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\' && pos < input.length()) {
                char escaped = input.charAt(pos++);
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        String hex = input.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> sb.append(escaped);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Map<String, Object> readObject() {
        pos++; // skip {
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (pos < input.length() && input.charAt(pos) == '}') {
            pos++;
            return map;
        }

        while (pos < input.length()) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            map.put(key, value);
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ',') {
                pos++;
            } else {
                break;
            }
        }
        skipWhitespace();
        if (pos < input.length() && input.charAt(pos) == '}') pos++;
        return map;
    }

    private List<Object> readArray() {
        pos++; // skip [
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (pos < input.length() && input.charAt(pos) == ']') {
            pos++;
            return list;
        }

        while (pos < input.length()) {
            list.add(readValue());
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ',') {
                pos++;
            } else {
                break;
            }
        }
        skipWhitespace();
        if (pos < input.length() && input.charAt(pos) == ']') pos++;
        return list;
    }

    private Boolean readBoolean() {
        if (input.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        pos += 5;
        return Boolean.FALSE;
    }

    private Object readNull() {
        pos += 4;
        return null;
    }

    private Long readNumber() {
        int start = pos;
        if (pos < input.length() && input.charAt(pos) == '-') pos++;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        // Skip decimal part if present (parse as long by truncating)
        if (pos < input.length() && input.charAt(pos) == '.') {
            pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }
        return Long.parseLong(input.substring(start, pos).split("\\.")[0]);
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private void expect(char expected) {
        skipWhitespace();
        if (pos < input.length() && input.charAt(pos) == expected) {
            pos++;
        }
    }
}
