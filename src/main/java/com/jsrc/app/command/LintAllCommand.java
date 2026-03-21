package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans the entire indexed codebase for structural anti-patterns:
 * - Mutable static fields (static non-final)
 * - God classes (>500 LOC or >30 methods)
 * - Methods with >5 parameters
 * - Empty/untyped fields
 *
 * Uses index data only — no re-parsing needed.
 */
public class LintAllCommand implements Command {

    @Override
    public int execute(CommandContext ctx) {
        if (ctx.indexed() == null) {
            ctx.formatter().printResult(Map.of("error", "No index. Run --index first."));
            return 0;
        }

        List<Map<String, Object>> mutableStatics = new ArrayList<>();
        List<Map<String, Object>> godClasses = new ArrayList<>();
        List<Map<String, Object>> highParamMethods = new ArrayList<>();
        List<Map<String, Object>> primitiveObsession = new ArrayList<>();

        // Lazy: only build call graph if we have findings to enrich
        com.jsrc.app.analysis.CallGraph graph = null;

        for (var entry : ctx.indexed().getEntries()) {
            for (var ic : entry.classes()) {
                // Skip test classes
                String name = ic.name();
                if (name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("IT")) continue;

                // Mutable statics
                for (var f : ic.fields()) {
                    if (f.modifiers().contains("static") && !f.modifiers().contains("final")) {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("class", ic.qualifiedName());
                        finding.put("field", f.name());
                        finding.put("type", f.type());
                        finding.put("modifiers", f.modifiers());
                        finding.put("file", entry.path());
                        finding.put("issue", "Mutable static field — not thread-safe without synchronization");
                        mutableStatics.add(finding);
                    }
                }

                // God classes: >500 LOC or >30 methods
                int loc = ic.endLine() - ic.startLine();
                if (loc > 500 || ic.methods().size() > 30) {
                    Map<String, Object> finding = new LinkedHashMap<>();
                    finding.put("class", ic.qualifiedName());
                    finding.put("loc", loc);
                    finding.put("methods", ic.methods().size());
                    finding.put("file", entry.path());
                    finding.put("issue", loc > 500
                            ? "God class: " + loc + " LOC (threshold: 500)"
                            : "God class: " + ic.methods().size() + " methods (threshold: 30)");
                    godClasses.add(finding);
                }

                // High-param methods (>5)
                for (var m : ic.methods()) {
                    if (m.paramCount() > 5) {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("class", ic.qualifiedName());
                        finding.put("method", m.name());
                        finding.put("params", m.paramCount());
                        finding.put("signature", m.signature());
                        finding.put("issue", "Too many parameters: " + m.paramCount() + " (threshold: 5)");
                        highParamMethods.add(finding);
                    }

                    // Primitive obsession: 3+ String params in same method
                    if (m.signature() != null) {
                        long stringCount = 0;
                        String sig = m.signature();
                        int idx = sig.indexOf('(');
                        if (idx >= 0) {
                            String params = sig.substring(idx);
                            for (int si = 0; si < params.length() - 5; si++) {
                                if (params.startsWith("String", si)) stringCount++;
                            }
                        }
                        if (stringCount >= 3) {
                            Map<String, Object> finding = new LinkedHashMap<>();
                            finding.put("class", ic.qualifiedName());
                            finding.put("method", m.name());
                            finding.put("stringParams", stringCount);
                            finding.put("signature", m.signature());
                            finding.put("issue", "Primitive obsession: " + stringCount + " String parameters — consider a value object");
                            primitiveObsession.add(finding);
                        }
                    }
                }
            }
        }

        // Enrich findings with caller counts for prioritization
        if (!godClasses.isEmpty() || !highParamMethods.isEmpty() || !mutableStatics.isEmpty()) {
            graph = ctx.callGraph();
            // God classes + mutable statics: callers by class
            for (var findings : List.of(godClasses, mutableStatics)) {
                for (var f : findings) {
                    String className = ((String) f.get("class"));
                    if (className.contains(".")) className = className.substring(className.lastIndexOf('.') + 1);
                    long callers = 0;
                    for (var ref : graph.findMethodsByName(className)) {
                        callers += graph.getCallersOf(ref).size();
                    }
                    // Also count all method callers for the class
                    String cn = className;
                    for (var ref : graph.getAllMethods()) {
                        if (ref.className().equals(cn)) {
                            callers += graph.getCallersOf(ref).size();
                        }
                    }
                    f.put("callers", callers);
                }
            }
            // High-param methods: callers by specific method
            for (var f : highParamMethods) {
                String methodName = (String) f.get("method");
                String className = ((String) f.get("class"));
                if (className.contains(".")) className = className.substring(className.lastIndexOf('.') + 1);
                long callers = 0;
                String cn = className;
                for (var ref : graph.findMethodsByName(methodName)) {
                    if (ref.className().equals(cn)) {
                        callers += graph.getCallersOf(ref).size();
                    }
                }
                f.put("callers", callers);
            }
        }

        // Sort by severity
        godClasses.sort(Comparator.<Map<String, Object>, Integer>comparing(
                m -> ((Number) m.get("loc")).intValue()).reversed());
        highParamMethods.sort(Comparator.<Map<String, Object>, Integer>comparing(
                m -> ((Number) m.get("params")).intValue()).reversed());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mutableStatics", Map.of(
                "count", mutableStatics.size(),
                "findings", mutableStatics.size() > 20
                        ? mutableStatics.subList(0, 20) : mutableStatics));
        result.put("godClasses", Map.of(
                "count", godClasses.size(),
                "findings", godClasses.size() > 20
                        ? godClasses.subList(0, 20) : godClasses));
        result.put("highParamMethods", Map.of(
                "count", highParamMethods.size(),
                "findings", highParamMethods.size() > 20
                        ? highParamMethods.subList(0, 20) : highParamMethods));

        primitiveObsession.sort(Comparator.<Map<String, Object>, Long>comparing(
                m -> ((Number) m.get("stringParams")).longValue()).reversed());
        result.put("primitiveObsession", Map.of(
                "count", primitiveObsession.size(),
                "findings", primitiveObsession.size() > 20
                        ? primitiveObsession.subList(0, 20) : primitiveObsession));

        int total = mutableStatics.size() + godClasses.size() + highParamMethods.size() + primitiveObsession.size();
        result.put("totalIssues", total);
        result.put("summary", Map.of(
                "mutableStatics", mutableStatics.size(),
                "godClasses", godClasses.size(),
                "highParamMethods", highParamMethods.size(),
                "primitiveObsession", primitiveObsession.size()));

        ctx.formatter().printResult(result);
        return total;
    }
}
