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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test WatchCommand session cache without relying on tree-sitter native libs.
 * Tests verify A1-A5 contract: cache reuse, refresh detection, quit protocol, envelope format.
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
    void editDuringIndexRefreshIsDetectedOnNextWatchCommand(@TempDir Path tempDir)
            throws Exception {
        Path file = createSimpleJavaFile(tempDir);
        var scans = new AtomicInteger();
        var originalIn = System.in;
        var originalOut = System.out;
        System.setIn(new ByteArrayInputStream(
                "{\"command\":\"overview\"}\n{\"command\":\"overview\"}\n"
                        .getBytes()));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true));
        try {
            new WatchCommand() {
                private boolean changed;

                @Override
                protected com.jsrc.app.project.SourceCompatibilityScanner.Result scanSources(
                        List<Path> files, com.jsrc.app.project.ProjectModel model,
                        com.jsrc.app.config.ProjectConfig config) {
                    scans.incrementAndGet();
                    return super.scanSources(files, model, config);
                }

                @Override
                protected IndexedCodebase callTryLoad(
                        Path root, List<Path> files, boolean frozenIndex) {
                    IndexedCodebase loaded = super.callTryLoad(root, files, frozenIndex);
                    if (!changed) {
                        changed = true;
                        try {
                            var modified = Files.getLastModifiedTime(file);
                            Files.writeString(file,
                                    Files.readString(file).replace("run()", "act()"));
                            Files.setLastModifiedTime(file, modified);
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    }
                    return loaded;
                }
            }.execute(createContext(tempDir));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        assertEquals(2, scans.get());
    }

    @Test
    void repeatedCommandsDoNotRescanUnchangedSources(@TempDir Path tempDir)
            throws Exception {
        createSimpleJavaFile(tempDir);
        Path sources = Files.createDirectories(tempDir.resolve("src/main/java"));
        for (int index = 0; index < 50; index++) {
            Files.writeString(sources.resolve("Feature" + index + ".java"),
                    "class Feature" + index + " {}\n");
        }
        assertEquals(0, com.jsrc.app.cli.JsrcCliFactory.create().execute(
                "--dir", tempDir.toString(), "index"));
        Files.writeString(sources.resolve("Feature0.java"),
                "class Feature0 { int changed; }\n");
        var scans = new AtomicInteger();
        var originalIn = System.in;
        var originalOut = System.out;
        System.setIn(new ByteArrayInputStream(
                "{\"command\":\"overview\"}\n{\"command\":\"overview\"}\n"
                        .getBytes()));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true));
        try {
            new WatchCommand() {
                @Override
                protected com.jsrc.app.project.SourceCompatibilityScanner.Result scanSources(
                        List<Path> files, com.jsrc.app.project.ProjectModel model,
                        com.jsrc.app.config.ProjectConfig config) {
                    scans.incrementAndGet();
                    return super.scanSources(files, model, config);
                }
            }.execute(createContext(tempDir));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        assertEquals(1, scans.get());
    }

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
    void commandAfterBuildSourceLevelChangeRefreshesIndex(@TempDir Path tempDir) throws Exception {
        createSimpleJavaFile(tempDir);
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("BuildLevel.java"), "class BuildLevel {}");
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>watch</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                </project>
                """);
        tryLoadCounter.set(0);
        var originalIn = System.in;
        var originalOut = System.out;
        var commands = new PipedOutputStream();
        System.setIn(new PipedInputStream(commands));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true));
        try {
            var watch = createInstrumentedWatchCommand();
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(createContext(tempDir)));
            commands.write("{\"command\":\"overview\"}\n".getBytes());
            commands.flush();
            Thread.sleep(500);
            Files.writeString(pom, Files.readString(pom).replace(
                    "<maven.compiler.release>21</maven.compiler.release>",
                    "<maven.compiler.release>8</maven.compiler.release>"));
            commands.write("{\"command\":\"overview\"}\n".getBytes());
            commands.flush();
            Thread.sleep(500);
            commands.write("{\"command\":\"quit\"}\n".getBytes());
            commands.close();
            future.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(tryLoadCounter.get() >= 2,
                    "Changing only the declared source level must invalidate the watch cache");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

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

            var originalTime = Files.getLastModifiedTime(javaFile);
            String original = Files.readString(javaFile);
            Files.writeString(javaFile, original.replace("run()", "act()"));
            Files.setLastModifiedTime(javaFile, originalTime);

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
    void watchReportsQuarantinedSourceInsteadOfSilentEmptyResult(@TempDir Path tempDir)
            throws Exception {
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Point.java"), "record Point(int x) {}");
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>watch</artifactId><version>1</version>
                <properties><maven.compiler.release>8</maven.compiler.release></properties>
                </project>
                """);
        var originalIn = System.in;
        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setIn(new ByteArrayInputStream(
                "{\"command\":\"overview\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(captured, true));
        try {
            new WatchCommand().execute(createContext(tempDir));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        assertTrue(captured.toString().contains("SOURCE_SYNTAX_UNSUPPORTED"));
    }

    @Test
    void frozenWatchReportsInitialQuarantinedSource(@TempDir Path tempDir) throws Exception {
        Path file = createSimpleJavaFile(tempDir);
        var diagnostic = new com.jsrc.app.project.SourceDiagnostic(
                "SOURCE_SYNTAX_UNSUPPORTED", tempDir.resolve("Point.java"),
                "Source cannot be parsed as Java 8");
        var originalIn = System.in;
        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setIn(new ByteArrayInputStream(
                "{\"command\":\"overview\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(captured, true));
        try {
            var ctx = new CommandContext(
                    List.of(file), tempDir.toString(), null,
                    OutputFormatter.create(true, false, null),
                    null, new com.jsrc.app.parser.HybridJavaParser(),
                    false, null, false, false, null, true, null,
                    java.util.Set.of(), java.util.Map.of(), List.of(diagnostic));
            new WatchCommand() {
                @Override
                protected IndexedCodebase callTryLoad(
                        Path root, List<Path> files, boolean frozenIndex) {
                    return null;
                }
            }.execute(ctx);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        Map<?, ?> response = assertInstanceOf(Map.class,
                JsonReader.parse(captured.toString().trim()));
        assertEquals("partial", response.get("status"));
        assertNotEquals(0L, response.get("exit"));
        assertTrue(captured.toString().contains("SOURCE_SYNTAX_UNSUPPORTED"));
    }

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
        var parser = new com.jsrc.app.parser.HybridJavaParser();
        return new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
    }

    private WatchCommand createInstrumentedWatchCommand() {
        return new WatchCommand() {
            @Override
            protected RefreshResult loadOrRefreshIndex(
                    Path root, List<Path> files, IndexedCodebase cached, boolean frozenIndex) {
                RefreshResult result = super.loadOrRefreshIndex(root, files, cached, frozenIndex);
                return result;
            }

            @Override
            protected IndexedCodebase callTryLoad(Path root, List<Path> files, boolean frozenIndex) {
                tryLoadCounter.incrementAndGet();
                return super.callTryLoad(root, files, frozenIndex);
            }
        };
    }

    /**
     * A1: Watch command → envelope with exit + result keys (validates envelope structure).
     * Strengthened: assert exit == 0 for successful overview/mini AND result has real structure.
     */
    @Test
    void watchCommandReturnsEnvelopeWithExitAndResult(@TempDir Path tempDir) throws Exception {
        createSimpleJavaFile(tempDir);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream("{\"command\":\"overview\"}\n{\"command\":\"mini\",\"arg\":\"App\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createContext(tempDir);
            watch.execute(ctx);

            String output = outputCapture.toString();
            String[] lines = output.split("\n");
            
            int envelopesValidated = 0;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;
                
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope = (Map<String, Object>) JsonReader.parse(line);
                    if (envelope != null && envelope.containsKey("exit") && envelope.containsKey("result")) {
                        // A1 strengthened: exit == 0 for successful commands
                        Object exitObj = envelope.get("exit");
                        assertNotNull(exitObj, "exit field should not be null");
                        assertTrue(exitObj instanceof Number, "exit should be numeric");
                        long exitCode = ((Number) exitObj).longValue();
                        assertEquals(0L, exitCode, "Successful command should have exit == 0");
                        
                        // A1 strengthened: result has real structure (Map with expected fields)
                        Object result = envelope.get("result");
                        assertNotNull(result, "Result field should not be null");
                        assertTrue(result instanceof Map, "Result should be a Map with real structure");
                        
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) result;
                        assertFalse(resultMap.isEmpty(), "Result Map should not be empty");
                        
                        envelopesValidated++;
                    }
                } catch (Exception e) {
                    // Skip non-JSON lines
                }
            }
            
            assertTrue(envelopesValidated >= 2, "Should validate at least 2 envelopes (overview + mini), got: " + envelopesValidated);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A2: Watch unknown command → exit != 0 + error field.
     */
    @Test
    void watchUnknownCommandReturnsNonZeroExitWithError(@TempDir Path tempDir) throws Exception {
        createSimpleJavaFile(tempDir);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream("{\"command\":\"unknownXYZ\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createContext(tempDir);
            watch.execute(ctx);

            String output = outputCapture.toString();
            String[] lines = output.split("\n");
            
            boolean foundError = false;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;
                
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope = (Map<String, Object>) JsonReader.parse(line);
                    if (envelope != null && envelope.containsKey("exit")) {
                        long exitCode = ((Number) envelope.get("exit")).longValue();
                        if (exitCode != 0) {
                            foundError = true;
                            assertNotEquals(0L, exitCode, "Unknown command should have non-zero exit");
                            
                            Object result = envelope.get("result");
                            assertNotNull(result, "Result should not be null");
                            
                            if (result instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> resultMap = (Map<String, Object>) result;
                                assertTrue(resultMap.containsKey("error"), "Result should contain error field");
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    // Skip non-JSON lines
                }
            }
            
            assertTrue(foundError, "Should find envelope with non-zero exit and error");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A5: Warm-index cache from #20 still load-once (regression check).
     */
    @Test
    void warmIndexCacheStillLoadsOnce(@TempDir Path tempDir) throws Exception {
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
                    "Warm cache should still load index exactly once (regression from #20)");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A1 (Lazy): Watch overview command should NOT parse CallGraph.
     * Validates that lazy loading defers graph parse until needed.
     */
    @Test
    void watchOverview_noGraphParse(@TempDir Path tempDir) throws Exception {
        createSimpleJavaFile(tempDir);
        
        var originalIn = System.in;
        var originalOut = System.out;
        
        System.setIn(new ByteArrayInputStream("{\"command\":\"overview\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true));
        
        try {
            var watch = new WatchCommand();
            var ctx = createContext(tempDir);
            
            com.jsrc.app.index.BinaryIndexV2Reader.resetGraphParsedFlag();
            watch.execute(ctx);
            
            assertFalse(com.jsrc.app.index.BinaryIndexV2Reader.wasGraphParsed(),
                    "Overview command should NOT parse CallGraph (lazy loading)");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A2 (Lazy): Watch callers command DOES parse CallGraph and returns correct results.
     */
    @Test
    void watchCallers_graphParsed(@TempDir Path tempDir) throws Exception {
        // Create files with caller relationship
        Path caller = tempDir.resolve("Caller.java");
        Files.writeString(caller, """
                package demo;
                public class Caller {
                    public void run() {
                        process();
                    }
                    public void process() {}
                }
                """);
        
        var files = List.of(caller);
        var formatter = com.jsrc.app.output.OutputFormatter.create(true, false, null);
        var parser = new com.jsrc.app.parser.HybridJavaParser();
        
        // Pre-create index so refresh path triggers lazy load
        var indexCmd = new com.jsrc.app.command.meta.IndexCommand();
        var indexCtx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
        indexCmd.execute(indexCtx);
        
        var originalIn = System.in;
        var originalOut = System.out;
        
        System.setIn(new ByteArrayInputStream("{\"command\":\"callers\",\"arg\":\"process\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true));
        
        try {
            var watch = new WatchCommand();
            var ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
            
            com.jsrc.app.index.BinaryIndexV2Reader.resetGraphParsedFlag();
            watch.execute(ctx);
            
            assertTrue(com.jsrc.app.index.BinaryIndexV2Reader.wasGraphParsed(),
                    "Callers command SHOULD parse CallGraph (lazy loading triggered)");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A5 (Frozen Index): Watch with frozen-index flag serves stale index after source mutation.
     * Verifies that --frozen-index prevents refresh even when files change.
     * Strengthened: asserts load count remains 1 despite mutation, proving no refresh.
     */
    @Test
    void watchWithFrozenIndexServesStaleAfterMutation(@TempDir Path tempDir) throws Exception {
        // Create initial file and build index
        var javaFile = createSimpleJavaFile(tempDir);
        var files = List.of(javaFile);
        
        // Build valid index first
        var formatter = OutputFormatter.create(true, false, null);
        var parser = new com.jsrc.app.parser.HybridJavaParser();
        var indexCmd = new com.jsrc.app.command.meta.IndexCommand();
        var indexCtx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
        indexCmd.execute(indexCtx);
        
        tryLoadCounter.set(0);
        
        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();
        
        var inputCommands = new PipedOutputStream();
        var inputStream = new PipedInputStream(inputCommands);
        
        System.setIn(inputStream);
        System.setOut(new PrintStream(outputCapture, true));
        
        try {
            // Create instrumented watch command that respects frozen flag
            var watch = createInstrumentedWatchCommand();
            // Create context WITH frozenIndex=true (fixed constructor usage)
            var ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser,
                    false, null, false, false, null, true);  // frozenIndex=true
            
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));
            
            // First command - should load index once
            inputCommands.write("{\"command\":\"overview\"}\n".getBytes());
            inputCommands.flush();
            Thread.sleep(500);
            
            assertEquals(1, tryLoadCounter.get(), "First command should load index once");
            
            // Mutate source file (touch mtime to force stamp change)
            Files.setLastModifiedTime(javaFile, 
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
            Files.writeString(javaFile, """
                    package demo;
                    public class App {
                        public void run() {}
                        public void newMethodAddedAfterWatch() {}
                    }
                    """);
            Thread.sleep(100);
            
            // Second command after mutation - should NOT refresh when frozen
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
            
            // Contract: with frozen-index, load count stays 1 despite mutation (no refresh)
            assertEquals(1, tryLoadCounter.get(),
                    "With frozen-index, watch should NOT refresh index after mutation (load count stays 1)");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    @Test
    void frozenWatchPreservesConfiguredRootSourceSet(@TempDir Path tempDir) throws Exception {
        Path custom = tempDir.resolve("custom/java/Custom.java");
        Files.createDirectories(custom.getParent());
        Files.writeString(custom, "class Custom {}");
        Files.writeString(tempDir.resolve(".jsrc.yaml"), "sourceRoots:\n  - custom/java\n");
        var config = com.jsrc.app.config.ProjectConfig.load(tempDir).orElseThrow();
        var discovery = new com.jsrc.app.project.ProjectSourceDiscovery()
                .discover(tempDir, config);
        var index = new com.jsrc.app.index.CodebaseIndex();
        index.build(
                new com.jsrc.app.parser.HybridJavaParser(),
                discovery.files(),
                tempDir,
                java.util.List.of(),
                java.util.List.of(),
                discovery.sourceSets());
        var graphBuilder = new com.jsrc.app.analysis.CallGraphBuilder();
        graphBuilder.loadFromIndex(index.getEntries());
        index.saveWithGraph(tempDir, graphBuilder.toCallGraph());

        var originalIn = System.in;
        var originalOut = System.out;
        var captured = new ByteArrayOutputStream();
        System.setIn(new ByteArrayInputStream(
                "{\"command\":\"overview\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(captured, true));
        try {
            var formatter = OutputFormatter.create(true, false, null);
            var context = new CommandContext(
                    discovery.files(), tempDir.toString(), config, formatter,
                    null, new com.jsrc.app.parser.HybridJavaParser(),
                    false, null, false, false, null, true,
                    discovery.model(), java.util.Set.of(), discovery.sourceSets());

            assertEquals(0, new WatchCommand().execute(context));

            java.util.Map<?, ?> envelope = java.util.Arrays.stream(
                            captured.toString().split("\\R"))
                    .filter(line -> line.startsWith("{"))
                    .map(com.jsrc.app.output.JsonReader::parse)
                    .map(java.util.Map.class::cast)
                    .findFirst()
                    .orElseThrow();
            java.util.Map<?, ?> result = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class, envelope.get("result"));
            java.util.Map<?, ?> sourceSets = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.Map.class, result.get("sourceSets"));
            assertEquals(1L, sourceSets.get("main"));
            assertEquals(0L, sourceSets.get("unknown"));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A3: Watch callers ambiguous → envelope exit 0 (post-B sentinel), result/body has ambiguous:true.
     * Validates that watch command with ambiguous callers returns exit 0 and result contains ambiguous:true,
     * aligned with CLI one-shot behavior.
     */
    @Test
    void watchCallersAmbiguousReturnsExitZeroWithAmbiguousFlag(@TempDir Path tempDir) throws Exception {
        // Create two classes with same method name to trigger ambiguity
        Path file1 = tempDir.resolve("Handler1.java");
        Files.writeString(file1, """
                package demo;
                public class Handler1 {
                    public void process() {}
                }
                """);
        
        Path file2 = tempDir.resolve("Handler2.java");
        Files.writeString(file2, """
                package demo;
                public class Handler2 {
                    public void process() {}
                }
                """);

        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream("{\"command\":\"callers\",\"arg\":\"process\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var files = List.of(file1, file2);
            var formatter = OutputFormatter.create(true, false, null);
            var parser = new com.jsrc.app.parser.HybridJavaParser();
            var ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
            watch.execute(ctx);

            String output = outputCapture.toString();
            String[] lines = output.split("\n");
            
            boolean foundAmbiguous = false;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;
                
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope = (Map<String, Object>) JsonReader.parse(line);
                    if (envelope != null && envelope.containsKey("exit") && envelope.containsKey("result")) {
                        // A3: exit should be 0 (post-B sentinel for ambiguous)
                        Object exitObj = envelope.get("exit");
                        assertNotNull(exitObj, "exit field should not be null");
                        long exitCode = ((Number) exitObj).longValue();
                        assertEquals(0L, exitCode, "Ambiguous callers should have exit 0 (post-B sentinel)");
                        
                        // Result should contain ambiguous:true
                        Object result = envelope.get("result");
                        assertNotNull(result, "result field should not be null");
                        
                        if (result instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> resultMap = (Map<String, Object>) result;
                            assertEquals(Boolean.TRUE, resultMap.get("ambiguous"), 
                                    "Result should contain ambiguous:true for ambiguous callers");
                            foundAmbiguous = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    // Skip non-JSON lines
                }
            }
            
            assertTrue(foundAmbiguous, "Should find envelope with exit 0 and ambiguous:true for ambiguous callers");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }
}
