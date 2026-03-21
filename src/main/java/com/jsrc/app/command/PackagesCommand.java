package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Maps package structure with inter-package dependencies.
 */
public class PackagesCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();

        // Group classes by package
        Map<String, List<ClassInfo>> byPackage = new TreeMap<>();
        for (ClassInfo ci : allClasses) {
            String pkg = ci.packageName().isEmpty() ? "(default)" : ci.packageName();
            byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(ci);
        }

        // Analyze dependencies between packages
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : byPackage.entrySet()) {
            String pkg = entry.getKey();
            List<ClassInfo> classes = entry.getValue();
            Map<String, Integer> dependsOnCount = new LinkedHashMap<>();

            for (ClassInfo ci : classes) {
                var deps = ctx.indexed() != null
                        ? ctx.indexed().getDependencies(ci.name())
                        : ctx.dependencyAnalyzer().analyze(ctx.javaFiles(), ci.name());
                if (deps.isEmpty()) continue;
                for (String imp : deps.get().imports()) {
                    int lastDot = imp.lastIndexOf('.');
                    if (lastDot > 0) {
                        String importPkg = imp.substring(0, lastDot);
                        if (!importPkg.equals(pkg) && byPackage.containsKey(importPkg)) {
                            dependsOnCount.merge(importPkg, 1, Integer::sum);
                        }
                    }
                }
            }

            Map<String, Object> pkgInfo = new LinkedHashMap<>();
            pkgInfo.put("name", pkg);
            pkgInfo.put("classes", classes.size());
            pkgInfo.put("methods", classes.stream().mapToInt(c -> c.methods().size()).sum());
            if (!dependsOnCount.isEmpty()) {
                // Sort by import count descending
                var sorted = dependsOnCount.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .map(e -> Map.of("pkg", (Object) e.getKey(), "imports", (Object) e.getValue()))
                        .toList();
                pkgInfo.put("dependsOn", sorted);
            }
            result.add(pkgInfo);
        }

        // Detect circular dependencies (A→B AND B→A)
        List<Map<String, Object>> circularDeps = new ArrayList<>();
        Map<String, java.util.Set<String>> depGraph = new java.util.LinkedHashMap<>();
        for (var pkg : result) {
            String name = (String) pkg.get("name");
            var deps = pkg.get("dependsOn");
            if (deps instanceof List<?> depList) {
                java.util.Set<String> depNames = new java.util.LinkedHashSet<>();
                for (var d : depList) {
                    if (d instanceof Map<?, ?> dm) depNames.add((String) ((Map<String, Object>) dm).get("pkg"));
                    else if (d instanceof String s) depNames.add(s);
                }
                depGraph.put(name, depNames);
            }
        }
        // Find length-2 cycles (A↔B)
        java.util.Set<String> seenCycles = new java.util.LinkedHashSet<>();
        for (var entry : depGraph.entrySet()) {
            String a = entry.getKey();
            for (String b : entry.getValue()) {
                var bDeps = depGraph.get(b);
                if (bDeps != null && bDeps.contains(a)) {
                    String cycleKey = a.compareTo(b) < 0 ? a + "↔" + b : b + "↔" + a;
                    if (seenCycles.add(cycleKey)) {
                        circularDeps.add(Map.of("packageA", a, "packageB", b, "type", "bidirectional"));
                    }
                }
            }
        }

        if (!ctx.fullOutput() && result.size() > 30) {
            // Compact: top 30 packages by class count + summary
            // Strip import details — only name + class count
            var sorted = result.stream()
                    .sorted((a, b) -> Integer.compare(
                            ((Number) b.get("classes")).intValue(),
                            ((Number) a.get("classes")).intValue()))
                    .limit(30)
                    .map(p -> {
                        var slim = new LinkedHashMap<String, Object>();
                        slim.put("name", p.get("name"));
                        slim.put("classes", p.get("classes"));
                        slim.put("methods", p.get("methods"));
                        return slim;
                    })
                    .toList();
            var compact = new LinkedHashMap<String, Object>();
            compact.put("totalPackages", result.size());
            compact.put("totalClasses", result.stream()
                    .mapToInt(p -> ((Number) p.get("classes")).intValue()).sum());
            compact.put("packages", sorted);
            compact.put("truncated", true);
            compact.put("hint", "Use --full to see all " + result.size() + " packages");
            if (!circularDeps.isEmpty()) {
                compact.put("circularDeps", circularDeps);
            }
            ctx.formatter().printResult(compact);
        } else {
            var fullResult = new java.util.LinkedHashMap<String, Object>();
            fullResult.put("totalPackages", result.size());
            fullResult.put("packages", result);
            if (!circularDeps.isEmpty()) {
                fullResult.put("circularDeps", circularDeps);
            }
            ctx.formatter().printResult(fullResult);
        }
        return result.size();
    }
}
