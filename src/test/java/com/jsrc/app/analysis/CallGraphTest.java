package com.jsrc.app.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Tests for CallGraph — immutable query container produced by CallGraphBuilder.
 */
class CallGraphTest {

    @Test
    void emptyGraph() {
        var graph = CallGraph.empty();
        assertTrue(graph.getAllMethods().isEmpty());
        assertTrue(graph.getCallersOf(ref("A", "foo")).isEmpty());
        assertTrue(graph.getCalleesOf(ref("A", "foo")).isEmpty());
        assertTrue(graph.findMethodsByName("foo").isEmpty());
    }

    @Test
    void queryCallersAndCallees() {
        var caller = ref("Controller", "handle");
        var callee = ref("Service", "process");
        var call = new MethodCall(caller, callee, 10);

        var graph = CallGraph.of(
                Map.of(callee, Set.of(call)),  // callerIndex
                Map.of(caller, Set.of(call)),  // calleeIndex
                Set.of(caller, callee),        // allMethods
                Map.of("handle", Set.of(caller), "process", Set.of(callee))
        );

        assertEquals(Set.of(call), graph.getCallersOf(callee));
        assertEquals(Set.of(call), graph.getCalleesOf(caller));
        assertTrue(graph.getCallersOf(caller).isEmpty());
    }

    @Test
    void findMethodsByName() {
        var m1 = ref("A", "foo");
        var m2 = ref("B", "foo");

        var graph = CallGraph.of(
                Map.of(), Map.of(),
                Set.of(m1, m2),
                Map.of("foo", Set.of(m1, m2))
        );

        var found = graph.findMethodsByName("foo");
        assertEquals(2, found.size());
        assertTrue(found.contains(m1));
        assertTrue(found.contains(m2));
    }

    @Test
    void isRoot_noCallers() {
        var root = ref("Main", "main");
        var child = ref("Service", "run");
        var call = new MethodCall(root, child, 5);

        var graph = CallGraph.of(
                Map.of(child, Set.of(call)),
                Map.of(root, Set.of(call)),
                Set.of(root, child),
                Map.of("main", Set.of(root), "run", Set.of(child))
        );

        assertTrue(graph.isRoot(root), "main has no callers → root");
        assertFalse(graph.isRoot(child), "run has callers → not root");
    }

    @Test
    void immutability_collectionsAreUnmodifiable() {
        var graph = CallGraph.empty();
        assertThrows(UnsupportedOperationException.class,
                () -> graph.getAllMethods().add(ref("X", "y")));
    }

    @Test
    void getAllCallerIndexKeys() {
        var callee = ref("Svc", "run");
        var call = new MethodCall(ref("A", "go"), callee, 1);

        var graph = CallGraph.of(
                Map.of(callee, Set.of(call)),
                Map.of(),
                Set.of(),
                Map.of()
        );

        assertTrue(graph.getAllCallerIndexKeys().contains(callee));
    }

    private static MethodReference ref(String cls, String method) {
        return new MethodReference(cls, method, -1, null);
    }
}
