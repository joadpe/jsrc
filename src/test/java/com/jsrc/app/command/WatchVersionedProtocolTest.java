package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.command.meta.WatchCommand;
import com.jsrc.app.output.JsonProtocol;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;
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
}
