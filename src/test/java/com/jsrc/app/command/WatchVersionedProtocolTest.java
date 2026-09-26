package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.command.meta.WatchCommand;
import com.jsrc.app.config.ArchitectureConfig;
import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.output.JsonProtocol;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.project.ProjectModelDetector;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchVersionedProtocolTest {

    @Test
    void emitsOneCompleteVersionOneEnvelopePerResponse(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("Example.java");
        Files.writeString(source, "public class Example {}");

        var originalIn = System.in;
        var originalOut = System.out;
        var bytes = new ByteArrayOutputStream();
        var bufferedOut = new PrintStream(
                new java.io.BufferedOutputStream(bytes), false,
                java.nio.charset.StandardCharsets.UTF_8);
        System.setIn(new ByteArrayInputStream(
                "{\"command\":\"overview\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            BudgetContext budget = new BudgetContext(
                    BudgetProfile.STANDARD, null, null, false, false, null);
            OutputFormatter formatter = OutputFormatter.create(
                    true, false, null, bufferedOut, budget, JsonProtocol.V1, "watch");
            CommandContext context = new CommandContext(
                    List.of(source), tempDir.toString(), null, formatter, null,
                    new HybridJavaParser());

            assertEquals(0, new WatchCommand().execute(context));

            String[] lines = bytes.toString().lines().filter(line -> !line.isBlank()).toArray(String[]::new);
            assertEquals(1, lines.length);
            Map<?, ?> envelope = assertInstanceOf(Map.class, JsonReader.parse(lines[0]));
            assertEquals(1L, envelope.get("protocolVersion"));
            assertEquals("watch", envelope.get("command"));
            assertTrue(envelope.containsKey("data"));
        } finally {
            bufferedOut.close();
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    @Test
    void preservesProjectModelAndModeledFilesInOverview(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'watch-model'");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");
        Path source = tempDir.resolve("src/main/java/demo/Included.java");
        Path configured = tempDir.resolve("custom-src/demo/Configured.java");
        Path excludedByConfig = tempDir.resolve("src/main/java/demo/Excluded.java");
        Path excluded = tempDir.resolve("target/arbitrary/demo/Excluded.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(configured.getParent());
        Files.createDirectories(excluded.getParent());
        Files.writeString(source, "package demo; public class Included {}");
        Files.writeString(configured, "package demo; public class Configured {}");
        Files.writeString(excludedByConfig, "package demo; public class Excluded {}");
        Files.writeString(excluded, "package demo; public class Excluded {}");
        var model = new ProjectModelDetector().detect(tempDir);
        var files = List.of(configured, source);
        var config = new ProjectConfig(
                List.of("custom-src"), List.of("**/Excluded.java"),
                "21", ArchitectureConfig.empty());

        var originalIn = System.in;
        var originalOut = System.out;
        var bytes = new ByteArrayOutputStream();
        var bufferedOut = new PrintStream(
                new java.io.BufferedOutputStream(bytes), false,
                java.nio.charset.StandardCharsets.UTF_8);
        System.setIn(new ByteArrayInputStream(
                "{\"command\":\"overview\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            BudgetContext budget = new BudgetContext(
                    BudgetProfile.STANDARD, null, null, false, false, null);
            OutputFormatter formatter = OutputFormatter.create(
                    true, false, null, bufferedOut, budget, JsonProtocol.V1, "watch");
            CommandContext context = new CommandContext(
                    files, tempDir.toString(), config, formatter, null,
                    new HybridJavaParser(), false, null, false, false,
                    budget, false, model);

            assertEquals(0, new WatchCommand().execute(context));

            String line = bytes.toString().lines()
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElseThrow();
            Map<?, ?> envelope = assertInstanceOf(Map.class, JsonReader.parse(line));
            Map<?, ?> data = assertInstanceOf(Map.class, envelope.get("data"));
            Map<?, ?> overview = assertInstanceOf(Map.class, data.get("result"));
            Map<?, ?> project = assertInstanceOf(Map.class, overview.get("project"));
            assertEquals("gradle", project.get("buildSystem"));
            assertEquals(2L, overview.get("totalFiles"));
            assertEquals(List.of("demo"), overview.get("packages"));
            List<?> topClasses = assertInstanceOf(List.class, overview.get("topClasses"));
            assertTrue(topClasses.stream().anyMatch(value -> value.toString().startsWith("Configured ")));
            assertFalse(topClasses.stream().anyMatch(value -> value.toString().startsWith("Excluded ")));
        } finally {
            bufferedOut.close();
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }
}
