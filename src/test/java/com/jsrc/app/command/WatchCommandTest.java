package com.jsrc.app.command;

import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test WatchCommand (daemon mode) using threads instead of processes.
 * Sends a query via stdin pipe, reads response from stdout.
 */
class WatchCommandTest {

    @Test
    void watchRespondsToOverviewQuery(@TempDir Path tempDir) throws Exception {
        // Create fixture
        Files.writeString(tempDir.resolve("App.java"), """
                package demo;
                public class App {
                    public void run() {}
                }
                """);

        var parser = new HybridJavaParser();
        var files = List.of(tempDir.resolve("App.java"));
        var formatter = OutputFormatter.create(true, false, null);
        var ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);

        // WatchCommand writes to System.out directly — must redirect both stdin and stdout
        var originalIn = System.in;
        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();

        // Send overview query then quit
        System.setIn(new ByteArrayInputStream("{\"command\":\"overview\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(captured, true));

        try {
            var watch = new WatchCommand();
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));

            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            }
            executor.shutdownNow();

            String output = captured.toString();
            assertTrue(output.contains("totalFiles") || output.contains("totalClasses"),
                    "Watch should respond to overview query. Got: " + output);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }
}
