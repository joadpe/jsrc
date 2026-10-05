package com.jsrc.app.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract test for issue #17: jsrc index must exit 0 on success.
 * 
 * Tests the FULL picocli path (not just IndexCommand.execute in isolation),
 * because the bug is in PicocliAdapter's result→exit mapping.
 */
class IndexExitCodeContractTest {

    @Test
    void relativeAndAbsoluteRootIndexSameFixture(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("Demo.java"), "class Demo { void run() {} }\n");

        runIndex(project, "--dir", ".", "index");
        var relative = com.jsrc.app.index.CodebaseIndex.loadPublished(project);
        runIndex(project, "--dir", project.toAbsolutePath().toString(), "index");
        var absolute = com.jsrc.app.index.CodebaseIndex.loadPublished(project);

        assertEquals(1, relative.size());
        assertEquals("Demo.java", relative.getFirst().path());
        assertEquals(relative.getFirst().path(), absolute.getFirst().path());
        assertEquals(relative.getFirst().classes(), absolute.getFirst().classes());
    }

    @Test
    void indexFromCurrentDirectoryPublishesRelativePaths(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Demo.java"), "class Demo {}\n");

        runIndex(tempDir, "index");
        assertTrue(Files.isRegularFile(tempDir.resolve(".jsrc/current")));
        assertEquals("Demo.java", com.jsrc.app.index.CodebaseIndex.loadPublished(tempDir).get(0).path());
    }

    @Test
    void indexAcceptsRelativeDirectory(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(project.resolve("Demo.java"), "class Demo {}\n");

        runIndex(tempDir, "--dir", "project", "index");

        assertTrue(Files.isRegularFile(project.resolve(".jsrc/current")));
        assertEquals("Demo.java", com.jsrc.app.index.CodebaseIndex.loadPublished(project).get(0).path());
    }

    @Test
    void unchangedSourceIsCachedFromCurrentDirectory(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Demo.java"), "class Demo {}\n");

        runIndex(tempDir, "index");
        String output = runIndex(tempDir, "index");

        assertTrue(output.contains("Done. Indexed 1 files (0 re-indexed, 1 cached)."), output);
    }

    private static String runIndex(Path workingDirectory, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(
                java, "--enable-native-access=ALL-UNNAMED", "-cp", classpath, "com.jsrc.app.App"));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Index subprocess timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

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
            var cmd = JsrcCliFactory.create();
            int exitCode = cmd.execute("--dir", tempDir.toString(), "index");

            String stderr = capturedErr.toString();
            
            // Acceptance criterion 1: exit code must be 0
            assertEquals(0, exitCode, 
                    "index must exit 0 on success (issue #17). stderr: " + stderr);

            // Acceptance criterion 1: stderr still shows "Done. Indexed"
            assertTrue(stderr.contains("Done. Indexed"), 
                    "index should print 'Done. Indexed' to stderr");

            // Acceptance criterion 1: a complete generation is published.
            Path manifest = tempDir.resolve(".jsrc/current");
            assertTrue(Files.isRegularFile(manifest));
            assertTrue(Files.isRegularFile(
                    com.jsrc.app.index.CodebaseIndex.currentBinary(tempDir)));

        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void unchangedCallFreeSourceIsCached(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Demo.java"), """
                package demo;
                public class Demo {
                    public void run() {}
                }
                """);
        assertEquals(0, JsrcCliFactory.create().execute("--dir", tempDir.toString(), "index"));

        var originalErr = System.err;
        var capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr));
        int exitCode;
        try {
            exitCode = JsrcCliFactory.create().execute("--dir", tempDir.toString(), "index");
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(0, exitCode);
        assertTrue(capturedErr.toString().contains(
                "Done. Indexed 1 files (0 re-indexed, 1 cached)."),
                capturedErr.toString());
    }
}
