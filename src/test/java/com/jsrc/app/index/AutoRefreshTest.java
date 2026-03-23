package com.jsrc.app.index;

import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.parser.HybridJavaParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that auto-refresh preserves smells and migrations when files are modified.
 */
class AutoRefreshTest {

    @TempDir
    Path tempDir;

    /** Builds a full index with smells (simulates jsrc index). */
    private IndexedCodebase buildIndex(List<Path> files) throws Exception {
        var parser = new HybridJavaParser();
        var edgeResolver = new EdgeResolver();
        var javaParser = new com.github.javaparser.JavaParser();
        List<IndexEntry> entries = new ArrayList<>();

        for (Path file : files) {
            byte[] content = Files.readAllBytes(file);
            String hash = com.jsrc.app.util.Hashing.sha256(content);
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            var classes = parser.parseClasses(file);
            var indexedClasses = classes.stream()
                    .map(IndexedCodebase::classInfoToIndexed).toList();
            var edges = edgeResolver.extractCallEdges(file, javaParser);
            var smells = parser.detectSmells(file).stream()
                    .map(s -> new CachedSmell(s.ruleId(), s.severity().name(),
                            s.line(), s.methodName(), s.className(), s.message()))
                    .toList();
            entries.add(new IndexEntry(tempDir.relativize(file).toString(),
                    hash, lastModified, indexedClasses, edges, smells));
        }

        var codebaseIndex = new CodebaseIndex(entries);
        var builder = new CallGraphBuilder();
        builder.loadFromIndex(entries);
        codebaseIndex.saveWithGraph(tempDir, builder.toCallGraph());
        return IndexedCodebase.tryLoad(tempDir, files);
    }

    @Test
    void preservesSmellsForUnmodifiedFiles() throws Exception {
        Path fileA = tempDir.resolve("ServiceA.java");
        Files.writeString(fileA,
                "package test;\npublic class ServiceA {\n" +
                "  public void process() {\n" +
                "    try { throw new Exception(); } catch (Exception e) { }\n" +
                "  }\n}");
        Path fileB = tempDir.resolve("ServiceB.java");
        Files.writeString(fileB,
                "package test;\npublic class ServiceB {\n" +
                "  public void run() { System.out.println(\"hi\"); }\n}");

        var indexed1 = buildIndex(List.of(fileA, fileB));
        assertNotNull(indexed1);

        var entryA1 = indexed1.getEntries().stream()
                .filter(e -> e.path().contains("ServiceA")).findFirst().orElse(null);
        assertNotNull(entryA1);
        assertFalse(entryA1.smells().isEmpty(), "ServiceA should have smells (empty catch)");
        int smellsBefore = entryA1.smells().size();

        // Modify only ServiceB
        Thread.sleep(100);
        Files.writeString(fileB,
                "package test;\npublic class ServiceB {\n" +
                "  public void changed() { int x = 1; }\n}");

        var indexed2 = IndexedCodebase.tryLoad(tempDir, List.of(fileA, fileB));
        assertNotNull(indexed2);

        var entryA2 = indexed2.getEntries().stream()
                .filter(e -> e.path().contains("ServiceA")).findFirst().orElse(null);
        assertNotNull(entryA2);
        assertFalse(entryA2.smells().isEmpty(),
                "Smells for unmodified ServiceA should survive auto-refresh");
        assertEquals(smellsBefore, entryA2.smells().size());
    }

    @Test
    void recomputesSmellsForModifiedFiles() throws Exception {
        Path file = tempDir.resolve("Clean.java");
        Files.writeString(file,
                "package test;\npublic class Clean {\n" +
                "  public void ok() { return; }\n}");

        var indexed1 = buildIndex(List.of(file));
        assertNotNull(indexed1);

        // Modify to add empty catch
        Thread.sleep(100);
        Files.writeString(file,
                "package test;\npublic class Clean {\n" +
                "  public void ok() {\n" +
                "    try { throw new Exception(); } catch (Exception e) { }\n" +
                "  }\n}");

        var indexed2 = IndexedCodebase.tryLoad(tempDir, List.of(file));
        assertNotNull(indexed2);

        var entry = indexed2.getEntries().stream()
                .filter(e -> e.path().contains("Clean")).findFirst().orElse(null);
        assertNotNull(entry);
        assertFalse(entry.smells().isEmpty(),
                "Smells should be re-computed for modified file with new empty catch");
    }

    @Test
    void doesNotWipeMigrationsOnSave() throws Exception {
        Path fileA = tempDir.resolve("TrimUser.java");
        Files.writeString(fileA,
                "package test;\npublic class TrimUser {\n" +
                "  public String clean(String s) { return s.trim(); }\n}");
        Path fileB = tempDir.resolve("Other.java");
        Files.writeString(fileB,
                "package test;\npublic class Other {\n" +
                "  public void noop() { }\n}");

        buildIndex(List.of(fileA, fileB));
        var v2Before = BinaryIndexV2Reader.read(tempDir.resolve(".jsrc/index.bin"));

        // Modify only Other
        Thread.sleep(100);
        Files.writeString(fileB,
                "package test;\npublic class Other {\n" +
                "  public void changed() { int x = 2; }\n}");

        IndexedCodebase.tryLoad(tempDir, List.of(fileA, fileB));
        var v2After = BinaryIndexV2Reader.read(tempDir.resolve(".jsrc/index.bin"));

        if (!v2Before.migrations().isEmpty()) {
            assertFalse(v2After.migrations().isEmpty(),
                    "Migrations must not be wiped when saving after auto-refresh");
        }
    }
}
