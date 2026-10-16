package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Index-backed models must keep the class and method type parameters the index persists.
 */
class IndexedCodebaseTypeParametersTest {

    @TempDir
    Path tempDir;

    @Test
    void indexedClassesKeepClassAndMethodTypeParameters() throws Exception {
        Path file = writeRepo();
        ClassInfo parsed = new HybridJavaParser().parseClasses(file).getFirst();

        ClassInfo indexed = loadIndex(file).getAllClasses().getFirst();

        assertEquals(List.of("E"), indexed.typeParameters());
        assertEquals(List.of("T"), method(indexed, "find").typeParameters());
        assertEquals(List.of("K", "V extends Comparable<V>"),
                method(indexed, "sortedKeys").typeParameters());
        assertEquals("public <T> T find(Class<T> type, long id)",
                method(indexed, "find").signature());
        assertEquals(parsed.typeParameters(), indexed.typeParameters());
        assertEquals(signatures(parsed.methods()), signatures(indexed.methods()));
    }

    @Test
    void indexedMethodLookupKeepsMethodTypeParameters() throws Exception {
        Path file = writeRepo();

        List<MethodInfo> found = loadIndex(file).findMethodsByName("sortedKeys");

        assertEquals(1, found.size());
        assertEquals(List.of("K", "V extends Comparable<V>"), found.getFirst().typeParameters());
        assertTrue(found.getFirst().signature()
                        .startsWith("public static <K, V extends Comparable<V>> List<K> sortedKeys("),
                found.getFirst().signature());
    }

    private Path writeRepo() throws Exception {
        Path file = tempDir.resolve("Repo.java");
        Files.writeString(file, """
                package app;
                import java.util.List;
                import java.util.Map;
                public class Repo<E extends Comparable<E>> {
                    public <T> T find(Class<T> type, long id) { return null; }
                    public static <K, V extends Comparable<V>> List<K> sortedKeys(Map<K, V> map) {
                        return List.of();
                    }
                    public E first() { return null; }
                }
                """);
        return file;
    }

    /** Publishes the index as {@code jsrc index} does, then loads it like any command. */
    private IndexedCodebase loadIndex(Path file) throws Exception {
        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(file), tempDir, List.of());
        var graph = new CallGraphBuilder();
        graph.loadFromIndex(index.getEntries());
        index.saveWithGraph(tempDir, graph.toCallGraph());
        IndexedCodebase indexed = IndexedCodebase.tryLoad(tempDir, List.of(file));
        assertNotNull(indexed);
        return indexed;
    }

    private static MethodInfo method(ClassInfo owner, String name) {
        return owner.methods().stream()
                .filter(method -> method.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Method not found: " + name));
    }

    private static List<String> signatures(List<MethodInfo> methods) {
        return methods.stream().map(MethodInfo::signature).toList();
    }
}
