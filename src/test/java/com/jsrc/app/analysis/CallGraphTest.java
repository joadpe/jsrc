package com.jsrc.app.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.BinaryIndexV2Reader;
import com.jsrc.app.index.BinaryIndexV2Writer;
import com.jsrc.app.model.InvocationKind;
import com.jsrc.app.model.ResolutionLevel;
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

    // Methods in the defined order: class name, method name, parameter types, parameter count.
    private static final MethodReference UNRESOLVED_NAME = MethodReference.unresolved("name", 0);
    private static final MethodReference A_CONSTRUCTOR = typed("app.A", "A");
    private static final MethodReference A_FORMAT_STRING = typed("app.A", "format", "String");
    private static final MethodReference A_FORMAT_INT = typed("app.A", "format", "int");
    private static final MethodReference A_NAME = typed("app.A", "name");
    private static final MethodReference B_NAME = typed("app.B", "name");
    private static final MethodReference B_OTHER = typed("app.B", "other", "int");
    private static final MethodReference C_HELP = new MethodReference("app.C", "help", 1, null);
    private static final MethodReference MAIN = typed("app.Main", "main", "String[]");
    private static final List<MethodReference> METHODS = List.of(UNRESOLVED_NAME, A_CONSTRUCTOR,
            A_FORMAT_STRING, A_FORMAT_INT, A_NAME, B_NAME, B_OTHER, C_HELP, MAIN);

    // Calls in the defined order: caller, line, callee, invocation kind, resolution, evidence.
    private static final List<MethodCall> CALLS = List.of(
            call(B_NAME, C_HELP, 2, InvocationKind.STATIC, ResolutionLevel.INFERRED),
            call(B_NAME, UNRESOLVED_NAME, 3, InvocationKind.VIRTUAL, ResolutionLevel.UNRESOLVED,
                    "UNRESOLVED_RECEIVER"),
            call(B_NAME, A_CONSTRUCTOR, 3, InvocationKind.CONSTRUCTOR, ResolutionLevel.EXACT,
                    "OBJECT_CREATION"),
            call(B_NAME, A_FORMAT_STRING, 3, InvocationKind.VIRTUAL, ResolutionLevel.EXACT),
            call(B_NAME, A_FORMAT_INT, 3, InvocationKind.VIRTUAL, ResolutionLevel.EXACT),
            call(B_NAME, A_NAME, 5, InvocationKind.VIRTUAL, ResolutionLevel.EXACT),
            call(B_NAME, A_NAME, 5, InvocationKind.VIRTUAL, ResolutionLevel.INFERRED),
            call(B_NAME, A_NAME, 5, InvocationKind.INTERFACE, ResolutionLevel.EXACT),
            call(B_NAME, A_NAME, 7, InvocationKind.VIRTUAL, ResolutionLevel.EXACT, "A"),
            call(B_NAME, A_NAME, 7, InvocationKind.VIRTUAL, ResolutionLevel.EXACT, "B"),
            call(B_OTHER, A_NAME, 12, InvocationKind.VIRTUAL, ResolutionLevel.EXACT),
            call(MAIN, A_NAME, 4, InvocationKind.VIRTUAL, ResolutionLevel.EXACT),
            call(MAIN, B_NAME, 4, InvocationKind.STATIC, ResolutionLevel.EXACT));

    @Test
    void everySetIteratesInTheDefinedOrderWhateverTheInputOrder() {
        var callerIndex = new HashMap<MethodReference, Set<MethodCall>>();
        var calleeIndex = new HashMap<MethodReference, Set<MethodCall>>();
        var allMethods = new HashSet<MethodReference>();
        var methodsByName = new HashMap<String, Set<MethodReference>>();
        for (MethodCall call : CALLS.reversed()) {
            callerIndex.computeIfAbsent(call.callee(), ignored -> new HashSet<>()).add(call);
            calleeIndex.computeIfAbsent(call.caller(), ignored -> new HashSet<>()).add(call);
            for (MethodReference method : List.of(call.callee(), call.caller())) {
                allMethods.add(method);
                methodsByName.computeIfAbsent(method.methodName(), ignored -> new HashSet<>())
                        .add(method);
            }
        }
        assertDefinedOrder(CallGraph.of(callerIndex, calleeIndex, allMethods, methodsByName));

        var builder = new CallGraphBuilder();
        CALLS.reversed().forEach(builder::addEdge);
        assertDefinedOrder(builder.toCallGraph());
    }

    @Test
    void graphsReadFromTheIndexKeepTheOrderAndPublishTheSameBytes(@TempDir Path dir)
            throws Exception {
        var builder = new CallGraphBuilder();
        CALLS.reversed().forEach(builder::addEdge);
        Path built = dir.resolve("built.bin");
        BinaryIndexV2Writer.write(built, List.of(), builder.toCallGraph());

        CallGraph read = BinaryIndexV2Reader.read(built).callGraph();
        assertDefinedOrder(read);
        assertDefinedOrder(BinaryIndexV2Reader.readLazy(built).ensureGraph());

        // A reused graph is published again as read; it must not differ from a fresh build.
        Path republished = dir.resolve("republished.bin");
        BinaryIndexV2Writer.write(republished, List.of(), read);
        assertEquals(-1L, Files.mismatch(built, republished));
    }

    @Test
    void orderedSetsKeepSetSemantics() {
        var caller = ref("Controller", "handle");
        var first = new MethodCall(caller, ref("Service", "load"), 3);
        var second = new MethodCall(caller, ref("Service", "save"), 4);

        var graph = CallGraph.of(
                Map.of(), Map.of(caller, Set.of(second, first)), Set.of(), Map.of());

        Set<MethodCall> callees = graph.getCalleesOf(caller);
        assertEquals(List.of(first, second), List.copyOf(callees));
        assertEquals(Set.of(first, second), callees);
        assertEquals(callees, new HashSet<>(List.of(second, first)));
        assertEquals(Set.of(first, second).hashCode(), callees.hashCode());
        assertTrue(callees.contains(second));
        assertFalse(callees.contains(new MethodCall(caller, ref("Service", "save"), 5)));
        assertFalse(callees.contains("Service.save"));
        assertEquals(List.of(first, second), callees.stream().toList());
        assertThrows(UnsupportedOperationException.class, () -> callees.add(first));
        assertThrows(UnsupportedOperationException.class, () -> {
            var iterator = callees.iterator();
            iterator.next();
            iterator.remove();
        });
    }

    @Test
    void duplicateCallsAndMethodsCountOnce() {
        var caller = ref("Controller", "handle");
        var callee = ref("Service", "save");
        var call = new MethodCall(caller, callee, 4);

        var graph = CallGraph.of(Map.of(callee, List.of(call, call)),
                Map.of(caller, List.of(call, call)), List.of(callee, caller, callee),
                Map.of("save", List.of(callee, callee)));

        assertEquals(List.of(call), List.copyOf(graph.getCallersOf(callee)));
        assertEquals(List.of(call), List.copyOf(graph.getCalleesOf(caller)));
        assertEquals(List.of(caller, callee), List.copyOf(graph.getAllMethods()));
        assertEquals(List.of(callee), List.copyOf(graph.findMethodsByName("save")));
    }

    @Test
    void referencesThatDifferOnlyInParameterCountStayApart() {
        // A call of unknown arity and a call with one argument of unknown type share the
        // class, the name and the parameter types ["?"].
        var unknownArity = ref("Svc", "run");
        var oneArgument = typed("Svc", "run", "?");
        assertNotEquals(unknownArity, oneArgument);
        var caller = typed("App", "main");
        var viaUnknown = new MethodCall(caller, unknownArity, 3);
        var viaOne = new MethodCall(caller, oneArgument, 3);

        var graph = CallGraph.of(
                Map.of(oneArgument, Set.of(viaOne), unknownArity, Set.of(viaUnknown)),
                Map.of(caller, Set.of(viaOne, viaUnknown)),
                Set.of(caller, oneArgument, unknownArity),
                Map.of("run", Set.of(oneArgument, unknownArity), "main", Set.of(caller)));

        assertEquals(List.of(caller, unknownArity, oneArgument),
                List.copyOf(graph.getAllMethods()));
        assertEquals(List.of(unknownArity, oneArgument),
                List.copyOf(graph.findMethodsByName("run")));
        assertEquals(List.of(unknownArity, oneArgument),
                List.copyOf(graph.getAllCallerIndexKeys()));
        assertEquals(List.of(viaUnknown, viaOne), List.copyOf(graph.getCalleesOf(caller)));
        assertTrue(graph.getAllMethods().containsAll(List.of(unknownArity, oneArgument)));
    }

    @Test
    void everyComponentThatEqualsComparesKeepsEntriesApart() {
        // The orders must compare what equals compares, or distinct entries collapse into one.
        assertEquals(5, MethodReference.class.getRecordComponents().length,
                "Compare a new MethodReference component in CallGraph.compareMethods");
        assertEquals(6, MethodCall.class.getRecordComponents().length,
                "Compare a new MethodCall component in CallGraph.compareCalls");
        var methods = List.of(
                new MethodReference("A", "m", List.of("int"), 1, null),
                new MethodReference("B", "m", List.of("int"), 1, null),
                new MethodReference("A", "n", List.of("int"), 1, null),
                new MethodReference("A", "m", List.of("long"), 1, null),
                new MethodReference("A", "m", List.of("int"), 2, null),
                // equals ignores the file path
                new MethodReference("A", "m", List.of("int"), 1, Path.of("src/A.java")));
        assertEquals(5, CallGraph.of(Map.of(), Map.of(), methods, Map.of())
                .getAllMethods().size());

        var caller = typed("C", "go");
        var callee = typed("D", "run");
        var calls = List.of(
                call(caller, callee, 1, InvocationKind.VIRTUAL, ResolutionLevel.EXACT, "e"),
                call(callee, callee, 1, InvocationKind.VIRTUAL, ResolutionLevel.EXACT, "e"),
                call(caller, caller, 1, InvocationKind.VIRTUAL, ResolutionLevel.EXACT, "e"),
                call(caller, callee, 2, InvocationKind.VIRTUAL, ResolutionLevel.EXACT, "e"),
                call(caller, callee, 1, InvocationKind.STATIC, ResolutionLevel.EXACT, "e"),
                call(caller, callee, 1, InvocationKind.VIRTUAL, ResolutionLevel.INFERRED, "e"),
                call(caller, callee, 1, InvocationKind.VIRTUAL, ResolutionLevel.EXACT, "f"));
        assertEquals(7, CallGraph.of(Map.of(), Map.of(caller, calls), List.of(), Map.of())
                .getCalleesOf(caller).size());
    }

    @Test
    void parallelStreamsKeepTheDefinedOrder() {
        var methods = new HashSet<MethodReference>();
        for (int index = 0; index < 500; index++) {
            methods.add(new MethodReference(
                    "C" + index % 37, "m" + index * 7919 % 101, index % 3, null));
        }
        Set<MethodReference> allMethods =
                CallGraph.of(Map.of(), Map.of(), methods, Map.of()).getAllMethods();

        var spliterator = allMethods.spliterator();
        assertTrue(spliterator.hasCharacteristics(
                Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SIZED));
        // The elements have no natural order, so the set must not claim to be sorted by one.
        assertFalse(spliterator.hasCharacteristics(Spliterator.SORTED));
        assertEquals(List.copyOf(allMethods), allMethods.parallelStream().toList());
    }

    private static void assertDefinedOrder(CallGraph graph) {
        for (MethodReference method : METHODS) {
            assertEquals(CALLS.stream().filter(call -> call.caller().equals(method)).toList(),
                    List.copyOf(graph.getCalleesOf(method)), "Calls made by " + method);
            assertEquals(CALLS.stream().filter(call -> call.callee().equals(method)).toList(),
                    List.copyOf(graph.getCallersOf(method)), "Calls to " + method);
        }
        assertEquals(METHODS, List.copyOf(graph.getAllMethods()));
        assertEquals(List.of(UNRESOLVED_NAME, A_NAME, B_NAME),
                List.copyOf(graph.findMethodsByName("name")));
        assertEquals(METHODS.stream().filter(method -> CALLS.stream()
                        .anyMatch(call -> call.callee().equals(method))).toList(),
                List.copyOf(graph.getAllCallerIndexKeys()));
    }

    private static MethodReference typed(String cls, String method, String... parameterTypes) {
        return new MethodReference(cls, method, List.of(parameterTypes), null);
    }

    private static MethodCall call(MethodReference caller, MethodReference callee, int line,
                                   InvocationKind kind, ResolutionLevel resolution,
                                   String... evidence) {
        return new MethodCall(caller, callee, line, kind, resolution, List.of(evidence));
    }

    private static MethodReference ref(String cls, String method) {
        return new MethodReference(cls, method, -1, null);
    }
}
