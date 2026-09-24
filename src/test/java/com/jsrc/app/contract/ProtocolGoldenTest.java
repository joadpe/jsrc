package com.jsrc.app.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.output.DiagnosticCode;
import com.jsrc.app.output.JsonProtocol;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.output.VersionedJsonPrintStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtocolGoldenTest {

    @Test
    void positiveEnvelopeMatchesGolden() throws Exception {
        assertEquals(golden("success"), format("overview", Map.of("files", 3), Integer.MAX_VALUE));
    }

    @Test
    void emptyEnvelopeMatchesGolden() throws Exception {
        assertEquals(golden("empty"), format("classes", List.of(), Integer.MAX_VALUE));
    }

    @Test
    void errorEnvelopeMatchesGolden() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new VersionedJsonPrintStream(
                new PrintStream(bytes), "overview", standardBudget(Integer.MAX_VALUE))
                .printError(DiagnosticCode.INVALID_ARGUMENT, "Invalid argument");
        assertEquals(golden("error"), bytes.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void partialEnvelopeMatchesGolden() throws Exception {
        Object payload = List.of(
                Map.of("value", "x".repeat(200)),
                Map.of("value", "y".repeat(200)));
        assertEquals(golden("partial"), format("search", payload, 360));
    }

    private String format(String command, Object payload, int maxBytes) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputFormatter formatter = OutputFormatter.create(
                true, false, null, new PrintStream(bytes), standardBudget(maxBytes),
                JsonProtocol.V1, command);
        formatter.printResult(payload);
        return bytes.toString(StandardCharsets.UTF_8).trim();
    }

    private BudgetContext standardBudget(int maxBytes) {
        return new BudgetContext(
                BudgetProfile.STANDARD, null, maxBytes, false, false, null);
    }

    private String golden(String name) throws Exception {
        String path = "/contracts/json/v1/" + name + ".json";
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing golden: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
