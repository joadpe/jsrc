package com.jsrc.app.output;

import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UTF-8 byte budget + always-valid JSON under truncation tests (B1-B5).
 * Oracles: strict JSON parse (JsonReader + independent validator), UTF-8 byte length, structural validity.
 */
class BudgetAwareJsonFormatterUtf8Test {

    /**
     * Independent strict JSON validator that does NOT use JsonReader.
     * Oracle for S1/S2: validates truncated JSON is well-formed independently.
     */
    private static class StrictJsonValidator {
        private final String input;
        private int pos;

        private StrictJsonValidator(String input) {
            this.input = input;
            this.pos = 0;
        }

        static void validate(String json) {
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("JSON cannot be null or blank");
            }
            var validator = new StrictJsonValidator(json.trim());
            validator.parseValue();
            validator.skipWhitespace();
            if (validator.pos < validator.input.length()) {
                throw new IllegalArgumentException("Trailing content after JSON: " + 
                    validator.input.substring(validator.pos));
            }
        }

        private void parseValue() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = input.charAt(pos);
            switch (c) {
                case '"' -> parseString();
                case '{' -> parseObject();
                case '[' -> parseArray();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> parseNumber();
                default -> throw new IllegalArgumentException("Unexpected char: " + c);
            }
        }

        private void parseString() {
            pos++; // skip opening quote
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') return;
                if (c == '\\') {
                    if (pos >= input.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char escaped = input.charAt(pos++);
                    if (escaped == 'u') {
                        if (pos + 4 > input.length()) {
                            throw new IllegalArgumentException("Invalid unicode escape");
                        }
                        String hex = input.substring(pos, pos + 4);
                        try {
                            Integer.parseInt(hex, 16);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid unicode hex: " + hex);
                        }
                        pos += 4;
                    } else if (escaped != '"' && escaped != '\\' && escaped != '/' && 
                               escaped != 'n' && escaped != 'r' && escaped != 't' && 
                               escaped != 'b' && escaped != 'f') {
                        throw new IllegalArgumentException("Invalid escape: \\" + escaped);
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("Unescaped control char: " + (int)c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private void parseObject() {
            pos++; // skip {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '}') {
                pos++;
                return;
            }
            while (pos < input.length()) {
                skipWhitespace();
                if (input.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected string key");
                }
                parseString();
                skipWhitespace();
                if (pos >= input.length() || input.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected colon");
                }
                pos++;
                parseValue();
                skipWhitespace();
                if (pos >= input.length()) {
                    throw new IllegalArgumentException("Unclosed object");
                }
                if (input.charAt(pos) == ',') {
                    pos++;
                    skipWhitespace();
                    if (pos < input.length() && input.charAt(pos) == '}') {
                        throw new IllegalArgumentException("Trailing comma in object");
                    }
                } else if (input.charAt(pos) == '}') {
                    pos++;
                    return;
                } else {
                    throw new IllegalArgumentException("Expected comma or closing brace");
                }
            }
            throw new IllegalArgumentException("Unclosed object");
        }

        private void parseArray() {
            pos++; // skip [
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ']') {
                pos++;
                return;
            }
            while (pos < input.length()) {
                parseValue();
                skipWhitespace();
                if (pos >= input.length()) {
                    throw new IllegalArgumentException("Unclosed array");
                }
                if (input.charAt(pos) == ',') {
                    pos++;
                    skipWhitespace();
                    if (pos < input.length() && input.charAt(pos) == ']') {
                        throw new IllegalArgumentException("Trailing comma in array");
                    }
                } else if (input.charAt(pos) == ']') {
                    pos++;
                    return;
                } else {
                    throw new IllegalArgumentException("Expected comma or closing bracket");
                }
            }
            throw new IllegalArgumentException("Unclosed array");
        }

        private void parseBoolean() {
            if (input.startsWith("true", pos)) {
                pos += 4;
            } else if (input.startsWith("false", pos)) {
                pos += 5;
            } else {
                throw new IllegalArgumentException("Invalid boolean");
            }
        }

        private void parseNull() {
            if (input.startsWith("null", pos)) {
                pos += 4;
            } else {
                throw new IllegalArgumentException("Invalid null");
            }
        }

        private void parseNumber() {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') pos++;
            if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                throw new IllegalArgumentException("Invalid number");
            }
            if (input.charAt(pos) == '0') {
                pos++;
                if (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Leading zeros not allowed");
                }
            } else {
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            if (pos < input.length() && input.charAt(pos) == '.') {
                pos++;
                if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid decimal");
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid exponent");
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }


    /**
     * B1: Nested objects/arrays + commas inside strings + escaped quotes + small maxBytes
     * → STRICT-independent valid JSON (not only project JsonReader).
     */
    @Test
    void testB1_complexNestedStructuresValidJsonUnderTruncation() {
        var data = new LinkedHashMap<String, Object>();
        data.put("nested", Map.of(
            "inner", Map.of("deep", "value,with,commas"),
            "array", List.of("item1", "item2,quoted", "item3")
        ));
        data.put("escaped", "quoted \"string\" with escapes");
        data.put("commas", "a,b,c,d");

        // Small maxBytes to force truncation
        int maxBytes = 80;
        String truncated = formatWithMaxBytes(data, maxBytes);

        // Oracle 1: Must be valid JSON (strict independent validator - S1)
        assertDoesNotThrow(() -> StrictJsonValidator.validate(truncated),
            "Truncated JSON must pass strict independent validation");

        // Oracle 2: Must be valid JSON (parseable by JsonReader)
        Object parsed = JsonReader.parse(truncated);
        assertNotNull(parsed, "Truncated JSON must be parseable");
        assertTrue(parsed instanceof Map, "Root must be object");

        // Oracle 3: Must contain _truncated marker
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        assertEquals(Boolean.TRUE, map.get("_truncated"), 
            "Truncated JSON must have _truncated:true");

        // Oracle 4: Byte length must not exceed maxBytes
        byte[] bytes = truncated.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= maxBytes, 
            "Byte length " + bytes.length + " exceeds maxBytes " + maxBytes);
    }

    /**
     * B2: Accented + CJK + emoji under budget → getBytes(UTF_8).length ≤ maxBytes;
     * valid JSON; no split code point.
     */
    @Test
    void testB2_unicodeUnderBudgetNoSplitCodePoint() {
        var data = new LinkedHashMap<String, Object>();
        data.put("accented", "café résumé naïve");
        data.put("cjk", "日本語 中文 한글");
        data.put("emoji", "😀🎉🚀🌟");
        data.put("mixed", "Hello 世界 🌍");

        int maxBytes = 100;
        String truncated = formatWithMaxBytes(data, maxBytes);

        // Oracle 1: Byte length ≤ maxBytes (S2)
        byte[] bytes = truncated.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= maxBytes,
            "UTF-8 byte length " + bytes.length + " exceeds maxBytes " + maxBytes);

        // Oracle 2: Strict independent validation (S2)
        assertDoesNotThrow(() -> StrictJsonValidator.validate(truncated),
            "Truncated Unicode JSON must pass strict independent validation");

        // Oracle 3: Valid JSON (JsonReader)
        Object parsed = JsonReader.parse(truncated);
        assertNotNull(parsed, "Truncated Unicode JSON must be parseable");

        // Oracle 4: No split code point (re-encode should match byte length)
        String reEncoded = new String(truncated.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertEquals(truncated, reEncoded, "No split code points allowed");
    }

    /**
     * B3: Oversized list truncation → model shrink then serialize;
     * _truncated/meta structurally valid; strict parse OK.
     */
    @Test
    void testB3_oversizedListTruncationModelFirst() {
        // Create oversized list
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", (long) i);
            item.put("name", "Item " + i);
            item.put("description", "Description for item " + i);
            items.add(item);
        }

        int maxBytes = 500;
        String truncated = formatListWithMaxBytes(items, maxBytes);

        // Oracle 1: Strict independent validation
        assertDoesNotThrow(() -> StrictJsonValidator.validate(truncated),
            "Truncated list must pass strict independent validation");

        // Oracle 2: Valid JSON array
        Object parsed = JsonReader.parse(truncated);
        assertNotNull(parsed, "Truncated list must be parseable");
        assertTrue(parsed instanceof List, "Root must be array");

        // Oracle 3: Byte length ≤ maxBytes
        byte[] bytes = truncated.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= maxBytes,
            "Byte length " + bytes.length + " exceeds maxBytes " + maxBytes);

        // Oracle 4: If wrapped in object, must have _truncated
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            assertEquals(Boolean.TRUE, map.get("_truncated"));
        }
    }

    /**
     * B4: Very small maxBytes → minimal valid fallback; never malformed.
     */
    @Test
    void testB4_verySmallMaxBytesMinimalFallback() {
        var data = new LinkedHashMap<String, Object>();
        data.put("field1", "value1");
        data.put("field2", "value2");
        data.put("field3", "value3");

        // Very small maxBytes (not enough for any field)
        int[] smallLimits = {5, 10, 15, 20, 25};

        for (int maxBytes : smallLimits) {
            String truncated = formatWithMaxBytes(data, maxBytes);

            // Oracle 1: Strict independent validation
            assertDoesNotThrow(() -> StrictJsonValidator.validate(truncated),
                "Even with maxBytes=" + maxBytes + " must pass strict independent validation");

            // Oracle 2: Must be valid JSON
            Object parsed = JsonReader.parse(truncated);
            assertNotNull(parsed, "Even with maxBytes=" + maxBytes + " must produce valid JSON");
            assertTrue(parsed instanceof Map, "Root must be object");

            // Oracle 3: Byte length ≤ maxBytes (ALWAYS enforced)
            byte[] bytes = truncated.getBytes(StandardCharsets.UTF_8);
            assertTrue(bytes.length <= maxBytes,
                "Byte length " + bytes.length + " exceeds maxBytes " + maxBytes);

            // Oracle 4: Must contain _truncated marker if budget allows
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            
            String minFallback = "{\"_truncated\":true}";
            int minFallbackBytes = minFallback.getBytes(StandardCharsets.UTF_8).length;
            
            if (maxBytes >= minFallbackBytes) {
                // If budget allows, must have _truncated marker and be minimal fallback
                assertEquals(Boolean.TRUE, map.get("_truncated"),
                    "Minimal JSON must have _truncated:true when budget allows");
                assertEquals(minFallback, truncated,
                    "Minimal fallback should be {\"_truncated\":true} when budget allows");
            } else {
                // If budget too small, must be valid but minimal (e.g. {})
                assertTrue(truncated.equals("{}") || truncated.equals("[]"),
                    "When maxBytes < 19, fallback must be minimal valid JSON like {} or []");
            }
        }
    }

    /**
     * B5: ASCII under budget → no false truncation (regression).
     */
    @Test
    void testB5_asciiUnderBudgetNoFalseTruncation() {
        var data = new LinkedHashMap<String, Object>();
        data.put("field1", "value1");
        data.put("field2", "value2");
        data.put("count", 42L);

        // Calculate actual JSON size
        String fullJson = JsonWriter.toJson(data);
        int actualSize = fullJson.getBytes(StandardCharsets.UTF_8).length;

        // Set maxBytes ABOVE actual size (should NOT truncate)
        int maxBytes = actualSize + 50;
        String result = formatWithMaxBytes(data, maxBytes);

        // Oracle 1: Strict independent validation
        assertDoesNotThrow(() -> StrictJsonValidator.validate(result),
            "Under-budget JSON must pass strict independent validation");

        // Oracle 2: Must equal original JSON (no truncation)
        assertEquals(fullJson, result,
            "ASCII under budget should not be truncated");

        // Oracle 3: No _truncated marker
        Object parsed = JsonReader.parse(result);
        assertNotNull(parsed);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        assertNull(map.get("_truncated"),
            "Under-budget JSON should NOT have _truncated marker");

        // Oracle 4: Byte length ≤ maxBytes
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= maxBytes);
    }

    // Helper methods

    private String formatWithMaxBytes(Object data, int maxBytes) {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var budgetContext = new BudgetContext(
            BudgetProfile.STANDARD,
            null, // limit
            maxBytes,
            false, // signature
            false, // frozen
            null  // fields
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, out, budgetContext);
        formatter.printResult(data);
        return baos.toString().trim();
    }

    private String formatListWithMaxBytes(List<?> items, int maxBytes) {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var budgetContext = new BudgetContext(
            BudgetProfile.STANDARD,
            null,
            maxBytes,
            false,
            false,
            null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, out, budgetContext);
        formatter.printResult(items);
        return baos.toString().trim();
    }
}
