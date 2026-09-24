package com.jsrc.app.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.cli.CommandDescriptor;
import com.jsrc.app.cli.CommandOutputType;
import com.jsrc.app.cli.DefaultCommandRegistry;
import com.jsrc.app.output.DiagnosticCode;
import com.jsrc.app.output.JsonProtocol;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.output.VersionedJsonPrintStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandJsonContractCoverageTest {

    private static final List<String> REQUIRED_KEYS = List.of(
            "schema", "protocolVersion", "command", "status",
            "data", "diagnostics", "meta");

    @Test
    void everyJsonCommandHasVersionOneSuccessEmptyErrorAndPartialContracts() {
        List<CommandDescriptor> commands = DefaultCommandRegistry.create().commands().stream()
                .filter(command -> command.outputType() != CommandOutputType.NONE)
                .toList();

        assertTrue(!commands.isEmpty());
        for (CommandDescriptor command : commands) {
            assertContract(command, samplePayload(command), "ok", Integer.MAX_VALUE);
            assertContract(command, emptyPayload(command), "empty", Integer.MAX_VALUE);
            assertErrorContract(command);
            assertContract(command, List.of(Map.of("value", "x".repeat(1000))), "partial", 512);
        }
    }

    @Test
    void versionOneSchemaDeclaresAllMandatoryEnvelopeFields() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/json-schema/v1/envelope.schema.json")) {
            assertNotNull(input, "Versioned schema resource");
            Map<?, ?> schema = assertInstanceOf(
                    Map.class,
                    JsonReader.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            List<?> required = assertInstanceOf(List.class, schema.get("required"));
            assertEquals(REQUIRED_KEYS, required);
            assertEquals("urn:jsrc:output-envelope:1", schema.get("$id"));
        }
    }

    private void assertContract(
            CommandDescriptor command, Object payload, String status, int maxBytes) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BudgetContext budget = new BudgetContext(
                BudgetProfile.STANDARD, null, maxBytes, false, false, null);
        OutputFormatter formatter = OutputFormatter.create(
                true, false, null, new PrintStream(bytes), budget,
                JsonProtocol.V1, command.name());

        formatter.printResult(payload);

        Map<?, ?> envelope = parseEnvelope(bytes);
        assertRequiredEnvelope(command, envelope);
        assertEquals(status, envelope.get("status"), command.name());
    }

    private void assertErrorContract(CommandDescriptor command) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BudgetContext budget = new BudgetContext(
                BudgetProfile.STANDARD, null, null, false, false, null);
        new VersionedJsonPrintStream(new PrintStream(bytes), command.name(), budget)
                .printError(DiagnosticCode.INVALID_ARGUMENT, "Invalid argument");

        Map<?, ?> envelope = parseEnvelope(bytes);
        assertRequiredEnvelope(command, envelope);
        assertEquals("error", envelope.get("status"), command.name());
        assertEquals(null, envelope.get("data"), command.name());
    }

    private void assertRequiredEnvelope(CommandDescriptor command, Map<?, ?> envelope) {
        for (String key : REQUIRED_KEYS) {
            assertTrue(envelope.containsKey(key), command.name() + " missing " + key);
        }
        assertEquals(command.schemaId(), envelope.get("schema"));
        assertEquals(1L, envelope.get("protocolVersion"));
        assertEquals(command.name(), envelope.get("command"));
    }

    private Map<?, ?> parseEnvelope(ByteArrayOutputStream bytes) {
        return assertInstanceOf(
                Map.class,
                JsonReader.parse(bytes.toString(StandardCharsets.UTF_8).trim()));
    }

    private Object samplePayload(CommandDescriptor command) {
        return command.outputType() == CommandOutputType.ARRAY
                ? List.of(Map.of("value", command.name()))
                : Map.of("value", command.name());
    }

    private Object emptyPayload(CommandDescriptor command) {
        return command.outputType() == CommandOutputType.ARRAY ? List.of() : Map.of();
    }
}
