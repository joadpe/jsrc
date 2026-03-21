package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Lists the most referenced classes in the codebase, ranked by caller count.
 * Helps identify architectural hotspots and high-coupling classes.
 */
public class HotspotsCommand implements Command {

    private static final int TOP_N = 30;

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        CallGraph graph = ctx.callGraph();

        List<Map<String, Object>> hotspots = new ArrayList<>();
        for (ClassInfo ci : allClasses) {
            long callerCount = 0;
            for (var m : ci.methods()) {
                var refs = graph.findMethodsByName(m.name());
                for (var ref : refs) {
                    if (ref.className().equals(ci.name())) {
                        callerCount += graph.getCallersOf(ref).size();
                    }
                }
            }

            if (callerCount > 0) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("class", ci.qualifiedName());
                entry.put("callers", callerCount);
                entry.put("methods", ci.methods().size());
                entry.put("isInterface", ci.isInterface());
                hotspots.add(entry);
            }
        }

        hotspots.sort(Comparator.<Map<String, Object>, Long>comparing(
                m -> ((Number) m.get("callers")).longValue()).reversed());

        List<Map<String, Object>> top = hotspots.subList(0, Math.min(TOP_N, hotspots.size()));

        // Most imported types (from index imports)
        Map<String, Integer> importCounts = new LinkedHashMap<>();
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (String imp : ic.imports()) {
                        int lastDot = imp.lastIndexOf('.');
                        String simpleName = lastDot >= 0 ? imp.substring(lastDot + 1) : imp;
                        if (!simpleName.equals("*")) {
                            importCounts.merge(simpleName, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        List<Map<String, Object>> topImported = importCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_N)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", e.getKey());
                    m.put("importedBy", e.getValue());
                    return m;
                })
                .toList();

        // Test coverage estimate: production classes with/without matching test class
        Set<String> testClassNames = new java.util.HashSet<>();
        Set<String> testTargets = new java.util.HashSet<>();
        Set<String> prodClassNames = new java.util.HashSet<>();
        for (ClassInfo ci : allClasses) {
            String name = ci.name();
            if (name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("IT")) {
                testClassNames.add(name);
                for (String suffix : new String[]{"Tests", "Test", "IT"}) {
                    if (name.endsWith(suffix)) {
                        testTargets.add(name.substring(0, name.length() - suffix.length()));
                        break;
                    }
                }
            } else {
                prodClassNames.add(name);
            }
        }
        int testedCount = 0;
        for (String p : prodClassNames) {
            if (testTargets.contains(p)) testedCount++;
        }

        // Untested hotspots: high-caller production classes without matching test
        List<Map<String, Object>> untestedHotspots = hotspots.stream()
                .filter(h -> {
                    String fqn = (String) h.get("class");
                    String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                    return !testTargets.contains(simple) && !simple.endsWith("Test")
                            && !simple.endsWith("Tests") && !simple.endsWith("IT");
                })
                .limit(10)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalClasses", allClasses.size());
        result.put("classesWithCallers", hotspots.size());
        result.put("byCallers", top);
        result.put("byImports", topImported);
        result.put("testCoverage", Map.of(
                "testClasses", testClassNames.size(),
                "productionClasses", prodClassNames.size(),
                "testedByNaming", testedCount,
                "estimatedCoverage", prodClassNames.isEmpty() ? 0
                        : testedCount * 100 / prodClassNames.size()));
        if (!untestedHotspots.isEmpty()) {
            result.put("untestedHotspots", untestedHotspots);
        }
        ctx.formatter().printResult(result);
        return top.size();
    }
}
