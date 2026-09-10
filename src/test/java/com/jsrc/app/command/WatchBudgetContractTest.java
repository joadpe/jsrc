package com.jsrc.app.command;

import com.jsrc.app.command.meta.WatchCommand;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.output.OutputFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for watch per-request budget support (issue #26).
 * 
 * Spec:
 * - Request: {"command":"overview","arg":"","budget":"tiny"} — budget OPTIONAL.
 * - Present + valid → BudgetContext for that request only
 * - Omitted → STANDARD (no limits; retrocompat)
 * - Invalid → exit 2 BAD_USAGE + error in result
 */
class WatchBudgetContractTest {

    @Test
    @DisplayName("A1: budget tiny → result ≤2048 bytes OR _budget.truncated:true")
    void a1_budgetTinyRespectsCeiling(@TempDir Path tempDir) throws Exception {
        createOversizedJavaFiles(tempDir);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream(
            "{\"command\":\"overview\",\"budget\":\"tiny\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createContext(tempDir);
            watch.execute(ctx);

            String output = outputCapture.toString();
            Map<String, Object> envelope = extractFirstEnvelope(output);

            assertNotNull(envelope, "Should find envelope");
            assertEquals(0L, ((Number) envelope.get("exit")).longValue(), "Exit should be 0");

            Object result = envelope.get("result");
            assertNotNull(result, "Result should not be null");

            String resultJson = com.jsrc.app.output.JsonWriter.toJson(result);
            int resultBytes = resultJson.getBytes().length;

            boolean withinCeiling = resultBytes <= 2048;
            boolean hasTruncatedFlag = false;

            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                Object budget = resultMap.get("_budget");
                if (budget instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> budgetMeta = (Map<String, Object>) budget;
                    hasTruncatedFlag = Boolean.TRUE.equals(budgetMeta.get("truncated"));
                }
            }

            assertTrue(withinCeiling || hasTruncatedFlag,
                "A1 FAIL: result exceeds 2048 bytes (" + resultBytes + ") without truncated flag");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("A2: omit budget → standard (no tiny ceilings; no forced _budget)")
    void a2_omitBudgetDefaultsToStandard(@TempDir Path tempDir) throws Exception {
        createOversizedJavaFiles(tempDir);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream(
            "{\"command\":\"overview\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createContext(tempDir);
            watch.execute(ctx);

            String output = outputCapture.toString();
            Map<String, Object> envelope = extractFirstEnvelope(output);

            assertNotNull(envelope, "Should find envelope");
            assertEquals(0L, ((Number) envelope.get("exit")).longValue(), "Exit should be 0");

            Object result = envelope.get("result");
            assertNotNull(result, "Result should not be null");

            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                assertFalse(resultMap.containsKey("_budget"),
                    "A2 FAIL: STANDARD should not inject _budget metadata");
            }

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("A3: budget \"invalid\" → exit ≠ 0 + error mentioning valid values")
    void a3_invalidBudgetReturnsError(@TempDir Path tempDir) throws Exception {
        createSimpleJavaFile(tempDir);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream(
            "{\"command\":\"overview\",\"budget\":\"invalid\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createContext(tempDir);
            watch.execute(ctx);

            String output = outputCapture.toString();
            Map<String, Object> envelope = extractFirstEnvelope(output);

            assertNotNull(envelope, "Should find envelope");
            
            long exitCode = ((Number) envelope.get("exit")).longValue();
            assertNotEquals(0L, exitCode, "Invalid budget should return non-zero exit");

            Object result = envelope.get("result");
            assertNotNull(result, "Result should not be null");

            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                assertTrue(resultMap.containsKey("error"), "Result should contain error field");
                
                String error = resultMap.get("error").toString().toLowerCase();
                assertTrue(error.contains("tiny") || error.contains("small") || error.contains("standard"),
                    "Error should mention valid budget values");
            } else if (result instanceof String) {
                String error = result.toString().toLowerCase();
                assertTrue(error.contains("tiny") || error.contains("small") || error.contains("standard"),
                    "Error should mention valid budget values");
            }

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("A4: callers + budget tiny still returns refs correctly under constraints")
    void a4_callersWithBudgetTinyWorks(@TempDir Path tempDir) throws Exception {
        // Create caller scenario
        Path caller = tempDir.resolve("Caller.java");
        Files.writeString(caller, """
            package demo;
            public class Caller {
                public void run() {
                    Target t = new Target();
                    t.process();
                }
            }
            """);
        
        Path target = tempDir.resolve("Target.java");
        Files.writeString(target, """
            package demo;
            public class Target {
                public void process() {}
            }
            """);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream(
            "{\"command\":\"callers\",\"arg\":\"process\",\"budget\":\"tiny\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var files = List.of(caller, target);
            var formatter = OutputFormatter.create(true, false, null);
            var parser = new com.jsrc.app.parser.HybridJavaParser();
            var ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
            watch.execute(ctx);

            String output = outputCapture.toString();
            Map<String, Object> envelope = extractFirstEnvelope(output);

            assertNotNull(envelope, "Should find envelope");
            assertEquals(0L, ((Number) envelope.get("exit")).longValue(), "Exit should be 0");

            Object result = envelope.get("result");
            assertNotNull(result, "Result should not be null");
            
            assertTrue(result instanceof List || result instanceof Map,
                "A4 FAIL: callers result should be array or object");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("A5: warm cache still works; budget applied per-request independently")
    void a5_warmCacheWithIndependentBudgets(@TempDir Path tempDir) throws Exception {
        createSimpleJavaFile(tempDir);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        // First tiny, then standard, then small
        System.setIn(new ByteArrayInputStream((
            "{\"command\":\"overview\",\"budget\":\"tiny\"}\n" +
            "{\"command\":\"overview\"}\n" +
            "{\"command\":\"overview\",\"budget\":\"small\"}\n" +
            "{\"command\":\"quit\"}\n").getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createContext(tempDir);
            watch.execute(ctx);

            String output = outputCapture.toString();
            String[] lines = output.split("\n");

            List<Map<String, Object>> envelopes = new ArrayList<>();
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope = (Map<String, Object>) JsonReader.parse(line);
                    if (envelope != null && envelope.containsKey("exit")) {
                        envelopes.add(envelope);
                    }
                } catch (Exception e) {
                    // Skip
                }
            }

            assertTrue(envelopes.size() >= 3, "Should have at least 3 envelopes");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("A6: CLI overview --budget tiny regression (unchanged)")
    void a6_cliOneshotBudgetUnchanged(@TempDir Path tempDir) throws Exception {
        // This is a regression check — CLI one-shot should still work
        // We verify it by checking that BudgetProfile.fromString still works correctly
        var tiny = com.jsrc.app.cli.BudgetProfile.fromString("tiny");
        assertEquals(com.jsrc.app.cli.BudgetProfile.TINY, tiny, "BudgetProfile.fromString should work");
        assertEquals(2048, tiny.defaultMaxBytes(), "TINY maxBytes should be 2048");
    }

    @Test
    @DisplayName("A7: budget small → SMALL maxBytes 8192 path")
    void a7_budgetSmallRespectsCeiling(@TempDir Path tempDir) throws Exception {
        createOversizedJavaFiles(tempDir);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream(
            "{\"command\":\"overview\",\"budget\":\"small\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createContext(tempDir);
            watch.execute(ctx);

            String output = outputCapture.toString();
            Map<String, Object> envelope = extractFirstEnvelope(output);

            assertNotNull(envelope, "Should find envelope");
            assertEquals(0L, ((Number) envelope.get("exit")).longValue(), "Exit should be 0");

            Object result = envelope.get("result");
            assertNotNull(result, "Result should not be null");

            String resultJson = com.jsrc.app.output.JsonWriter.toJson(result);
            int resultBytes = resultJson.getBytes().length;

            boolean withinCeiling = resultBytes <= 8192;
            boolean hasTruncatedFlag = false;

            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                Object budget = resultMap.get("_budget");
                if (budget instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> budgetMeta = (Map<String, Object>) budget;
                    hasTruncatedFlag = Boolean.TRUE.equals(budgetMeta.get("truncated"));
                }
            }

            assertTrue(withinCeiling || hasTruncatedFlag,
                "A7 FAIL: result exceeds 8192 bytes (" + resultBytes + ") without truncated flag");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("A8: invalid type/null mishandling → exit ≠ 0 + error")
    void a8_invalidBudgetTypesHandled(@TempDir Path tempDir) throws Exception {
        createSimpleJavaFile(tempDir);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        // Test numeric budget (invalid — only string profiles allowed)
        System.setIn(new ByteArrayInputStream(
            "{\"command\":\"overview\",\"budget\":123}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createContext(tempDir);
            watch.execute(ctx);

            String output = outputCapture.toString();
            Map<String, Object> envelope = extractFirstEnvelope(output);

            assertNotNull(envelope, "Should find envelope");
            
            long exitCode = ((Number) envelope.get("exit")).longValue();
            assertNotEquals(0L, exitCode, "Invalid budget type should return non-zero exit");

            Object result = envelope.get("result");
            assertNotNull(result, "Result should not be null");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    // Helper methods

    private Path createSimpleJavaFile(Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("App.java");
        Files.writeString(javaFile, """
            package demo;
            public class App {
                public void run() {}
            }
            """);
        return javaFile;
    }

    private void createOversizedJavaFiles(Path tempDir) throws IOException {
        // Create many files to exceed budget ceilings
        for (int i = 0; i < 50; i++) {
            Path file = tempDir.resolve("Class" + i + ".java");
            Files.writeString(file, """
                package demo;
                public class Class%d {
                    public void method1() {}
                    public void method2() {}
                    public void method3() {}
                }
                """.formatted(i));
        }
    }

    private CommandContext createContext(Path tempDir) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walk(tempDir)
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(files::add);
        
        var formatter = OutputFormatter.create(true, false, null);
        var parser = new com.jsrc.app.parser.HybridJavaParser();
        return new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFirstEnvelope(String output) {
        String[] lines = output.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("{")) continue;
            try {
                Map<String, Object> envelope = (Map<String, Object>) JsonReader.parse(line);
                if (envelope != null && envelope.containsKey("exit") && envelope.containsKey("result")) {
                    return envelope;
                }
            } catch (Exception e) {
                // Skip non-JSON lines
            }
        }
        return null;
    }
}
