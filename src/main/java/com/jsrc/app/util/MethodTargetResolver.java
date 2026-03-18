package com.jsrc.app.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.jsrc.app.analysis.CallGraph;
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
        return resolve(ref, graph.toCallGraph());
    }

    /**
     * Resolves method targets from an immutable call graph.
     */
    public static Result resolve(MethodResolver.MethodRef ref, CallGraph graph) {
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
                    String params = SignatureUtils.extractParams(im.signature());
                    int paramCount = SignatureUtils.countParams(im.signature());
                    String key = ic.name() + "." + im.name();
                    map.putIfAbsent(key, params);
                    map.put(key + "/" + paramCount, params);
                }
            }
        }
        return map;
    }

    /**
     * Resolves the display signature for a MethodReference using the signature map.
     * Uses simple class name: "Service.process(String, int)"
     */
    public static String displayName(com.jsrc.app.parser.model.MethodReference ref,
                                      java.util.Map<String, String> signatures) {
        String key = ref.className() + "." + ref.methodName();
        String params = resolveParams(ref, signatures);
        return ref.className() + "." + ref.methodName() + params;
    }

    /**
     * Resolves the fully qualified display name including package.
     * E.g. "com.agbar.occam.facturacion.Service.process(String, int)"
     */
    public static String qualifiedDisplayName(com.jsrc.app.parser.model.MethodReference ref,
                                               java.util.Map<String, String> signatures,
                                               java.util.Map<String, String> classPackages) {
        return qualifiedDisplayName(ref, signatures, classPackages, null);
    }

    /**
     * Resolves the fully qualified display name including package.
     * When a methodPackages map is provided and multiple classes share the same
     * simple name, uses the method-level package map for accurate disambiguation.
     */
    public static String qualifiedDisplayName(com.jsrc.app.parser.model.MethodReference ref,
                                               java.util.Map<String, String> signatures,
                                               java.util.Map<String, String> classPackages,
                                               java.util.Map<String, String> methodPackages) {
        String params = resolveParams(ref, signatures);
        // Try method-level package first (more specific)
        String pkg = null;
        if (methodPackages != null) {
            pkg = methodPackages.get(ref.className() + "." + ref.methodName());
        }
        if (pkg == null) {
            pkg = classPackages.getOrDefault(ref.className(), "");
        }
        String qualifiedClass = pkg.isEmpty() ? ref.className() : pkg + "." + ref.className();
        return qualifiedClass + "." + ref.methodName() + params;
    }

    private static String resolveParams(com.jsrc.app.parser.model.MethodReference ref,
                                         java.util.Map<String, String> signatures) {
        String key = ref.className() + "." + ref.methodName();
        String params = null;
        if (ref.parameterCount() >= 0) {
            params = signatures.get(key + "/" + ref.parameterCount());
        }
        if (params == null) {
            params = signatures.getOrDefault(key, "()");
        }
        return params;
    }

    /**
     * Builds a map of simple class name → package name from indexed entries.
     * When multiple classes share the same simple name, stores all packages.
     * Use {@link #resolvePackage} to pick the right one for display.
     */
    public static java.util.Map<String, String> buildClassPackageMap(
            com.jsrc.app.index.IndexedCodebase indexed) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (indexed == null) return map;
        for (var entry : indexed.getEntries()) {
            for (var ic : entry.classes()) {
                map.putIfAbsent(ic.name(), ic.packageName());
            }
        }
        return map;
    }

    /**
     * Builds a map of "ClassName.methodName" → package for accurate disambiguation.
     * Each method is keyed to the package of the class that declares it.
     */
    public static java.util.Map<String, String> buildMethodPackageMap(
            com.jsrc.app.index.IndexedCodebase indexed) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (indexed == null) return map;
        for (var entry : indexed.getEntries()) {
            for (var ic : entry.classes()) {
                for (var im : ic.methods()) {
                    // Key: "ClassName.methodName" → package
                    map.putIfAbsent(ic.name() + "." + im.name(), ic.packageName());
                }
            }
        }
        return map;
    }

    /**
     * Builds a list of candidate strings for ambiguity display.
     * Format: "ClassName.methodName(Type1, Type2)"
     */
    public static List<String> buildCandidates(Set<MethodReference> targets,
                                                java.util.Map<String, String> signatures) {
        return buildCandidates(targets, signatures, java.util.Map.of());
    }

    public static List<String> buildCandidates(Set<MethodReference> targets,
                                                java.util.Map<String, String> signatures,
                                                java.util.Map<String, String> classPackages) {
        return targets.stream()
                .map(t -> qualifiedDisplayName(t, signatures, classPackages))
                .sorted()
                .distinct()
                .toList();
    }
}
