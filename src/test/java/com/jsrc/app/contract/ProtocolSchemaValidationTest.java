package com.jsrc.app.contract;

import com.jsrc.app.output.JsonReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolSchemaValidationTest {

    @Test
    void acceptsACompleteEnvelope() throws Exception {
        Map<String, Object> envelope = validEnvelope();

        assertTrue(MinimalJsonSchemaValidator.validate(schema(), envelope).isEmpty());
    }

    @Test
    void rejectsDiagnosticWithoutRequiredMessage() throws Exception {
        Map<String, Object> envelope = validEnvelope();
        envelope.put("diagnostics", List.of(Map.of(
                "code", "INVALID_ARGUMENT",
                "severity", "error")));

        assertFalse(MinimalJsonSchemaValidator.validate(schema(), envelope).isEmpty());
    }

    @Test
    void rejectsInvalidBudgetMetadataType() throws Exception {
        Map<String, Object> envelope = validEnvelope();
        envelope.put("meta", Map.of(
                "budget", Map.of("profile", "standard", "truncated", "false"),
                "confidence", Map.of("level", "exact", "reasons", List.of()),
                "partial", Map.of("reasons", List.of())));

        assertFalse(MinimalJsonSchemaValidator.validate(schema(), envelope).isEmpty());
    }

    @Test
    void rejectsInvalidConfidenceReasonsType() throws Exception {
        Map<String, Object> envelope = validEnvelope();
        envelope.put("meta", Map.of(
                "budget", Map.of("profile", "standard", "truncated", false),
                "confidence", Map.of("level", "exact", "reasons", "none"),
                "partial", Map.of("reasons", List.of())));

        assertFalse(MinimalJsonSchemaValidator.validate(schema(), envelope).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schema() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/json-schema/v1/envelope.schema.json")) {
            assertNotNull(input);
            return (Map<String, Object>) JsonReader.parse(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private Map<String, Object> validEnvelope() {
        return new java.util.LinkedHashMap<>(Map.of(
                "schema", "urn:jsrc:output:find:1",
                "protocolVersion", 1,
                "command", "find",
                "status", "error",
                "data", Map.of(),
                "diagnostics", List.of(Map.of(
                        "code", "INVALID_ARGUMENT",
                        "severity", "error",
                        "message", "Invalid argument")),
                "meta", Map.of(
                        "budget", Map.of("profile", "standard", "truncated", false),
                        "confidence", Map.of("level", "exact", "reasons", List.of()),
                        "partial", Map.of("reasons", List.of()))));
    }
}
