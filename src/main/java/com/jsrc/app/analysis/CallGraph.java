package com.jsrc.app.analysis;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Immutable directed call graph. Query-only — no mutation after construction.
 * <p>
 * Produced by {@link CallGraphBuilder}, consumed by commands and analyzers.
 */
public final class CallGraph {

    private final Map<MethodReference, Set<MethodCall>> callerIndex;
    private final Map<MethodReference, Set<MethodCall>> calleeIndex;
    private final Set<MethodReference> allMethods;
    private final Map<String, Set<MethodReference>> methodsByName;

    private CallGraph(Map<MethodReference, Set<MethodCall>> callerIndex,
                      Map<MethodReference, Set<MethodCall>> calleeIndex,
                      Set<MethodReference> allMethods,
                      Map<String, Set<MethodReference>> methodsByName) {
        this.callerIndex = callerIndex;
        this.calleeIndex = calleeIndex;
        this.allMethods = allMethods;
        this.methodsByName = methodsByName;
    }

    /**
     * Creates a CallGraph from the given indexes.
     */
    public static CallGraph of(Map<MethodReference, Set<MethodCall>> callerIndex,
                                Map<MethodReference, Set<MethodCall>> calleeIndex,
                                Set<MethodReference> allMethods,
                                Map<String, Set<MethodReference>> methodsByName) {
        return new CallGraph(
                Collections.unmodifiableMap(callerIndex),
                Collections.unmodifiableMap(calleeIndex),
                Collections.unmodifiableSet(allMethods),
                Collections.unmodifiableMap(methodsByName)
        );
    }

    /**
     * Returns an empty call graph.
     */
    public static CallGraph empty() {
        return new CallGraph(Map.of(), Map.of(), Set.of(), Map.of());
    }

    /**
     * Returns all calls where {@code method} is the callee (who calls this method?).
     */
    public Set<MethodCall> getCallersOf(MethodReference method) {
        return callerIndex.getOrDefault(method, Set.of());
    }

    /**
     * Returns all calls where {@code method} is the caller (what does this method call?).
     */
    public Set<MethodCall> getCalleesOf(MethodReference method) {
        return calleeIndex.getOrDefault(method, Set.of());
    }

    /**
     * Returns all registered methods in the graph.
     */
    public Set<MethodReference> getAllMethods() {
        return allMethods;
    }

    /**
     * Finds all registered methods matching the given name (across all classes).
     */
    public Set<MethodReference> findMethodsByName(String methodName) {
        return methodsByName.getOrDefault(methodName, Set.of());
    }

    /**
     * Returns all method references that appear as callees in the caller index.
     * Used for fuzzy matching across interface/implementation boundaries.
     */
    public Set<MethodReference> getAllCallerIndexKeys() {
        return callerIndex.keySet();
    }

    /**
     * Returns true if no method in the graph calls this method.
     */
    public boolean isRoot(MethodReference method) {
        Set<MethodCall> callers = callerIndex.get(method);
        return callers == null || callers.isEmpty();
    }
}
