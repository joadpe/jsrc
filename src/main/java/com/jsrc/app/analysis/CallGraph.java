package com.jsrc.app.analysis;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;

import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Immutable directed call graph. Query-only — no mutation after construction.
 * <p>
 * Produced by {@link CallGraphBuilder}, consumed by commands and analyzers.
 * <p>
 * Every set it returns iterates in one defined order, however the graph was built or
 * loaded: methods by class name, method name, parameter types and parameter count; calls
 * by caller, line, callee, invocation kind, resolution level and evidence. The calls a
 * method makes therefore come by line, and the calls to a method by caller. Hash order
 * would differ between runs, as {@link MethodCall} hashes its enum components by identity.
 */
public final class CallGraph {

    private static final Comparator<MethodReference> METHOD_ORDER = CallGraph::compareMethods;
    private static final Comparator<MethodCall> CALL_ORDER = CallGraph::compareCalls;

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
     * Creates a CallGraph from copies of the given indexes, in the defined order.
     * Duplicate calls or methods count once.
     */
    public static CallGraph of(Map<MethodReference, ? extends Collection<MethodCall>> callerIndex,
                                Map<MethodReference, ? extends Collection<MethodCall>> calleeIndex,
                                Collection<MethodReference> allMethods,
                                Map<String, ? extends Collection<MethodReference>> methodsByName) {
        // getAllCallerIndexKeys iterates the caller index; the other maps are only looked up.
        List<MethodReference> called = new ArrayList<>(callerIndex.keySet());
        called.sort(METHOD_ORDER);
        Map<MethodReference, Set<MethodCall>> byCallee =
                LinkedHashMap.newLinkedHashMap(called.size());
        for (MethodReference callee : called) {
            byCallee.put(callee, callSet(callerIndex.get(callee)));
        }
        Map<MethodReference, Set<MethodCall>> byCaller = HashMap.newHashMap(calleeIndex.size());
        calleeIndex.forEach((caller, calls) -> byCaller.put(caller, callSet(calls)));
        Map<String, Set<MethodReference>> byName = HashMap.newHashMap(methodsByName.size());
        methodsByName.forEach((name, methods) -> byName.put(name, methodSet(methods)));
        return new CallGraph(
                Collections.unmodifiableMap(byCallee),
                Collections.unmodifiableMap(byCaller),
                methodSet(allMethods),
                Collections.unmodifiableMap(byName)
        );
    }

    /**
     * Returns an empty call graph.
     */
    public static CallGraph empty() {
        return new CallGraph(Map.of(), Map.of(), Set.of(), Map.of());
    }

    /**
     * Returns all calls where {@code method} is the callee (who calls this method?),
     * by caller and then line.
     */
    public Set<MethodCall> getCallersOf(MethodReference method) {
        return callerIndex.getOrDefault(method, Set.of());
    }

    /**
     * Returns all calls where {@code method} is the caller (what does this method call?),
     * by line and then callee.
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

    private static Set<MethodCall> callSet(Collection<MethodCall> calls) {
        return new OrderedSet<>(calls, MethodCall.class, CALL_ORDER);
    }

    private static Set<MethodReference> methodSet(Collection<MethodReference> methods) {
        return new OrderedSet<>(methods, MethodReference.class, METHOD_ORDER);
    }

    private static int compareMethods(MethodReference left, MethodReference right) {
        if (left == right) return 0;
        int order = compareText(left.className(), right.className());
        if (order == 0) order = compareText(left.methodName(), right.methodName());
        if (order == 0) order = compareTexts(left.parameterTypes(), right.parameterTypes());
        if (order == 0) order = Integer.compare(left.parameterCount(), right.parameterCount());
        return order;
    }

    private static int compareCalls(MethodCall left, MethodCall right) {
        int order = compareMethods(left.caller(), right.caller());
        if (order == 0) order = Integer.compare(left.line(), right.line());
        if (order == 0) order = compareMethods(left.callee(), right.callee());
        if (order == 0) order = left.invocationKind().compareTo(right.invocationKind());
        if (order == 0) order = left.resolutionLevel().compareTo(right.resolutionLevel());
        if (order == 0) order = compareTexts(left.evidence(), right.evidence());
        return order;
    }

    private static int compareTexts(List<String> left, List<String> right) {
        if (left == right) return 0;
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int order = compareText(left.get(index), right.get(index));
            if (order != 0) return order;
        }
        return Integer.compare(left.size(), right.size());
    }

    private static int compareText(String left, String right) {
        return left == right ? 0 : left.compareTo(right);
    }

    /**
     * An immutable set of distinct elements kept sorted by an order consistent with
     * {@code equals}, which also answers {@link #contains}. Smaller than a hash set.
     */
    private static final class OrderedSet<E> extends AbstractSet<E> {
        private final List<E> elements;
        private final Class<E> type;
        private final Comparator<? super E> order;

        OrderedSet(Collection<E> values, Class<E> type, Comparator<? super E> order) {
            // A graph read from the index arrives in this order already.
            this.elements = ascending(values, order)
                    ? List.copyOf(values) : sortedDistinct(values, order);
            this.type = type;
            this.order = order;
        }

        private static <E> boolean ascending(Collection<E> values, Comparator<? super E> order) {
            Iterator<E> iterator = values.iterator();
            if (!iterator.hasNext()) return true;
            E previous = iterator.next();
            while (iterator.hasNext()) {
                E next = iterator.next();
                if (order.compare(previous, next) >= 0) return false;
                previous = next;
            }
            return true;
        }

        private static <E> List<E> sortedDistinct(
                Collection<E> values, Comparator<? super E> order) {
            List<E> sorted = new ArrayList<>(values);
            sorted.sort(order);
            int distinct = 0;
            for (int index = 0; index < sorted.size(); index++) {
                E element = sorted.get(index);
                if (distinct == 0 || order.compare(sorted.get(distinct - 1), element) != 0) {
                    sorted.set(distinct++, element);
                }
            }
            return List.copyOf(sorted.subList(0, distinct));
        }

        @Override
        public Iterator<E> iterator() {
            return elements.iterator();
        }

        @Override
        public Spliterator<E> spliterator() {
            return Spliterators.spliterator(elements, Spliterator.ORDERED
                    | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.IMMUTABLE);
        }

        @Override
        public int size() {
            return elements.size();
        }

        @Override
        public boolean contains(Object value) {
            return type.isInstance(value)
                    && Collections.binarySearch(elements, type.cast(value), order) >= 0;
        }
    }
}
