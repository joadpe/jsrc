package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.ClassInfo;

/** The index keeps the modifiers each class declares. */
class ClassModifiersIndexTest {

    private static final Map<String, List<String>> EXPECTED = Map.of(
            "app.Shapes", List.of("public", "abstract"),
            "app.Shapes$Shape", List.of("protected", "sealed"),
            "app.Shapes$Circle", List.of("public", "static", "final"),
            "app.Shapes$Square", List.of("private"),
            "app.Shapes$Kind", List.of(),
            "app.Names", List.of("final"));

    @TempDir
    Path tempDir;

    @Test
    void classModifiersSurviveJsonAndBinaryRoundtrip() throws Exception {
        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(writeShapes()), tempDir, List.of());
        assertModifiers(index.getEntries());
        // Lambda owners are rebuilt with their synthetic methods
        assertTrue(indexedClass(index.getEntries(), "app.Names").methods().stream()
                .anyMatch(method -> method.name().equals("log$lambda$1")));

        index.save(tempDir);
        assertModifiers(CodebaseIndex.load(tempDir));

        var graph = new CallGraphBuilder();
        graph.loadFromIndex(index.getEntries());
        Path binary = tempDir.resolve("modifiers.bin");
        BinaryIndexV2Writer.write(binary, index.getEntries(), graph.toCallGraph());
        assertModifiers(BinaryIndexV2Reader.read(binary).entries());
    }

    @Test
    void indexedClassesReportModifiersAndAbstractness() throws Exception {
        Path file = writeShapes();
        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(file), tempDir, List.of());
        var graph = new CallGraphBuilder();
        graph.loadFromIndex(index.getEntries());
        index.saveWithGraph(tempDir, graph.toCallGraph());
        IndexedCodebase indexed = IndexedCodebase.tryLoad(tempDir, List.of(file));
        assertNotNull(indexed);

        Map<String, List<String>> modifiers = new LinkedHashMap<>();
        for (ClassInfo type : indexed.getAllClasses()) {
            modifiers.put(type.qualifiedName(), type.modifiers());
        }
        assertEquals(EXPECTED, modifiers);
        ClassInfo shapes = indexed.getAllClasses().stream()
                .filter(type -> type.name().equals("Shapes")).findFirst().orElseThrow();
        assertTrue(shapes.isAbstract());
    }

    private Path writeShapes() throws Exception {
        Path file = tempDir.resolve("Shapes.java");
        Files.writeString(file, """
                package app;
                public abstract class Shapes {
                    protected sealed interface Shape permits Circle, Square {}
                    public static final class Circle implements Shape {}
                    private record Square(int side) implements Shape {}
                    enum Kind { ROUND }
                }
                final class Names {
                    void log() { Runnable r = () -> {}; r.run(); }
                }
                """);
        return file;
    }

    private static void assertModifiers(List<IndexEntry> entries) {
        Map<String, List<String>> modifiers = new LinkedHashMap<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass type : entry.classes()) {
                modifiers.put(type.qualifiedName(), type.modifiers());
            }
        }
        assertEquals(EXPECTED, modifiers);
    }

    private static IndexedClass indexedClass(List<IndexEntry> entries, String qualifiedName) {
        return entries.stream()
                .flatMap(entry -> entry.classes().stream())
                .filter(type -> type.qualifiedName().equals(qualifiedName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not indexed: " + qualifiedName));
    }
}
