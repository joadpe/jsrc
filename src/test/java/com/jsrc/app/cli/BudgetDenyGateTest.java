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
 * B1: Contract tests that DENY commands are actually blocked at execution.
 * Tests that ContextAdapter, DumpAdapter, TourAdapter, CallChainAdapter, MapAdapter
 * and ANY future DENY command are blocked by a central gate in PicocliAdapter.
 * 
 * These tests MUST fail on current HEAD (adapters don't check budget),
 * then pass after implementing the central gate in PicocliAdapter.call().
 */
class BudgetDenyGateTest {

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
                public void method1() {}
                public void method2() {}
            }
            """);
        
        // Create minimal .jsrc/index.bin
        Path jsrcDir = rootDir.resolve(".jsrc");
        Files.createDirectories(jsrcDir);
        // For now, tests will handle missing index gracefully
    }

    @Test
    @DisplayName("B1: context command under TINY is DENIED at gate, exits with code 2")
    void contextCommandUnderTinyIsDeniedAtGate() throws IOException {
        // Given: TINY profile via env var
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        try {
            JsrcCommand jsrcCmd = new JsrcCommand();
            jsrcCmd.rootPath = rootDir.toString();
            jsrcCmd.jsonOutput = true;
            jsrcCmd.budgetProfile = BudgetProfile.TINY;
            
            CommandLine cli = new CommandLine(jsrcCmd);
            
            // When: context command is invoked
            int exitCode = cli.execute("context", "TestClass");
            
            // Then: Should exit with BAD_USAGE (2) and output budget_denied JSON
            assertEquals(ExitCode.BAD_USAGE, exitCode, 
                "context under TINY should exit with code 2 (BAD_USAGE)");
            
            String output = errContent.toString();
            assertTrue(output.contains("budget_denied"), 
                "Should output budget_denied error type");
            assertTrue(output.contains("\"command\":\"context\""), 
                "Should specify which command was denied");
            assertTrue(output.contains("\"budget\":\"tiny\"") || output.contains("\"profile\":\"tiny\""), 
                "Should specify the budget profile");
            
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B1: call-chain command under TINY is DENIED at gate")
    void callChainCommandUnderTinyIsDeniedAtGate() throws IOException {
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        try {
            JsrcCommand jsrcCmd = new JsrcCommand();
            jsrcCmd.rootPath = rootDir.toString();
            jsrcCmd.jsonOutput = true;
            jsrcCmd.budgetProfile = BudgetProfile.TINY;
            
            CommandLine cli = new CommandLine(jsrcCmd);
            int exitCode = cli.execute("call-chain", "TestClass.method1");
            
            assertEquals(ExitCode.BAD_USAGE, exitCode);
            
            String output = errContent.toString();
            assertTrue(output.contains("budget_denied"));
            assertTrue(output.contains("\"command\":\"call-chain\""));
            
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B1: dump command under TINY is DENIED at gate")
    void dumpCommandUnderTinyIsDeniedAtGate() throws IOException {
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        try {
            JsrcCommand jsrcCmd = new JsrcCommand();
            jsrcCmd.rootPath = rootDir.toString();
            jsrcCmd.jsonOutput = true;
            jsrcCmd.budgetProfile = BudgetProfile.TINY;
            
            CommandLine cli = new CommandLine(jsrcCmd);
            int exitCode = cli.execute("dump");
            
            assertEquals(ExitCode.BAD_USAGE, exitCode);
            
            String output = errContent.toString();
            assertTrue(output.contains("budget_denied"));
            assertTrue(output.contains("\"command\":\"dump\""));
            
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B1: tour command under TINY is DENIED at gate")
    void tourCommandUnderTinyIsDeniedAtGate() throws IOException {
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        try {
            JsrcCommand jsrcCmd = new JsrcCommand();
            jsrcCmd.rootPath = rootDir.toString();
            jsrcCmd.jsonOutput = true;
            jsrcCmd.budgetProfile = BudgetProfile.TINY;
            
            CommandLine cli = new CommandLine(jsrcCmd);
            int exitCode = cli.execute("tour", "com.example");
            
            assertEquals(ExitCode.BAD_USAGE, exitCode);
            
            String output = errContent.toString();
            assertTrue(output.contains("budget_denied"));
            assertTrue(output.contains("\"command\":\"tour\""));
            
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B1: map command under TINY is DENIED at gate")
    void mapCommandUnderTinyIsDeniedAtGate() throws IOException {
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        try {
            JsrcCommand jsrcCmd = new JsrcCommand();
            jsrcCmd.rootPath = rootDir.toString();
            jsrcCmd.jsonOutput = true;
            jsrcCmd.budgetProfile = BudgetProfile.TINY;
            
            CommandLine cli = new CommandLine(jsrcCmd);
            int exitCode = cli.execute("map", "com.example");
            
            assertEquals(ExitCode.BAD_USAGE, exitCode);
            
            String output = errContent.toString();
            assertTrue(output.contains("budget_denied"));
            assertTrue(output.contains("\"command\":\"map\""));
            
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B1: context command under SMALL is also DENIED (per spec)")
    void contextCommandUnderSmallIsDeniedAtGate() throws IOException {
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        try {
            JsrcCommand jsrcCmd = new JsrcCommand();
            jsrcCmd.rootPath = rootDir.toString();
            jsrcCmd.jsonOutput = true;
            jsrcCmd.budgetProfile = BudgetProfile.SMALL;
            
            CommandLine cli = new CommandLine(jsrcCmd);
            int exitCode = cli.execute("context", "TestClass");
            
            assertEquals(ExitCode.BAD_USAGE, exitCode);
            
            String output = errContent.toString();
            assertTrue(output.contains("budget_denied"));
            assertTrue(output.contains("\"budget\":\"small\"") || output.contains("\"profile\":\"small\""));
            
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B1: ALLOW commands like mini still work under TINY")
    void allowCommandsStillWorkUnderTiny() throws IOException {
        // Positive test: verify ALLOW commands are not blocked
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        try {
            JsrcCommand jsrcCmd = new JsrcCommand();
            jsrcCmd.rootPath = rootDir.toString();
            jsrcCmd.jsonOutput = true;
            jsrcCmd.budgetProfile = BudgetProfile.TINY;
            
            CommandLine cli = new CommandLine(jsrcCmd);
            // mini is ALLOW under TINY, should not be denied
            int exitCode = cli.execute("mini", "TestClass");
            
            // Should either succeed (exit 0 or NOT_FOUND) or fail for other reasons, 
            // but NOT exit with BAD_USAGE=2 from budget denial
            assertNotEquals(ExitCode.BAD_USAGE, exitCode,
                "mini under TINY should NOT be denied by budget gate");
            
            String errOutput = errContent.toString();
            assertFalse(errOutput.contains("budget_denied"),
                "mini should not be budget-denied");
            
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("B1: Unknown command under TINY is DENIED by default")
    void unknownCommandUnderTinyIsDeniedByDefault() throws IOException {
        // BudgetPolicy.getAction returns DENY for unknown commands under TINY
        var action = BudgetPolicy.getAction("nonexistent-command", BudgetProfile.TINY);
        assertEquals(BudgetPolicy.Action.DENY, action,
            "Unknown commands should default to DENY under TINY");
    }
}
