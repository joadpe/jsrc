package com.jsrc.app.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for picocli-based CLI entry point.
 */
class PicocliIntegrationTest {

    @Test
    void helpShowsSubcommands() {
        var out = new ByteArrayOutputStream();
        var cmd = new CommandLine(new JsrcCommand());
        cmd.setOut(new java.io.PrintWriter(out, true));
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("overview"), "Should list overview subcommand");
        assertTrue(output.contains("help"), "Should list help subcommand");
    }

    @Test
    void versionPrintsVersion() {
        var out = new ByteArrayOutputStream();
        var cmd = new CommandLine(new JsrcCommand());
        cmd.setOut(new java.io.PrintWriter(out, true));
        int exitCode = cmd.execute("--version");
        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("2.1.0"));
    }

    @Test
    void overviewSubcommandProducesOutput(@TempDir Path tempDir) throws Exception {
        // Create a minimal Java file
        Path javaFile = tempDir.resolve("Hello.java");
        Files.writeString(javaFile, """
                package demo;
                public class Hello {
                    public void greet() {}
                }
                """);

        // Capture System.out (commands write to System.out, not picocli's writer)
        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            var cmd = new CommandLine(new JsrcCommand());
            int exitCode = cmd.execute("--dir", tempDir.toString(), "--json", "overview");
            String output = captured.toString();
            assertTrue(output.contains("totalFiles") || output.contains("totalClasses"),
                    "Overview should produce JSON output, got: " + output);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void noSubcommandShowsUsage() {
        // When no subcommand is given, JsrcCommand.run() calls CommandLine.usage()
        // which writes to System.out
        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            var cmd = new CommandLine(new JsrcCommand());
            int exitCode = cmd.execute();
            assertEquals(0, exitCode);
            String output = captured.toString();
            assertTrue(output.contains("jsrc") || output.contains("overview") || output.contains("Usage"),
                    "No subcommand should show usage, got: " + output);
        } finally {
            System.setOut(originalOut);
        }
    }
}
