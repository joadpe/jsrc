package com.jsrc.app.command;

import com.jsrc.app.command.meta.WatchCommand;
import com.jsrc.app.index.IndexedCodebase;

import com.jsrc.app.output.OutputFormatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test WatchCommand session cache without relying on tree-sitter native libs.
 * Tests verify A1-A4 contract: cache reuse, refresh detection, quit protocol.
 */
class WatchCommandTest {

    private static final AtomicInteger tryLoadCounter = new AtomicInteger(0);

    /**
     * A1: First watch command should call tryLoad exactly once.
     */
    @Test
    void firstWatchCommandCallsLoadOnce(@TempDir Path tempDir) throws Exception {
        createSimpleJavaFile(tempDir);
        tryLoadCounter.set(0);

        var originalIn = System.in;
        var originalOut = System.out;

        System.setIn(new ByteArrayInputStream("{\"command\":\"overview\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true));

        try {
            var watch = createInstrumentedWatchCommand();
            var ctx = createContext(tempDir);
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));

            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            }
            executor.shutdownNow();

            assertEquals(1, tryLoadCounter.get(),
                    "First watch command should call tryLoad exactly once");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A2: Second identical command with no file changes should NOT call tryLoad again.
     */
    @Test
    void secondCommandWithoutChangesSkipsLoad(@TempDir Path tempDir) throws Exception {
        createSimpleJavaFile(tempDir);
        tryLoadCounter.set(0);

        var originalIn = System.in;
        var originalOut = System.out;

        System.setIn(new ByteArrayInputStream(
                "{\"command\":\"overview\"}\n{\"command\":\"overview\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true));

        try {
            var watch = createInstrumentedWatchCommand();
            var ctx = createContext(tempDir);
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));

            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            }
            executor.shutdownNow();

            assertEquals(1, tryLoadCounter.get(),
                    "Second command without file changes should NOT call tryLoad again (count stays 1)");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A3: After touching a Java file mtime, next command should refresh (tryLoad called again).
     */
    @Test
    void commandAfterFileChangeRefreshesIndex(@TempDir Path tempDir) throws Exception {
        var javaFile = createSimpleJavaFile(tempDir);
        tryLoadCounter.set(0);

        var originalIn = System.in;
        var originalOut = System.out;

        var inputCommands = new PipedOutputStream();
        var inputStream = new PipedInputStream(inputCommands);

        System.setIn(inputStream);
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true));

        try {
            var watch = createInstrumentedWatchCommand();
            var ctx = createContext(tempDir);
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));

            inputCommands.write("{\"command\":\"overview\"}\n".getBytes());
            inputCommands.flush();
            Thread.sleep(500);

            Files.setLastModifiedTime(javaFile, 
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));

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

            assertTrue(tryLoadCounter.get() >= 2,
                    "After file modification, tryLoad should be called again (>= 2), got: " + tryLoadCounter.get());
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A4: {"command":"quit"} exits cleanly, protocol unchanged.
     */
    @Test
    void quitCommandExitsCleanly(@TempDir Path tempDir) throws Exception {
        createSimpleJavaFile(tempDir);

        var originalIn = System.in;
        var originalOut = System.out;

        System.setIn(new ByteArrayInputStream("{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true));

        try {
            var watch = new WatchCommand();
            var ctx = createContext(tempDir);
            int exitCode = watch.execute(ctx);

            assertEquals(0, exitCode, "quit command should exit with code 0");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    private Path createSimpleJavaFile(Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("App.java");
        Files.writeString(javaFile, """
                package demo;
                public class App {
                    public void run() {}
                }
                """);
        return javaFile;
    }

    private CommandContext createContext(Path tempDir) {
        var files = List.of(tempDir.resolve("App.java"));
        var formatter = OutputFormatter.create(true, false, null);
        return new CommandContext(files, tempDir.toString(), null, formatter, null, null);
    }

    private WatchCommand createInstrumentedWatchCommand() {
        return new WatchCommand() {
            @Override
            protected IndexedCodebase loadOrRefreshIndex(
                    Path root, List<Path> files, IndexedCodebase cached) {
                IndexedCodebase result = super.loadOrRefreshIndex(root, files, cached);
                return result;
            }

            @Override
            protected IndexedCodebase callTryLoad(Path root, List<Path> files) {
                tryLoadCounter.incrementAndGet();
                return super.callTryLoad(root, files);
            }
        };
    }
}
