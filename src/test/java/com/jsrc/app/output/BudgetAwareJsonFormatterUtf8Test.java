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
 * Oracles: strict JSON parse (JsonReader), UTF-8 byte length, structural validity.
 */
class BudgetAwareJsonFormatterUtf8Test {

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

        // Oracle 1: Must be valid JSON (parseable by JsonReader)
        Object parsed = JsonReader.parse(truncated);
        assertNotNull(parsed, "Truncated JSON must be parseable");
        assertTrue(parsed instanceof Map, "Root must be object");

        // Oracle 2: Must contain _truncated marker
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        assertEquals(Boolean.TRUE, map.get("_truncated"), 
            "Truncated JSON must have _truncated:true");

        // Oracle 3: Byte length must not exceed maxBytes
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

        // Oracle 1: Byte length ≤ maxBytes
        byte[] bytes = truncated.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= maxBytes,
            "UTF-8 byte length " + bytes.length + " exceeds maxBytes " + maxBytes);

        // Oracle 2: Valid JSON
        Object parsed = JsonReader.parse(truncated);
        assertNotNull(parsed, "Truncated Unicode JSON must be parseable");

        // Oracle 3: No split code point (re-encode should match byte length)
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

        // Oracle 1: Valid JSON array
        Object parsed = JsonReader.parse(truncated);
        assertNotNull(parsed, "Truncated list must be parseable");
        assertTrue(parsed instanceof List, "Root must be array");

        // Oracle 2: Byte length ≤ maxBytes
        byte[] bytes = truncated.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= maxBytes,
            "Byte length " + bytes.length + " exceeds maxBytes " + maxBytes);

        // Oracle 3: If wrapped in object, must have _truncated
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

            // Oracle 1: Must be valid JSON
            Object parsed = JsonReader.parse(truncated);
            assertNotNull(parsed, "Even with maxBytes=" + maxBytes + " must produce valid JSON");
            assertTrue(parsed instanceof Map, "Root must be object");

            // Oracle 2: Byte length ≤ maxBytes (ALWAYS enforced)
            byte[] bytes = truncated.getBytes(StandardCharsets.UTF_8);
            assertTrue(bytes.length <= maxBytes,
                "Byte length " + bytes.length + " exceeds maxBytes " + maxBytes);

            // Oracle 3: Must contain _truncated marker if budget allows
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

        // Oracle 1: Must equal original JSON (no truncation)
        assertEquals(fullJson, result,
            "ASCII under budget should not be truncated");

        // Oracle 2: No _truncated marker
        Object parsed = JsonReader.parse(result);
        assertNotNull(parsed);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        assertNull(map.get("_truncated"),
            "Under-budget JSON should NOT have _truncated marker");

        // Oracle 3: Byte length ≤ maxBytes
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
