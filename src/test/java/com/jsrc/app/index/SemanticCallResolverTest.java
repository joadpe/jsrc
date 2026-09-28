package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticCallResolverTest {

    @Test
    void repeatedVirtualCallsResolveOnlyConcreteDescendants() {
        IndexedMethod abstractMethod = method("work", "public abstract void work()");
        IndexedMethod concreteMethod = method("work", "public void work()");
        IndexedMethod abstractOtherMethod = method("other", "public abstract void other()");
        IndexedMethod concreteOtherMethod = method("other", "public void other()");
        List<IndexedClass> classes = List.of(
                indexedClass("Service", true, true, List.of(), List.of(),
                        abstractMethod, abstractOtherMethod),
                indexedClass("Base", false, true, List.of(), List.of("Service")),
                indexedClass("First", false, false, List.of("Base"), List.of(),
                        concreteMethod, concreteOtherMethod),
                indexedClass("Second", false, false, List.of(), List.of("Service"), concreteMethod),
                indexedClass("Unrelated", false, false, List.of(), List.of(),
                        concreteMethod, concreteOtherMethod),
                indexedClass("Caller", false, false, List.of(), List.of(),
                        method("call", "public void call()")));
        IndexEntry entry = new IndexEntry("example/Types.java", "hash", 0, classes);
        SemanticCallResolver resolver = new SemanticCallResolver(List.of(entry));
        CallEdge edge = new CallEdge("example.Caller", "call", List.of(), 0,
                "example.Service", "work", List.of(), 10, 0);

        CallEdge otherEdge = new CallEdge("example.Caller", "call", List.of(), 0,
                "example.Service", "other", List.of(), 11, 0);

        List<CallEdge> first = resolver.resolve(edge);
        List<CallEdge> other = resolver.resolve(otherEdge);
        List<CallEdge> repeated = resolver.resolve(edge);

        assertEquals(List.of("example.First", "example.Second"),
                first.stream().map(CallEdge::calleeClass).toList());
        assertEquals(List.of("example.First"),
                other.stream().map(CallEdge::calleeClass).toList());
        assertEquals(first, repeated);
    }

    @Test
    void unknownFunctionalTypeWithoutAstContextRemainsUnresolved() {
        assertNull(EdgeResolver.functionalInputTypes("CustomAction", null));
        assertEquals(List.of("String"),
                EdgeResolver.functionalInputTypes("Consumer<String>", null));
    }

    private static IndexedClass indexedClass(String name, boolean isInterface,
            boolean isAbstract, List<String> superClass, List<String> interfaces,
            IndexedMethod... methods) {
        return new IndexedClass(name, "example", 1, 1, isInterface, isAbstract,
                superClass, interfaces, List.of(methods), List.of(), List.of());
    }

    private static IndexedMethod method(String name, String signature) {
        return new IndexedMethod(name, signature, 1, 1, "void", List.of());
    }
}
