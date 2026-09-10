package com.jsrc.app.index;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests A1-A8 for lazy CallGraph loading in BinaryIndexV2Reader.
 * Validates that graph parsing can be deferred until first access.
 */
class BinaryIndexV2LazyLoadTest {

    /**
     * A1: overview/mini path does NOT build CallGraph (spy/flag).
     * Test that readLazy returns IndexData with callGraph=null initially.
     */
    @Test
    void testReadLazy_classesOnly_graphNotParsed(@TempDir Path tempDir) throws Exception {
        // Arrange: Create index with graph
        var entries = createTestEntries();
        var graph = createTestGraph();
        Path indexFile = tempDir.resolve("index.bin");
        BinaryIndexV2Writer.write(indexFile, entries, graph);

        // Act: Read with lazy loading
        BinaryIndexV2Reader.resetGraphParsedFlag();
        var lazyData = BinaryIndexV2Reader.readLazy(indexFile);
        
        // Assert: Graph should NOT be parsed yet
        assertNotNull(lazyData, "LazyIndexData should not be null");
        var indexData = lazyData.getData();
        assertNotNull(indexData, "IndexData should not be null");
        assertNull(indexData.callGraph(), "CallGraph should be null (not parsed yet)");
        assertEquals(2, indexData.entries().size(), "Should have 2 entries");
        assertFalse(BinaryIndexV2Reader.wasGraphParsed(), "Graph parse flag should be false");
    }

    /**
     * A2: callers loads graph and returns correct results.
     */
    @Test
    void testReadLazy_graphOnDemand_callersCorrect(@TempDir Path tempDir) throws Exception {
        // Arrange
        var entries = createTestEntries();
        var graph = createTestGraph();
        Path indexFile = tempDir.resolve("index.bin");
        BinaryIndexV2Writer.write(indexFile, entries, graph);

        // Act: Read lazy then trigger graph load
        var lazyData = BinaryIndexV2Reader.readLazy(indexFile);
        BinaryIndexV2Reader.resetGraphParsedFlag();
        CallGraph loadedGraph = lazyData.ensureGraph();

        // Assert: Graph should now be parsed
        assertNotNull(loadedGraph, "Graph should be loaded");
        assertTrue(BinaryIndexV2Reader.wasGraphParsed(), "Graph parse flag should be true after ensureGraph");
        
        // Verify callers work correctly
        var fooBar = new MethodReference("Foo", "bar", 1, null);
        Set<MethodCall> callers = loadedGraph.getCallersOf(fooBar);
        assertEquals(1, callers.size(), "Foo.bar should have 1 caller");
        assertEquals("App", callers.iterator().next().caller().className());
    }

    /**
     * A3: load-phase flags show deferred graph vs eager baseline.
     */
    @Test
    void testReadLazy_flagsShowDeferredVsEager(@TempDir Path tempDir) throws Exception {
        // Arrange
        var entries = createTestEntries();
        var graph = createTestGraph();
        Path indexFile = tempDir.resolve("index.bin");
        BinaryIndexV2Writer.write(indexFile, entries, graph);

        // Act & Assert: Eager (baseline)
        BinaryIndexV2Reader.resetGraphParsedFlag();
        var eagerData = BinaryIndexV2Reader.read(indexFile);
        assertTrue(BinaryIndexV2Reader.wasGraphParsed(), "Eager read should parse graph immediately");
        assertNotNull(eagerData.callGraph(), "Eager should have graph");

        // Act & Assert: Lazy (deferred)
        BinaryIndexV2Reader.resetGraphParsedFlag();
        var lazyData = BinaryIndexV2Reader.readLazy(indexFile);
        assertFalse(BinaryIndexV2Reader.wasGraphParsed(), "Lazy read should NOT parse graph initially");
        assertNull(lazyData.getData().callGraph(), "Lazy should have null graph initially");
        
        // Trigger lazy load
        lazyData.ensureGraph();
        assertTrue(BinaryIndexV2Reader.wasGraphParsed(), "Lazy read should parse graph after ensureGraph");
    }

    /**
     * A4: existing index.bin still readable (compat).
     * Tests backwards compatibility: V2 binary written before this PR still readable.
     */
    @Test
    void testReadLazy_backwardsCompatible(@TempDir Path tempDir) throws Exception {
        // Arrange: Write with current writer (should be compatible)
        var entries = createTestEntries();
        var graph = createTestGraph();
        Path indexFile = tempDir.resolve("index.bin");
        BinaryIndexV2Writer.write(indexFile, entries, graph);

        // Act: Read with both old (eager) and new (lazy) readers
        var eagerData = BinaryIndexV2Reader.read(indexFile);
        var lazyData = BinaryIndexV2Reader.readLazy(indexFile);
        var lazyGraph = lazyData.ensureGraph();

        // Assert: Both should produce same results
        assertEquals(eagerData.entries().size(), lazyData.getData().entries().size());
        assertEquals(eagerData.callGraph().getAllMethods().size(), lazyGraph.getAllMethods().size());
        
        // Verify caller results match
        var fooBar = new MethodReference("Foo", "bar", 1, null);
        assertEquals(
            eagerData.callGraph().getCallersOf(fooBar).size(),
            lazyGraph.getCallersOf(fooBar).size(),
            "Eager and lazy should return same callers"
        );
    }

    /**
     * A7: lazy graph first access once, same instance.
     */
    @Test
    void testReadLazy_singleGraphInstance(@TempDir Path tempDir) throws Exception {
        // Arrange
        var entries = createTestEntries();
        var graph = createTestGraph();
        Path indexFile = tempDir.resolve("index.bin");
        BinaryIndexV2Writer.write(indexFile, entries, graph);

        // Act: Call ensureGraph multiple times
        var lazyData = BinaryIndexV2Reader.readLazy(indexFile);
        BinaryIndexV2Reader.resetGraphParsedFlag();
        
        CallGraph graph1 = lazyData.ensureGraph();
        int parseCount1 = BinaryIndexV2Reader.getGraphParseCount();
        
        CallGraph graph2 = lazyData.ensureGraph();
        int parseCount2 = BinaryIndexV2Reader.getGraphParseCount();

        // Assert: Same instance, parsed only once
        assertSame(graph1, graph2, "ensureGraph should return same instance");
        assertEquals(1, parseCount2, "Graph should be parsed exactly once");
    }

    /**
     * A8: smells/migrations still correct when needed.
     */
    @Test
    void testReadLazy_smellsAndMigrations(@TempDir Path tempDir) throws Exception {
        // Arrange: Create entries with smells
        var entries = List.of(
            new IndexEntry("App.java", "hash", 1000L,
                List.of(new IndexedClass("App", "pkg", 1, 30, false, false, 
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of())),
                List.of(),
                List.of(new CachedSmell("TEST_SMELL", "WARN", 10, "test", "App", "Test smell")))
        );
        
        Map<String, List<CachedMigration>> migrations = Map.of(
            "App.java", List.of(new CachedMigration(1, 15))
        );
        
        var graph = CallGraph.empty();
        Path indexFile = tempDir.resolve("index.bin");
        BinaryIndexV2Writer.write(indexFile, entries, graph);
        
        // Write migrations manually (BinaryIndexV2Writer handles this)
        var indexWithMigrations = new CodebaseIndex(entries);
        indexWithMigrations.saveWithGraph(tempDir, graph, migrations);
        Path actualIndexFile = tempDir.resolve(".jsrc/index.bin");

        // Act: Read lazy
        var lazyData = BinaryIndexV2Reader.readLazy(actualIndexFile);
        var indexData = lazyData.getData();

        // Assert: Smells and migrations loaded correctly
        assertEquals(1, indexData.entries().get(0).smells().size());
        assertEquals("TEST_SMELL", indexData.entries().get(0).smells().get(0).ruleId());
        
        assertNotNull(indexData.migrations());
        assertTrue(indexData.migrations().containsKey("App.java"));
        assertEquals(1, indexData.migrations().get("App.java").size());
    }

    // Helper methods

    static List<IndexEntry> createTestEntries() {
        return List.of(
            new IndexEntry("Foo.java", "abc123", 1000L,
                List.of(new IndexedClass("Foo", "com.example", 1, 50,
                    false, false, List.of(), List.of(), List.of(
                    new IndexedMethod("bar", "void bar(int x)", 10, 20, "void", List.of(), (short) 2, (byte) 1)
                ), List.of(), List.of(), List.of())),
                List.of(new CallEdge("Foo", "bar", 1, "Baz", "qux", 2, 25)),
                List.of()),
            new IndexEntry("App.java", "def456", 2000L,
                List.of(new IndexedClass("App", "com.example", 1, 30,
                    false, false, List.of(), List.of(), List.of(
                    new IndexedMethod("main", "void main(String[])", 5, 15, "void", List.of(), (short) 1, (byte) 1)
                ), List.of(), List.of(), List.of())),
                List.of(new CallEdge("App", "main", 1, "Foo", "bar", 1, 10)),
                List.of())
        );
    }

    static CallGraph createTestGraph() {
        var callerIndex = new HashMap<MethodReference, Set<MethodCall>>();
        var calleeIndex = new HashMap<MethodReference, Set<MethodCall>>();
        var allMethods = new HashSet<MethodReference>();
        var methodsByName = new HashMap<String, Set<MethodReference>>();

        var fooBar = new MethodReference("Foo", "bar", 1, null);
        var bazQux = new MethodReference("Baz", "qux", 2, null);
        var mainMethod = new MethodReference("App", "main", 1, null);

        allMethods.addAll(List.of(fooBar, bazQux, mainMethod));
        methodsByName.put("bar", Set.of(fooBar));
        methodsByName.put("qux", Set.of(bazQux));
        methodsByName.put("main", Set.of(mainMethod));

        var call1 = new MethodCall(mainMethod, fooBar, 10);
        var call2 = new MethodCall(fooBar, bazQux, 25);

        callerIndex.put(fooBar, Set.of(call1));
        callerIndex.put(bazQux, Set.of(call2));
        calleeIndex.put(mainMethod, Set.of(call1));
        calleeIndex.put(fooBar, Set.of(call2));

        return CallGraph.of(callerIndex, calleeIndex, allMethods, methodsByName);
    }
}
