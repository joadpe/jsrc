package com.jsrc.app.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Centralized method target resolution for all commands.
 * Given a parsed {@link MethodResolver.MethodRef} and a {@link CallGraphBuilder},
 * resolves the set of matching {@link MethodReference} targets.
 * <p>
 * Handles: simple name, Class.method, qualified names, param types,
 * and ambiguity detection (multiple classes with same method name).
 */
public final class MethodTargetResolver {

    /**
     * Resolution result.
     *
     * @param targets   matching method references
     * @param ambiguous true if multiple classes have the same method and no class was specified
     */
    public record Result(Set<MethodReference> targets, boolean ambiguous) {
        public boolean isResolved() {
            return !targets.isEmpty();
        }

        public boolean isAmbiguous() {
            return ambiguous;
        }

        /** Distinct class names in the targets. */
        public Set<String> classNames() {
            return targets.stream()
                    .map(MethodReference::className)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private MethodTargetResolver() {}

    /**
     * Resolves method targets from the call graph.
     *
     * @param ref   parsed method reference (from MethodResolver.parse)
     * @param graph call graph with registered methods
     * @return resolution result with targets and ambiguity flag
     */
    public static Result resolve(MethodResolver.MethodRef ref, CallGraphBuilder graph) {
        Set<MethodReference> allTargets = graph.findMethodsByName(ref.methodName());

        if (allTargets.isEmpty()) {
            return new Result(Set.of(), false);
        }

        // Filter by class name if specified
        Set<MethodReference> filtered = allTargets;
        if (ref.hasClassName()) {
            filtered = allTargets.stream()
                    .filter(t -> t.className().equals(ref.className()))
                    .collect(Collectors.toSet());
        }

        // Filter by param count if specified
        if (ref.hasParamTypes()) {
            int expectedCount = ref.paramTypes().size();
            filtered = filtered.stream()
                    .filter(t -> t.parameterCount() < 0 || t.parameterCount() == expectedCount)
                    .collect(Collectors.toSet());
        }

        // Check ambiguity: multiple targets and no params specified to disambiguate
        boolean ambiguous = false;
        if (!ref.hasParamTypes() && filtered.size() > 1) {
            // Ambiguous if: multiple classes, or multiple overloads in same class
            Set<String> classes = filtered.stream()
                    .map(MethodReference::className)
                    .collect(Collectors.toSet());
            Set<Integer> paramCounts = filtered.stream()
                    .map(MethodReference::parameterCount)
                    .filter(c -> c >= 0)
                    .collect(Collectors.toSet());
            ambiguous = classes.size() > 1 || paramCounts.size() > 1;
        }

        return new Result(filtered, ambiguous);
    }

    /**
     * Builds a signature map from indexed entries.
     * Keys: "ClassName.methodName" (putIfAbsent) and "ClassName.methodName/N" (keyed by param count).
     * Values: "(Type1, Type2)" extracted from method signatures.
     */
    public static java.util.Map<String, String> buildSignatureMap(
            com.jsrc.app.index.IndexedCodebase indexed) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (indexed == null) return map;
        for (var entry : indexed.getEntries()) {
            for (var ic : entry.classes()) {
                for (var im : ic.methods()) {
                    if (im.signature() == null || im.signature().isEmpty()) continue;
                    String params = extractParams(im.signature());
                    int paramCount = countParams(params);
                    String key = ic.name() + "." + im.name();
                    map.putIfAbsent(key, params);
                    map.put(key + "/" + paramCount, params);
                }
            }
        }
        return map;
    }

    private static String extractParams(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) return "()";
        String inner = signature.substring(open + 1, close).trim();
        if (inner.isEmpty()) return "()";
        String[] parts = inner.split(",");
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            String[] tokens = part.split("\\s+");
            sb.append(tokens[0]);
            if (i < parts.length - 1) sb.append(", ");
        }
        sb.append(")");
        return sb.toString();
    }

    private static int countParams(String params) {
        if (params == null || params.equals("()")) return 0;
        String inner = params.substring(1, params.length() - 1).trim();
        if (inner.isEmpty()) return 0;
        return inner.split(",").length;
    }

    /**
     * Resolves the display signature for a MethodReference using the signature map.
     */
    public static String displayName(com.jsrc.app.parser.model.MethodReference ref,
                                      java.util.Map<String, String> signatures) {
        String key = ref.className() + "." + ref.methodName();
        String params = null;
        if (ref.parameterCount() >= 0) {
            params = signatures.get(key + "/" + ref.parameterCount());
        }
        if (params == null) {
            params = signatures.getOrDefault(key, "()");
        }
        return ref.className() + "." + ref.methodName() + params;
    }

    /**
     * Builds a list of candidate strings for ambiguity display.
     * Format: "ClassName.methodName(Type1, Type2)"
     */
    public static List<String> buildCandidates(Set<MethodReference> targets,
                                                java.util.Map<String, String> signatures) {
        return targets.stream()
                .map(t -> {
                    String key = t.className() + "." + t.methodName();
                    String params = null;
                    if (t.parameterCount() >= 0) {
                        params = signatures.get(key + "/" + t.parameterCount());
                    }
                    if (params == null) {
                        params = signatures.getOrDefault(key, "()");
                    }
                    return t.className() + "." + t.methodName() + params;
                })
                .sorted()
                .distinct()
                .toList();
    }
}
