package com.jsrc.app.index;

import com.jsrc.app.analysis.CallGraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A6: Graph-dependent commands return identical results vs eager baseline.
 * Tests that lazy loading produces same caller/callee results as eager loading.
 */
class LazyCallGraphIntegrationTest {

    /**
     * A6: Lazy-loaded graph returns identical callers as eager-loaded graph.
     */
    @Test
    void callersLazy_matchesEager(@TempDir Path tempDir) throws Exception {
        // Arrange: Create test graph
        var entries = BinaryIndexV2LazyLoadTest.createTestEntries();
        var graph = BinaryIndexV2LazyLoadTest.createTestGraph();
        Path indexFile = tempDir.resolve("index.bin");
        BinaryIndexV2Writer.write(indexFile, entries, graph);

        // Act: Load with eager
        var eagerData = BinaryIndexV2Reader.read(indexFile);
        CallGraph eagerGraph = eagerData.callGraph();

        // Act: Load with lazy
        var lazyData = BinaryIndexV2Reader.readLazy(indexFile);
        CallGraph lazyGraph = lazyData.ensureGraph();

        // Assert: Callers should match
        var fooBar = new com.jsrc.app.parser.model.MethodReference("Foo", "bar", 1, null);
        var eagerCallers = eagerGraph.getCallersOf(fooBar);
        var lazyCallers = lazyGraph.getCallersOf(fooBar);

        assertEquals(eagerCallers.size(), lazyCallers.size(), 
            "Lazy and eager should return same number of callers");
        
        if (!eagerCallers.isEmpty()) {
            assertEquals(
                eagerCallers.iterator().next().caller().className(),
                lazyCallers.iterator().next().caller().className(),
                "Lazy and eager should return same caller class"
            );
        }
        
        // Assert: Callees should match
        var mainMethod = new com.jsrc.app.parser.model.MethodReference("App", "main", 1, null);
        var eagerCallees = eagerGraph.getCalleesOf(mainMethod);
        var lazyCallees = lazyGraph.getCalleesOf(mainMethod);
        
        assertEquals(eagerCallees.size(), lazyCallees.size(),
            "Lazy and eager should return same number of callees");
    }
}
