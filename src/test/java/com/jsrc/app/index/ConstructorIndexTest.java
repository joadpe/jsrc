package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.model.DependencyResult;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Constructors are persisted with the modifiers, type parameters, annotations and exceptions
 * they declare.
 */
class ConstructorIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void constructorsKeepDeclaredMetadataAcrossJsonAndBinaryRoundtrip() throws Exception {
        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(writeBox()), tempDir, List.of());
        assertConstructors(index.getEntries());

        index.save(tempDir);
        assertConstructors(CodebaseIndex.load(tempDir));

        var graph = new CallGraphBuilder();
        graph.loadFromIndex(index.getEntries());
        Path binary = tempDir.resolve("constructors.bin");
        BinaryIndexV2Writer.write(binary, index.getEntries(), graph.toCallGraph());
        assertConstructors(BinaryIndexV2Reader.read(binary).entries());
    }

    @Test
    void indexedViewKeepsConstructorVisibilityAndDependencies() throws Exception {
        Path file = writeBox();
        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(file), tempDir, List.of());
        var graph = new CallGraphBuilder();
        graph.loadFromIndex(index.getEntries());
        index.saveWithGraph(tempDir, graph.toCallGraph());
        IndexedCodebase indexed = IndexedCodebase.tryLoad(tempDir, List.of(file));
        assertNotNull(indexed);

        List<MethodInfo> constructors = indexed.getAllClasses().getFirst().methods();
        assertEquals(List.of(List.of("private"), List.of("public"), List.of()),
                constructors.stream().map(MethodInfo::modifiers).toList());

        // Every constructor's parameters are dependencies, whatever its visibility
        DependencyResult deps = indexed.getDependencies("Box").orElseThrow();
        assertEquals(List.of("Object", "T", "Class"), deps.constructorDependencies().stream()
                .map(DependencyResult.FieldDep::type).toList());
    }

    private Path writeBox() throws Exception {
        Path file = tempDir.resolve("Box.java");
        Files.writeString(file, """
                package app;
                import java.io.IOException;
                public class Box {
                    private Box(Object value) {}
                    @Deprecated
                    public <T> Box(T value, Class<T> type) throws IOException {}
                    Box() {}
                }
                """);
        return file;
    }

    private static void assertConstructors(List<IndexEntry> entries) {
        List<IndexedMethod> constructors = entries.getFirst().classes().getFirst().methods();
        assertEquals(List.of(
                        "private Box(Object value)",
                        "public <T> Box(T value, Class<T> type) throws IOException",
                        "Box()"),
                constructors.stream().map(IndexedMethod::signature).toList());
        assertEquals(List.of("T"), constructors.get(1).typeParameters());
        assertEquals(List.of("Deprecated"), constructors.get(1).annotations());
    }
}
