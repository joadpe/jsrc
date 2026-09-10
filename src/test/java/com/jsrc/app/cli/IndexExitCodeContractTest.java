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
 * Contract test for issue #17: jsrc index must exit 0 on success.
 * 
 * Tests the FULL picocli path (not just IndexCommand.execute in isolation),
 * because the bug is in PicocliAdapter's result→exit mapping.
 */
class IndexExitCodeContractTest {

    @Test
    void indexSuccessExitsZero(@TempDir Path tempDir) throws Exception {
        // Create a minimal Java file fixture (≥1 file)
        Path javaFile = tempDir.resolve("Demo.java");
        Files.writeString(javaFile, """
                package demo;
                public class Demo {
                    public void run() {}
                }
                """);

        // Capture stderr (IndexCommand writes "Done. Indexed..." to stderr)
        var originalErr = System.err;
        var capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr));

        try {
            var cmd = new CommandLine(new JsrcCommand());
            int exitCode = cmd.execute("--dir", tempDir.toString(), "index");

            String stderr = capturedErr.toString();
            
            // Acceptance criterion 1: exit code must be 0
            assertEquals(0, exitCode, 
                    "index must exit 0 on success (issue #17). stderr: " + stderr);

            // Acceptance criterion 1: stderr still shows "Done. Indexed"
            assertTrue(stderr.contains("Done. Indexed"), 
                    "index should print 'Done. Indexed' to stderr");

            // Acceptance criterion 1: .jsrc/index.bin exists
            Path indexBin = tempDir.resolve(".jsrc").resolve("index.bin");
            assertTrue(Files.exists(indexBin), 
                    ".jsrc/index.bin should exist after successful index");

        } finally {
            System.setErr(originalErr);
        }
    }
}
