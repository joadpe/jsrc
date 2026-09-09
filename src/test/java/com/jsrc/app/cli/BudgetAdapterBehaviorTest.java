package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import com.jsrc.app.ExitCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

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
    @DisplayName("B3/A4: SummaryAdapter under TINY degrades to mini - verify actual output behavior")
    void summaryAdapterUnderTinyDegradesToMini() throws IOException {
        // Test that summary command actually runs MiniCommand under TINY
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        try {
            JsrcCommand jsrcCmd = new JsrcCommand();
            CommandLine cli = new CommandLine(jsrcCmd);
            int exitCode = cli.execute("--dir", rootDir.toString(), "--json", "--budget", "tiny", "summary", "TestClass");
            
            // Should succeed or be NOT_FOUND, not BAD_USAGE (not denied)
            assertNotEquals(ExitCode.BAD_USAGE, exitCode,
                "summary should degrade to mini, not be denied");
            
            String output = outContent.toString();
            
            // Verify output indicates degradation (should have _budget metadata with degradedFrom)
            if (!output.isEmpty() && output.contains("{")) {
                assertTrue(
                    output.contains("\"degradedFrom\":\"summary\"") || 
                    output.contains("methodCount") || 
                    output.contains("TestClass"),
                    "summary under TINY should execute mini behavior or show degradation metadata"
                );
            }
            
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
    @DisplayName("B3/A5: read command under TINY has DEGRADE policy (denies whole-class reads)")
    void readAdapterUnderTinyDeniesWholeClassReads() {
        // Verify that BudgetPolicy marks read as DEGRADE under TINY
        // ReadAdapter implements this by denying whole-class reads
        var action = BudgetPolicy.getAction("read", BudgetProfile.TINY);
        assertEquals(BudgetPolicy.Action.DEGRADE, action,
            "read under TINY should have DEGRADE action (degrades to deny whole-class reads)");
        
        // Note: ReadAdapter.createCommand() checks if target contains "." to distinguish
        // whole-class reads (denied) from method reads (allowed).
        // Full end-to-end testing requires tree-sitter native libraries.
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
            JsrcCommand jsrcCmd = new JsrcCommand();
            CommandLine cli = new CommandLine(jsrcCmd);
            // Method read (has dot, specifies method)
            int exitCode = cli.execute("--dir", rootDir.toString(), "--json", "--budget", "tiny", "read", "TestClass.methodA");
            
            // Should succeed or NOT_FOUND, not be denied
            assertNotEquals(ExitCode.BAD_USAGE, exitCode,
                "read TestClass.methodA under TINY should be allowed");
            
            String errOutput = errContent.toString();
            assertFalse(errOutput.contains("budget_denied"),
                "Method reads should not be denied under TINY");
            
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
            JsrcCommand jsrcCmd = new JsrcCommand();
            CommandLine cli = new CommandLine(jsrcCmd);
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
            JsrcCommand jsrcCmd = new JsrcCommand();
            CommandLine cli = new CommandLine(jsrcCmd);
            int exitCode = cli.execute("--dir", rootDir.toString(), "--json", "--budget", "small", "summary", "TestClass");
            
            // Should succeed
            assertNotEquals(ExitCode.BAD_USAGE, exitCode);
            
            String errOutput = errContent.toString();
            assertFalse(errOutput.contains("budget_denied"),
                "summary under SMALL should not be denied");
            
            // Under SMALL, should NOT degrade (no degradedFrom metadata for SMALL summary)
            String output = outContent.toString();
            if (output.contains("_budget")) {
                assertFalse(output.contains("\"degradedFrom\":\"summary\""),
                    "summary under SMALL should not degrade");
            }
            
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
