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
 * Roundtrip test: build CallGraph → serialize → deserialize → verify identical results.
 * This is the critical test Rune requested: MethodReference equals/hashCode
 * must reconstruct identically for HashMap lookup to work.
 */
class BinaryIndexV2RoundtripTest {

    @Test
    void roundtripPreservesCallGraph(@TempDir Path tempDir) throws Exception {
        // Build a call graph manually
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

        // main calls bar, bar calls qux
        var call1 = new MethodCall(mainMethod, fooBar, 10);
        var call2 = new MethodCall(fooBar, bazQux, 25);

        callerIndex.put(fooBar, Set.of(call1));      // fooBar is called by main
        callerIndex.put(bazQux, Set.of(call2));       // bazQux is called by fooBar
        calleeIndex.put(mainMethod, Set.of(call1));   // main calls fooBar
        calleeIndex.put(fooBar, Set.of(call2));       // fooBar calls bazQux

        CallGraph original = CallGraph.of(callerIndex, calleeIndex, allMethods, methodsByName);

        // Create minimal index entries
        var entries = List.of(
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
                        List.of(new CachedSmell("METHOD_TOO_LONG", "INFO", 5, "main", "App", "Method too long")))
        );

        // Write
        Path indexFile = tempDir.resolve("index.bin");
        BinaryIndexV2Writer.write(indexFile, entries, original);

        assertTrue(indexFile.toFile().exists());
        assertTrue(indexFile.toFile().length() > 100, "Binary should have content");

        // Read
        var result = BinaryIndexV2Reader.read(indexFile);
        assertNotNull(result);

        // Verify entries
        assertEquals(2, result.entries().size());
        assertEquals("Foo.java", result.entries().get(0).path());
        assertEquals("App.java", result.entries().get(1).path());

        // Verify classes preserved
        assertEquals("Foo", result.entries().get(0).classes().get(0).name());
        assertEquals(1, result.entries().get(0).classes().get(0).methods().size());
        assertEquals("bar", result.entries().get(0).classes().get(0).methods().get(0).name());

        // Verify edges preserved
        assertEquals(1, result.entries().get(0).callEdges().size());
        assertEquals("Baz", result.entries().get(0).callEdges().get(0).calleeClass());

        // Verify smells preserved
        assertEquals(1, result.entries().get(1).smells().size());
        assertEquals("METHOD_TOO_LONG", result.entries().get(1).smells().get(0).ruleId());

        // CRITICAL: Verify call graph roundtrip
        CallGraph loaded = result.callGraph();
        assertNotNull(loaded, "Call graph should be present");

        // Verify all methods
        assertEquals(original.getAllMethods().size(), loaded.getAllMethods().size(),
                "Should have same number of methods");

        // Verify callers of fooBar — MUST find main as caller
        var fooBarLoaded = new MethodReference("Foo", "bar", 1, null);
        Set<MethodCall> fooBarCallers = loaded.getCallersOf(fooBarLoaded);
        assertEquals(1, fooBarCallers.size(),
                "Foo.bar should have 1 caller (main). This tests MethodReference equals/hashCode roundtrip.");
        assertEquals("App", fooBarCallers.iterator().next().caller().className());

        // Verify callers of bazQux — MUST find fooBar as caller
        var bazQuxLoaded = new MethodReference("Baz", "qux", 2, null);
        Set<MethodCall> bazQuxCallers = loaded.getCallersOf(bazQuxLoaded);
        assertEquals(1, bazQuxCallers.size(), "Baz.qux should have 1 caller (Foo.bar)");
        assertEquals("Foo", bazQuxCallers.iterator().next().caller().className());

        // Verify callees of main — MUST find fooBar
        var mainLoaded = new MethodReference("App", "main", 1, null);
        Set<MethodCall> mainCallees = loaded.getCalleesOf(mainLoaded);
        assertEquals(1, mainCallees.size(), "App.main should call 1 method (Foo.bar)");
        assertEquals("Foo", mainCallees.iterator().next().callee().className());

        // Verify methodsByName
        assertEquals(1, loaded.findMethodsByName("bar").size());
        assertEquals(1, loaded.findMethodsByName("qux").size());

        // Verify isRoot
        assertTrue(loaded.isRoot(mainLoaded), "main should be a root (nobody calls it)");
        assertFalse(loaded.isRoot(fooBarLoaded), "Foo.bar should NOT be a root (main calls it)");
    }

    @Test
    void corruptCrcThrowsException(@TempDir Path tempDir) throws Exception {
        var entries = List.of(new IndexEntry("A.java", "hash", 1L,
                List.of(new IndexedClass("A", "pkg", 1, 10,
                        false, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()))));

        Path indexFile = tempDir.resolve("index.bin");
        BinaryIndexV2Writer.write(indexFile, entries, null);

        // Corrupt the file (flip a byte in payload)
        byte[] bytes = java.nio.file.Files.readAllBytes(indexFile);
        bytes[bytes.length - 1] ^= 0xFF;
        java.nio.file.Files.write(indexFile, bytes);

        assertThrows(java.io.IOException.class, () -> BinaryIndexV2Reader.read(indexFile),
                "Corrupted index should throw IOException");
    }
}
