package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PicocliIntegrationTest {

    @Test
    void helpShowsSubcommands() {
        var out = new ByteArrayOutputStream();
        var cmd = JsrcCliFactory.create();
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
        var cmd = JsrcCliFactory.create();
        cmd.setOut(new java.io.PrintWriter(out, true));
        int exitCode = cmd.execute("--version");
        assertEquals(0, exitCode);
        assertEquals("jsrc 2.5.0" + System.lineSeparator(), out.toString());
    }

    @Test
    void overviewSubcommandProducesOutput(@TempDir Path tempDir) throws Exception {
        Path javaFile = tempDir.resolve("Hello.java");
        Files.writeString(javaFile, """
                package demo;
                public class Hello {
                    public void greet() {}
                }
                """);

        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            var cmd = JsrcCliFactory.create();
            int exitCode = cmd.execute("--dir", tempDir.toString(), "--json", "overview");
            assertEquals(0, exitCode);
            String output = captured.toString();
            assertTrue(output.contains("totalFiles") || output.contains("totalClasses"),
                    "Overview should produce JSON output, got: " + output);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void overviewAcceptsRelativeSourceRoot(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Hello.java"), "public class Hello {}");
        Path relativeRoot = Path.of("").toAbsolutePath().relativize(tempDir.toAbsolutePath());

        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            int exitCode = JsrcCliFactory.create().execute(
                    "--dir", relativeRoot.toString(), "--json", "overview");

            assertEquals(0, exitCode);
            assertTrue(captured.toString().contains("totalFiles"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void sourceSetOptionFiltersFilesBeforeAnalysis(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>source-sets</artifactId>
                </project>
                """);
        writeJava(tempDir.resolve("src/main/java/Main.java"), "class Main {}");
        writeJava(tempDir.resolve("src/test/java/MainTest.java"), "class MainTest {}");
        writeJava(
                tempDir.resolve("src/testFixtures/java/Fixture.java"),
                "class Fixture {}");

        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            int exitCode = JsrcCliFactory.create().execute(
                    "--dir", tempDir.toString(), "--json",
                    "--source-set", "main", "overview");

            assertEquals(0, exitCode);
            java.util.Map<?, ?> output = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class,
                    com.jsrc.app.output.JsonReader.parse(captured.toString().trim()));
            assertEquals(1L, output.get("totalFiles"));
            java.util.Map<?, ?> sourceSets = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class, output.get("sourceSets"));
            assertEquals(1L, sourceSets.get("main"));
            assertEquals(0L, sourceSets.get("test"));
            assertEquals(0L, sourceSets.get("testFixtures"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void configuredSourceRootsAreClassifiedAsMain(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve(".jsrc.yaml"), "sourceRoots:\n  - custom/java\n");
        writeJava(tempDir.resolve("custom/java/Custom.java"), "class Custom {}");

        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            int exitCode = JsrcCliFactory.create().execute(
                    "--dir", tempDir.toString(), "--json",
                    "--source-set", "main", "overview");

            assertEquals(0, exitCode, captured.toString());
            java.util.Map<?, ?> output = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class,
                    com.jsrc.app.output.JsonReader.parse(captured.toString().trim()));
            assertEquals(1L, output.get("totalFiles"));
            java.util.Map<?, ?> sourceSets = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class, output.get("sourceSets"));
            assertEquals(1L, sourceSets.get("main"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void versionOneProtocolWrapsCommandOutput(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Hello.java"), "public class Hello {}");

        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            int exitCode = JsrcCliFactory.create().execute(
                    "--dir", tempDir.toString(), "--json", "--protocol", "1", "overview");

            assertEquals(0, exitCode);
            Object parsed = com.jsrc.app.output.JsonReader.parse(captured.toString().trim());
            java.util.Map<?, ?> envelope = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class, parsed);
            assertEquals(1L, envelope.get("protocolVersion"));
            assertEquals("overview", envelope.get("command"));
            assertTrue(envelope.containsKey("data"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void legacyRemainsTheDefaultProtocol(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Hello.java"), "public class Hello {}");

        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            int exitCode = JsrcCliFactory.create().execute(
                    "--dir", tempDir.toString(), "--json", "overview");

            assertEquals(0, exitCode);
            java.util.Map<?, ?> output = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class,
                    com.jsrc.app.output.JsonReader.parse(captured.toString().trim()));
            assertTrue(!output.containsKey("protocolVersion"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void versionOneBudgetDenialProducesStructuredError(@TempDir Path tempDir) {
        var originalErr = System.err;
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            int exitCode = JsrcCliFactory.create().execute(
                    "--dir", tempDir.toString(), "--json", "--protocol", "1",
                    "--budget", "tiny", "context", "Missing");

            assertEquals(2, exitCode);
            java.util.Map<?, ?> envelope = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class,
                    com.jsrc.app.output.JsonReader.parse(captured.toString().trim()));
            assertEquals("error", envelope.get("status"));
            assertEquals("context", envelope.get("command"));
            java.util.List<?> diagnostics = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.List.class, envelope.get("diagnostics"));
            java.util.Map<?, ?> diagnostic = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class, diagnostics.getFirst());
            assertEquals("BUDGET_DENIED", diagnostic.get("code"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void versionOneWrapsSpecificDescribeOutput(@TempDir Path tempDir) {
        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            int exitCode = JsrcCliFactory.create().execute(
                    "--dir", tempDir.toString(), "--json", "--protocol", "1",
                    "describe", "overview");

            assertEquals(1, exitCode);
            java.util.Map<?, ?> envelope = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class,
                    com.jsrc.app.output.JsonReader.parse(captured.toString().trim()));
            assertEquals(1L, envelope.get("protocolVersion"));
            assertEquals("describe", envelope.get("command"));
            java.util.Map<?, ?> data = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class, envelope.get("data"));
            assertEquals("urn:jsrc:output:overview:1", data.get("schema"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void versionOneWrapsDumpOutput(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Hello.java"), "public class Hello {}");
        JsrcCliFactory.create().execute("--dir", tempDir.toString(), "index");

        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            int exitCode = JsrcCliFactory.create().execute(
                    "--dir", tempDir.toString(), "--json", "--protocol", "1", "dump");

            assertEquals(0, exitCode);
            java.util.Map<?, ?> envelope = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class,
                    com.jsrc.app.output.JsonReader.parse(captured.toString().trim()));
            assertEquals(1L, envelope.get("protocolVersion"));
            assertEquals("dump", envelope.get("command"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void invalidProtocolProducesVersionOneParameterError() {
        assertParameterError(executeCapturingError(
                "--json", "--protocol", "2", "overview"));
    }

    @Test
    void unknownOptionProducesVersionOneParameterError() {
        assertParameterError(executeCapturingError(
                "--json", "--protocol", "1", "--unknown-option", "overview"));
    }

    @Test
    void unknownCommandProducesVersionOneParameterError() {
        assertParameterError(executeCapturingError(
                "--json", "--protocol", "1", "unknown-command"));
    }

    @Test
    void missingArgumentProducesVersionOneParameterError() {
        assertParameterError(executeCapturingError(
                "--json", "--protocol", "1", "summary"));
    }

    @Test
    void tinyAndSmallBudgetsForceVersionOneParameterErrorsWithoutJsonFlag() {
        assertParameterError(executeCapturingError(
                "--budget", "tiny", "--protocol", "1", "--unknown-option"));
        assertParameterError(executeCapturingError(
                "--budget", "small", "--protocol", "1", "--unknown-option"));
    }

    @Test
    void configuredTinyBudgetForcesVersionOneParameterErrorWithoutJsonFlag(
            @TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("jsrc.yaml");
        Files.writeString(config, "budget: tiny\n");

        assertParameterError(executeCapturingError(
                "--config", config.toString(), "--protocol", "1", "--unknown-option"));
    }

    @Test
    void earlyParameterErrorsRespectMinimumByteBudget() {
        CapturedError result = executeCapturingError(
                "--json", "--protocol", "1", "--max-bytes", "320",
                "--unknown-option-with-a-long-name");

        assertParameterError(result);
        assertTrue(result.byteSize() <= 320);
    }

    @Test
    void earlyParameterErrorsTruncateLongMessagesToRequestedByteBudget() {
        String option = "--" + "unknown".repeat(200);
        CapturedError result = executeCapturingError(
                "--json", "--protocol", "1", "--max-bytes", "512", option);

        assertParameterError(result);
        assertTrue(result.byteSize() <= 512);
    }

    @Test
    void noSubcommandShowsUsage() {
        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            var cmd = JsrcCliFactory.create();
            int exitCode = cmd.execute();
            assertEquals(0, exitCode);
            String output = captured.toString();
            assertTrue(output.contains("jsrc") || output.contains("overview") || output.contains("Usage"),
                    "No subcommand should show usage, got: " + output);
        } finally {
            System.setOut(originalOut);
        }
    }

    private CapturedError executeCapturingError(String... args) {
        var originalErr = System.err;
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            int exitCode = JsrcCliFactory.create().execute(args);
            return new CapturedError(
                    exitCode, captured.toString().trim(), captured.toByteArray().length);
        } finally {
            System.setErr(originalErr);
        }
    }

    private static void writeJava(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private void assertParameterError(CapturedError result) {
        assertEquals(2, result.exitCode());
        java.util.Map<?, ?> envelope = org.junit.jupiter.api.Assertions.assertInstanceOf(
                java.util.Map.class,
                com.jsrc.app.output.JsonReader.parse(result.output()));
        assertEquals("error", envelope.get("status"));
        java.util.List<?> diagnostics = org.junit.jupiter.api.Assertions.assertInstanceOf(
                java.util.List.class, envelope.get("diagnostics"));
        java.util.Map<?, ?> diagnostic = org.junit.jupiter.api.Assertions.assertInstanceOf(
                java.util.Map.class, diagnostics.getFirst());
        assertEquals("INVALID_ARGUMENT", diagnostic.get("code"));
    }

    private record CapturedError(int exitCode, String output, int byteSize) {}
}
