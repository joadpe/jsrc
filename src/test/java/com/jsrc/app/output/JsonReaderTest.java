package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonReaderTest {

    @Test
    @DisplayName("Should parse null")
    void shouldParseNull() {
        assertNull(JsonReader.parse("null"));
    }

    @Test
    @DisplayName("Should parse booleans")
    void shouldParseBooleans() {
        assertEquals(Boolean.TRUE, JsonReader.parse("true"));
        assertEquals(Boolean.FALSE, JsonReader.parse("false"));
    }

    @Test
    @DisplayName("Should parse integers")
    void shouldParseIntegers() {
        assertEquals(42L, JsonReader.parse("42"));
        assertEquals(-1L, JsonReader.parse("-1"));
        assertEquals(0L, JsonReader.parse("0"));
    }

    @Test
    @DisplayName("Should parse strings")
    void shouldParseStrings() {
        assertEquals("hello", JsonReader.parse("\"hello\""));
        assertEquals("he said \"hi\"", JsonReader.parse("\"he said \\\"hi\\\"\""));
        assertEquals("line1\nline2", JsonReader.parse("\"line1\\nline2\""));
        assertEquals("tab\there", JsonReader.parse("\"tab\\there\""));
    }

    @Test
    @DisplayName("Should parse empty array")
    void shouldParseEmptyArray() {
        Object result = JsonReader.parse("[]");
        assertInstanceOf(List.class, result);
        assertTrue(((List<?>) result).isEmpty());
    }

    @Test
    @DisplayName("Should parse array of numbers")
    void shouldParseNumberArray() {
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) JsonReader.parse("[1,2,3]");
        assertEquals(3, result.size());
        assertEquals(1L, result.get(0));
    }

    @Test
    @DisplayName("Should parse empty object")
    void shouldParseEmptyObject() {
        Object result = JsonReader.parse("{}");
        assertInstanceOf(Map.class, result);
        assertTrue(((Map<?, ?>) result).isEmpty());
    }

    @Test
    @DisplayName("Should parse object with mixed values")
    void shouldParseObject() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonReader.parse(
                "{\"name\":\"test\",\"count\":5,\"active\":true}");
        assertEquals("test", result.get("name"));
        assertEquals(5L, result.get("count"));
        assertEquals(Boolean.TRUE, result.get("active"));
    }

    @Test
    @DisplayName("Should parse nested structures")
    void shouldParseNested() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonReader.parse(
                "{\"data\":{\"x\":1},\"items\":[\"a\",\"b\"]}");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(1L, data.get("x"));
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) result.get("items");
        assertEquals(2, items.size());
    }

    @Test
    @DisplayName("Should handle whitespace")
    void shouldHandleWhitespace() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonReader.parse(
                "{ \"key\" : \"value\" , \"num\" : 42 }");
        assertEquals("value", result.get("key"));
        assertEquals(42L, result.get("num"));
    }

    @Test
    @DisplayName("Roundtrip: JsonWriter -> JsonReader")
    void shouldRoundtrip() {
        var original = new java.util.LinkedHashMap<String, Object>();
        original.put("name", "test");
        original.put("count", 42);
        original.put("items", List.of("a", "b"));
        original.put("active", true);

        String json = JsonWriter.toJson(original);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) JsonReader.parse(json);

        assertEquals("test", parsed.get("name"));
        assertEquals(42L, parsed.get("count"));
        assertEquals(Boolean.TRUE, parsed.get("active"));
    }
}
