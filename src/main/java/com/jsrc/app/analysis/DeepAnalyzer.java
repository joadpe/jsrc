package com.jsrc.app.analysis;

import java.util.*;

import com.jsrc.app.analysis.PatternDetector.PatternDef;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Recursively searches callee chains for performance/security anti-patterns.
 * Uses the pre-resolved CallGraph for navigation, reads source only for detection.
 */
public class DeepAnalyzer {

    /**
     * Recursively search callee chain for patterns up to maxDepth.
     * Returns list of findings with type + path.
     * Each pattern type reported at most once per chain (shortest path).
     */
    public static List<Map<String, String>> findDeepPatterns(
            String callName, ClassInfo ci, CommandContext ctx,
            String currentClassSource, int currentDepth, int maxDepth,
            Set<String> visited, List<PatternDef> patterns) {

        List<Map<String, String>> results = new ArrayList<>();
        if (currentDepth > maxDepth) return results;
        if (visited.contains(callName)) return results;
        visited.add(callName);

        String calleeMethod = callName.contains(".") ? callName.substring(callName.lastIndexOf('.') + 1) : callName;
        String calleeClass = callName.contains(".") ? callName.substring(0, callName.lastIndexOf('.')) : null;

        // Resolve actual class name
        String resolvedClass = null;
        if ("this".equals(calleeClass)) {
            resolvedClass = ci.name();
        } else if (calleeClass != null) {
            resolvedClass = SourceResolver.resolveFieldType(calleeClass, ci);
        }

        // Get callee source for pattern detection
        String calleeSource = null;
        if ("this".equals(calleeClass) && currentClassSource != null) {
            calleeSource = SourceResolver.extractMethodByName(currentClassSource, calleeMethod);
        } else if (resolvedClass != null) {
            calleeSource = SourceResolver.loadMethodSource(resolvedClass, calleeMethod, ctx);
        }

        Set<String> foundTypes = new HashSet<>();

        // Check if callee class is a known DAO class
        if (resolvedClass != null && !foundTypes.contains("DB_QUERY")) {
            if (ClassResolver.isDaoClass(resolvedClass, ctx)) {
                results.add(Map.of("type", "DB_QUERY", "path", calleeMethod + " → DAO class (" + resolvedClass + ")"));
                foundTypes.add("DB_QUERY");
            }
        }

        // Detect patterns in callee source
        if (calleeSource != null) {
            for (String line : calleeSource.split("\n")) {
                String trimmed = line.trim();
                for (PatternDef pattern : patterns) {
                    if (pattern.detector().test(trimmed) && !foundTypes.contains(pattern.id())) {
                        results.add(Map.of("type", pattern.id(), "path",
                                calleeMethod + " → " + pattern.id().toLowerCase()));
                        foundTypes.add(pattern.id());
                    }
                }
            }

            // Check linear scan pattern
            if (!foundTypes.contains("LINEAR_SCAN") && PatternDetector.hasLinearScanPattern(calleeSource)) {
                results.add(Map.of("type", "LINEAR_SCAN", "path", calleeMethod + " → linear scan"));
                foundTypes.add("LINEAR_SCAN");
            }
        }

        // Navigate to next level using CallGraph
        CallGraph graph = ctx.callGraph();
        if (resolvedClass != null) {
            Set<MethodReference> refs = graph.findMethodsByName(calleeMethod);
            for (MethodReference ref : refs) {
                if (ref.className().equals(resolvedClass) || ref.className().equals(ci.name())) {
                    for (var call : graph.getCalleesOf(ref)) {
                        String nextCall = call.callee().className() + "." + call.callee().methodName();
                        var deepResults = findDeepPatterns(nextCall, ci, ctx, currentClassSource,
                                currentDepth + 1, maxDepth, visited, patterns);
                        for (var dr : deepResults) {
                            if (!foundTypes.contains(dr.get("type"))) {
                                results.add(Map.of("type", dr.get("type"),
                                        "path", calleeMethod + " → " + dr.get("path")));
                                foundTypes.add(dr.get("type"));
                            }
                        }
                    }
                    break;
                }
            }
        }

        return results;
    }
}
