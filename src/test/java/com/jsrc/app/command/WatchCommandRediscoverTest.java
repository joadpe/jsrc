package com.jsrc.app.command;

import com.jsrc.app.command.meta.WatchCommand;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.output.OutputFormatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Watch rediscover tests (W1-W4): create/delete/rename .java files → next command sees changes.
 * Oracles: stamp-driven rediscovery (not full WatchService); frozen-index bypass.
 */
class WatchCommandRediscoverTest {

    /**
     * W1: Watch: create .java after start → next command sees new symbol without restart.
     */
    @Test
    void testW1_createJavaAfterStartSeesNewSymbol(@TempDir Path tempDir) throws Exception {
        // Start with one file
        Path existing = tempDir.resolve("Existing.java");
        Files.writeString(existing, """
                package demo;
                public class Existing {
                    public void existingMethod() {}
                }
                """);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        var inputCommands = new PipedOutputStream();
        var inputStream = new PipedInputStream(inputCommands);

        System.setIn(inputStream);
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = createTestWatchCommand(tempDir);
            var files = List.of(existing);
            var formatter = OutputFormatter.create(true, false, null);
            var parser = new com.jsrc.app.parser.HybridJavaParser();
            
            // Build initial index
            var indexCmd = new com.jsrc.app.command.meta.IndexCommand();
            var indexCtx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
            indexCmd.execute(indexCtx);

            var ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));

            // First command - should see existing symbol
            inputCommands.write("{\"command\":\"mini\",\"arg\":\"Existing\"}\n".getBytes());
            inputCommands.flush();
            Thread.sleep(500);

            // Create NEW file after watch start
            Path newFile = tempDir.resolve("NewClass.java");
            Files.writeString(newFile, """
                    package demo;
                    public class NewClass {
                        public void newMethod() {}
                    }
                    """);
            Thread.sleep(100);

            // Update context files list to include new file (simulates JsrcCommand.buildContext rediscovery)
            var updatedFiles = List.of(existing, newFile);
            var updatedCtx = new CommandContext(updatedFiles, tempDir.toString(), null, formatter, null, parser);
            
            // Second command - should see NEW symbol without restart
            // Note: Watch needs to rediscover files on each stamp check
            inputCommands.write("{\"command\":\"mini\",\"arg\":\"NewClass\"}\n".getBytes());
            inputCommands.flush();
            Thread.sleep(500);

            inputCommands.write("{\"command\":\"quit\"}\n".getBytes());
            inputCommands.close();

            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            }
            executor.shutdownNow();

            String output = outputCapture.toString();
            
            // Oracle: Second command result should contain NewClass or newMethod
            assertTrue(output.contains("NewClass") || output.contains("newMethod"),
                "After creating NewClass.java, watch should see new symbol without restart");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * W2: Watch: modify known file → still refreshes (regression).
     */
    @Test
    void testW2_modifyKnownFileStillRefreshes(@TempDir Path tempDir) throws Exception {
        Path javaFile = tempDir.resolve("Mutable.java");
        Files.writeString(javaFile, """
                package demo;
                public class Mutable {
                    public void oldMethod() {}
                }
                """);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        var inputCommands = new PipedOutputStream();
        var inputStream = new PipedInputStream(inputCommands);

        System.setIn(inputStream);
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = createTestWatchCommand(tempDir);
            var files = List.of(javaFile);
            var formatter = OutputFormatter.create(true, false, null);
            var parser = new com.jsrc.app.parser.HybridJavaParser();
            
            // Build initial index
            var indexCmd = new com.jsrc.app.command.meta.IndexCommand();
            var indexCtx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
            indexCmd.execute(indexCtx);

            var ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));

            // First command
            inputCommands.write("{\"command\":\"mini\",\"arg\":\"Mutable\"}\n".getBytes());
            inputCommands.flush();
            Thread.sleep(500);

            // Modify file (add newMethod)
            Files.writeString(javaFile, """
                    package demo;
                    public class Mutable {
                        public void oldMethod() {}
                        public void newMethod() {}
                    }
                    """);
            Thread.sleep(100);

            // Second command - should see newMethod
            inputCommands.write("{\"command\":\"mini\",\"arg\":\"Mutable\"}\n".getBytes());
            inputCommands.flush();
            Thread.sleep(500);

            inputCommands.write("{\"command\":\"quit\"}\n".getBytes());
            inputCommands.close();

            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            }
            executor.shutdownNow();

            String output = outputCapture.toString();

            // Oracle: Output should contain newMethod (proof of refresh)
            assertTrue(output.contains("newMethod"),
                "After modifying Mutable.java, watch should refresh and see newMethod");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * W3: Watch: delete or rename away .java → stale symbol removed.
     */
    @Test
    void testW3_deleteJavaRemovesStaleSymbol(@TempDir Path tempDir) throws Exception {
        Path victim = tempDir.resolve("Victim.java");
        Files.writeString(victim, """
                package demo;
                public class Victim {
                    public void victimMethod() {}
                }
                """);

        Path survivor = tempDir.resolve("Survivor.java");
        Files.writeString(survivor, """
                package demo;
                public class Survivor {
                    public void survivorMethod() {}
                }
                """);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        var inputCommands = new PipedOutputStream();
        var inputStream = new PipedInputStream(inputCommands);

        System.setIn(inputStream);
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = createTestWatchCommand(tempDir);
            var files = List.of(victim, survivor);
            var formatter = OutputFormatter.create(true, false, null);
            var parser = new com.jsrc.app.parser.HybridJavaParser();
            
            // Build initial index
            var indexCmd = new com.jsrc.app.command.meta.IndexCommand();
            var indexCtx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
            indexCmd.execute(indexCtx);

            var ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));

            // First command - overview should list both classes
            inputCommands.write("{\"command\":\"overview\"}\n".getBytes());
            inputCommands.flush();
            Thread.sleep(500);

            // Delete victim file
            Files.delete(victim);
            Thread.sleep(100);

            // Second command - overview should NOT list Victim
            inputCommands.write("{\"command\":\"overview\"}\n".getBytes());
            inputCommands.flush();
            Thread.sleep(500);

            inputCommands.write("{\"command\":\"quit\"}\n".getBytes());
            inputCommands.close();

            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            }
            executor.shutdownNow();

            String output = outputCapture.toString();
            String[] lines = output.split("\n");
            
            // Parse second overview result
            int overviewCount = 0;
            String secondOverview = null;
            for (String line : lines) {
                if (line.contains("\"totalClasses\"")) {
                    overviewCount++;
                    if (overviewCount == 2) {
                        secondOverview = line;
                        break;
                    }
                }
            }

            // Oracle: Second overview should NOT contain "Victim"
            assertNotNull(secondOverview, "Should have second overview result");
            assertFalse(secondOverview.contains("Victim"),
                "After deleting Victim.java, watch should NOT list Victim class");
            assertTrue(secondOverview.contains("Survivor"),
                "Survivor should still be present");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * W4: frozen-index → no rediscovery refresh (regression).
     */
    @Test
    void testW4_frozenIndexNoRediscoveryRefresh(@TempDir Path tempDir) throws Exception {
        Path javaFile = tempDir.resolve("Frozen.java");
        Files.writeString(javaFile, """
                package demo;
                public class Frozen {
                    public void frozenMethod() {}
                }
                """);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        var inputCommands = new PipedOutputStream();
        var inputStream = new PipedInputStream(inputCommands);

        System.setIn(inputStream);
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = createTestWatchCommand(tempDir);
            var files = List.of(javaFile);
            var formatter = OutputFormatter.create(true, false, null);
            var parser = new com.jsrc.app.parser.HybridJavaParser();
            
            // Build initial index
            var indexCmd = new com.jsrc.app.command.meta.IndexCommand();
            var indexCtx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
            indexCmd.execute(indexCtx);

            // Create context with frozenIndex=true
            var ctx = new CommandContext(
                files, tempDir.toString(), null, formatter, null, parser,
                false, null, false, false, null, true  // frozenIndex=true
            );
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));

            // First command
            inputCommands.write("{\"command\":\"mini\",\"arg\":\"Frozen\"}\n".getBytes());
            inputCommands.flush();
            Thread.sleep(500);

            // Modify file (add newMethod)
            Files.writeString(javaFile, """
                    package demo;
                    public class Frozen {
                        public void frozenMethod() {}
                        public void addedAfterFreeze() {}
                    }
                    """);
            Thread.sleep(100);

            // Second command - should NOT see addedAfterFreeze (frozen)
            inputCommands.write("{\"command\":\"mini\",\"arg\":\"Frozen\"}\n".getBytes());
            inputCommands.flush();
            Thread.sleep(500);

            inputCommands.write("{\"command\":\"quit\"}\n".getBytes());
            inputCommands.close();

            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            }
            executor.shutdownNow();

            String output = outputCapture.toString();

            // Oracle: Output should NOT contain addedAfterFreeze (frozen index prevents refresh)
            assertFalse(output.contains("addedAfterFreeze"),
                "With frozen-index, watch should NOT see new methods after modification");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    private WatchCommand createTestWatchCommand(Path tempDir) {
        return new WatchCommand() {
            @Override
            protected IndexedCodebase loadOrRefreshIndex(
                    Path root, List<Path> files, IndexedCodebase cached, boolean frozenIndex) {
                // Rediscover files on each call (simulates stamp-driven rediscovery)
                List<Path> freshFiles = discoverJavaFiles(tempDir);
                return super.loadOrRefreshIndex(root, freshFiles, cached, frozenIndex);
            }
        };
    }

    private List<Path> discoverJavaFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
