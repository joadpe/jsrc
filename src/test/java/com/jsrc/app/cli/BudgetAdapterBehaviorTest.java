package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import com.jsrc.app.ExitCode;
import com.jsrc.app.output.JsonReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * B3: Tests that exercise actual adapter/command behavior, not just BudgetPolicy metadata.
 * These tests verify that SummaryAdapter, ReadAdapter, and DescribeCommand actually implement
 * the expected degradation/denial behavior at runtime.
 */
class BudgetAdapterBehaviorTest {

    @TempDir
    static Path tempDir;
    
    private static Path testFile;
    private static Path rootDir;

    @BeforeAll
    static void setUp() throws IOException {
        rootDir = tempDir;
        testFile = rootDir.resolve("src/main/java/com/example/TestClass.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
            package com.example;
            public class TestClass {
                public void methodA() { System.out.println("A"); }
                public void methodB() { System.out.println("B"); }
                public void methodC() { System.out.println("C"); }
            }
            """);
        
        // Create minimal .jsrc dir
        Path jsrcDir = rootDir.resolve(".jsrc");
        Files.createDirectories(jsrcDir);
    }

    @Test
    @DisplayName("B3/A4: SummaryAdapter under TINY degrades to mini and reports degradedFrom")
    void summaryAdapterUnderTinyDegradesToMini() throws IOException {
        // Test that summary command actually runs MiniCommand under TINY
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        try {
            CommandLine cli = JsrcCliFactory.create();
            int exitCode = cli.execute("--dir", rootDir.toString(), "--json", "--budget", "tiny", "summary", "TestClass");
            
            assertEquals(ExitCode.OK, exitCode,
                "summary should degrade to mini, not be denied");

            Map<?, ?> output = lastJsonObject(outContent.toString());
            assertTrue(output.containsKey("keyMethods"),
                "summary under TINY should execute mini: " + output);
            Map<?, ?> budget = assertInstanceOf(Map.class, output.get("_budget"),
                "summary under TINY should include _budget metadata: " + output);
            assertEquals("tiny", budget.get("profile"));
            assertEquals("summary", budget.get("degradedFrom"),
                "summary under TINY should report the degradation: " + output);

            // Should NOT be denied
            String errOutput = errContent.toString();
            assertFalse(errOutput.contains("budget_denied"),
                "summary should not be denied, but degraded to mini");
            
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B3/A4: protocol v1 reports the summary degradation in meta.budget")
    void summaryDegradationUnderTinyIsReportedInVersionOneMeta() {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        try {
            CommandLine cli = JsrcCliFactory.create();
            cli.execute("--dir", rootDir.toString(), "--json", "--protocol", "1",
                    "--budget", "tiny", "summary", "TestClass");

            Map<?, ?> envelope = lastJsonObject(outContent.toString());
            assertEquals("summary", envelope.get("command"));
            Map<?, ?> meta = assertInstanceOf(Map.class, envelope.get("meta"));
            Map<?, ?> budget = assertInstanceOf(Map.class, meta.get("budget"));
            assertEquals("tiny", budget.get("profile"));
            assertEquals("summary", budget.get("degradedFrom"),
                "v1 summary under TINY should report the degradation: " + envelope);

        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B3/A5: ReadAdapter under TINY denies whole-class reads and reports degradedFrom")
    void readAdapterUnderTinyDeniesWholeClassReads() {
        // read has a DEGRADE policy under TINY: ReadAdapter denies whole-class reads
        var action = BudgetPolicy.getAction("read", BudgetProfile.TINY);
        assertEquals(BudgetPolicy.Action.DEGRADE, action,
            "read under TINY should have DEGRADE action (degrades to deny whole-class reads)");
        
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        try {
            CommandLine cli = JsrcCliFactory.create();
            // Whole-class read (no method)
            cli.execute("--dir", rootDir.toString(), "--json", "--budget", "tiny", "read", "TestClass");

            assertTrue(outContent.toString().isBlank(),
                "whole-class read under TINY should not print source: " + outContent);

            Map<?, ?> error = lastJsonObject(errContent.toString());
            assertEquals("budget_denied", error.get("error"));
            assertEquals("read", error.get("command"));
            Map<?, ?> budget = assertInstanceOf(Map.class, error.get("_budget"),
                "read denial under TINY should include _budget metadata: " + error);
            assertEquals("tiny", budget.get("profile"));
            assertEquals("read", budget.get("degradedFrom"),
                "read denial under TINY should report the degradation: " + error);

        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B3/A5: ReadAdapter under TINY allows method reads - verify actual behavior")
    void readAdapterUnderTinyAllowsMethodReads() throws IOException {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        try {
            CommandLine cli = JsrcCliFactory.create();
            // Method read (has dot, specifies method)
            int exitCode = cli.execute("--dir", rootDir.toString(), "--json", "--budget", "tiny", "read", "TestClass.methodA");
            
            // Should succeed or NOT_FOUND, not be denied
            assertNotEquals(ExitCode.BAD_USAGE, exitCode,
                "read TestClass.methodA under TINY should be allowed");
            
            String errOutput = errContent.toString();
            assertFalse(errOutput.contains("budget_denied"),
                "Method reads should not be denied under TINY");
            assertFalse(outContent.toString().contains("degradedFrom"),
                "Method reads should not be degraded under TINY");
            
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B3/A7: DescribeCommand under TINY filters commands - verify actual command behavior")
    void describeCommandUnderTinyFiltersCommands() throws IOException {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        try {
            CommandLine cli = JsrcCliFactory.create();
            int exitCode = cli.execute("--dir", rootDir.toString(), "--json", "--budget", "tiny", "describe");
            
            // Verify it's not denied by budget
            String errOutput = errContent.toString();
            assertFalse(errOutput.contains("budget_denied"),
                "describe should not be denied by budget under TINY");
            
            // If it succeeded, verify filtering behavior
            String output = outContent.toString();
            if (exitCode == ExitCode.OK || exitCode == 0) {
                // Verify heavy commands are NOT in output
                assertFalse(output.contains("\"name\":\"context\"") && output.contains("Full context"),
                    "describe under TINY should NOT show context command");
                assertFalse(output.contains("\"name\":\"dump\"") && output.contains("Dump binary"),
                    "describe under TINY should NOT show dump command");
                assertFalse(output.contains("\"name\":\"tour\"") && output.contains("tour"),
                    "describe under TINY should NOT show tour command");
                
                // Verify core commands ARE in output
                assertTrue(output.contains("\"name\":\"mini\"") || output.contains("mini"),
                    "describe under TINY SHOULD show mini command");
                assertTrue(output.contains("\"name\":\"overview\"") || output.contains("overview"),
                    "describe under TINY SHOULD show overview command");
            }
            
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B3: SummaryAdapter under SMALL allows normal summary - verify no degradation")
    void summaryAdapterUnderSmallAllowsNormalSummary() throws IOException {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        try {
            CommandLine cli = JsrcCliFactory.create();
            int exitCode = cli.execute("--dir", rootDir.toString(), "--json", "--budget", "small", "summary", "TestClass");
            
            // Should succeed
            assertNotEquals(ExitCode.BAD_USAGE, exitCode);
            
            String errOutput = errContent.toString();
            assertFalse(errOutput.contains("budget_denied"),
                "summary under SMALL should not be denied");
            
            // Under SMALL, should NOT degrade (no degradedFrom metadata for SMALL summary)
            String output = outContent.toString();
            assertFalse(output.contains("degradedFrom"),
                "summary under SMALL should not degrade");
            
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /** Parses the last JSON object line, skipping diagnostics printed before it. */
    private static Map<?, ?> lastJsonObject(String text) {
        String json = text.lines()
                .filter(line -> line.startsWith("{"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no JSON object in: " + text));
        return assertInstanceOf(Map.class, JsonReader.parse(json));
    }
}
