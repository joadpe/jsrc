package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VersionedJsonProtocolTest {

    @Test
    void parsesLegacyNumericAndLatestProtocolSelectors() {
        assertEquals(JsonProtocol.LEGACY, JsonProtocol.parse("legacy"));
        assertEquals(JsonProtocol.V1, JsonProtocol.parse("1"));
        assertEquals(JsonProtocol.V1, JsonProtocol.parse("latest"));
        assertThrows(IllegalArgumentException.class, () -> JsonProtocol.parse("2"));
    }

    @Test
    void wrapsObjectPayloadInVersionOneEnvelope() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BudgetContext budget = new BudgetContext(
                BudgetProfile.STANDARD, null, null, false, false, null);

        OutputFormatter formatter = OutputFormatter.create(
                true,
                false,
                null,
                new PrintStream(bytes),
                budget,
                JsonProtocol.V1,
                "overview");
        formatter.printResult(Map.of("files", 3));

        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(bytes.toString(StandardCharsets.UTF_8).trim()));
        assertEquals("urn:jsrc:output:overview:1", envelope.get("schema"));
        assertEquals(1L, envelope.get("protocolVersion"));
        assertEquals("overview", envelope.get("command"));
        assertEquals("ok", envelope.get("status"));
        assertEquals(Map.of("files", 3L), envelope.get("data"));
        assertEquals(List.of(), envelope.get("diagnostics"));
        assertInstanceOf(Map.class, envelope.get("meta"));
    }

    @Test
    void identifiesEmptyPayloadWithoutTreatingItAsAnError() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputFormatter formatter = OutputFormatter.create(
                true,
                false,
                null,
                new PrintStream(bytes),
                new BudgetContext(BudgetProfile.STANDARD, null, null, false, false, null),
                JsonProtocol.V1,
                "classes");

        formatter.printResult(List.of());

        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(bytes.toString(StandardCharsets.UTF_8).trim()));
        assertEquals("empty", envelope.get("status"));
        assertEquals(List.of(), envelope.get("data"));
        assertEquals(List.of(), envelope.get("diagnostics"));
    }

    @Test
    void legacyProtocolPreservesTheExistingRootShape() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputFormatter formatter = OutputFormatter.create(
                true,
                false,
                null,
                new PrintStream(bytes),
                new BudgetContext(BudgetProfile.STANDARD, null, null, false, false, null),
                JsonProtocol.LEGACY,
                "classes");

        formatter.printResult(List.of(Map.of("name", "Example")));

        Object output = JsonReader.parse(bytes.toString(StandardCharsets.UTF_8).trim());
        assertInstanceOf(List.class, output);
    }

    @Test
    void truncatesPayloadStructurallyAndKeepsMandatoryEnvelopeFields() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BudgetContext budget = new BudgetContext(
                BudgetProfile.STANDARD, null, 360, false, false, null);
        OutputFormatter formatter = OutputFormatter.create(
                true,
                false,
                null,
                new PrintStream(bytes),
                budget,
                JsonProtocol.V1,
                "search");

        formatter.printResult(List.of(
                Map.of("value", "x".repeat(200)),
                Map.of("value", "y".repeat(200))));

        String json = bytes.toString(StandardCharsets.UTF_8).trim();
        Map<?, ?> envelope = assertInstanceOf(Map.class, JsonReader.parse(json));
        assertEquals("partial", envelope.get("status"));
        assertTrue(envelope.containsKey("schema"));
        assertTrue(envelope.containsKey("protocolVersion"));
        assertTrue(envelope.containsKey("diagnostics"));
        assertTrue(envelope.containsKey("meta"));
        assertFalse(((List<?>) envelope.get("diagnostics")).isEmpty());
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= 360, json);
    }

    @Test
    void rejectsByteBudgetsBelowTheMinimumEnvelopeSize() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputFormatter formatter = OutputFormatter.create(
                true,
                false,
                null,
                new PrintStream(bytes),
                new BudgetContext(BudgetProfile.STANDARD, null, 64, false, false, null),
                JsonProtocol.V1,
                "overview");

        formatter.printResult(Map.of("files", 3));

        String json = bytes.toString(StandardCharsets.UTF_8).trim();
        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(json));
        assertEquals("error", envelope.get("status"));
        List<?> diagnostics = assertInstanceOf(List.class, envelope.get("diagnostics"));
        Map<?, ?> diagnostic = assertInstanceOf(Map.class, diagnostics.getFirst());
        assertEquals("INVALID_ARGUMENT", diagnostic.get("code"));
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length
                <= VersionedJsonPrintStream.minimumEnvelopeBytes(), json);
    }

    @Test
    void truncatesLongErrorMessagesWithinSupportedByteBudget() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int maxBytes = VersionedJsonPrintStream.minimumEnvelopeBytes();
        BudgetContext budget = new BudgetContext(
                BudgetProfile.STANDARD, null, maxBytes, false, false, null);

        new VersionedJsonPrintStream(new PrintStream(bytes), "overview", budget)
                .printError(DiagnosticCode.INTERNAL_ERROR, "failure-".repeat(200));

        String json = bytes.toString(StandardCharsets.UTF_8).trim();
        Map<?, ?> envelope = assertInstanceOf(Map.class, JsonReader.parse(json));
        assertEquals("error", envelope.get("status"));
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= maxBytes, json);
        List<?> diagnostics = assertInstanceOf(List.class, envelope.get("diagnostics"));
        Map<?, ?> diagnostic = assertInstanceOf(Map.class, diagnostics.getFirst());
        assertEquals("INTERNAL_ERROR", diagnostic.get("code"));
    }

    @Test
    void marksAmbiguousResultsAsPartialWithStableDiagnostic() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputFormatter formatter = OutputFormatter.create(
                true,
                false,
                null,
                new PrintStream(bytes),
                new BudgetContext(BudgetProfile.STANDARD, null, null, false, false, null),
                JsonProtocol.V1,
                "summary");

        formatter.printResult(Map.of(
                "ambiguous", true,
                "candidates", List.of("a.Service", "b.Service")));

        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(bytes.toString(StandardCharsets.UTF_8).trim()));
        assertEquals("partial", envelope.get("status"));
        List<?> diagnostics = assertInstanceOf(List.class, envelope.get("diagnostics"));
        Map<?, ?> diagnostic = assertInstanceOf(Map.class, diagnostics.getFirst());
        assertEquals("UNRESOLVED_SYMBOL", diagnostic.get("code"));
    }
}
