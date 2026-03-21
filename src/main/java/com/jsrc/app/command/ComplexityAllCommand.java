package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Scans the entire codebase and returns the top methods by estimated complexity.
 * Supports --no-test to exclude test classes.
 */
public class ComplexityAllCommand implements Command {

    private static final int TOP_N = 30;

    @Override
    public int execute(CommandContext ctx) {
        List<Map<String, Object>> allMethods = new ArrayList<>();

        // Use indexed data when available (precomputed complexity + paramCount)
        if (ctx.indexed() != null) {
            for (var indexEntry : ctx.indexed().getEntries()) {
                for (var ic : indexEntry.classes()) {
                    for (var im : ic.methods()) {
                        int complexity = im.complexity() > 0 ? im.complexity()
                                : Math.max(1, (im.endLine() - im.startLine() + 1) / 5);
                        if (complexity <= 0) continue;

                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("class", ic.qualifiedName());
                        entry.put("method", im.name());
                        entry.put("loc", im.endLine() - im.startLine() + 1);
                        entry.put("complexity", complexity);
                        entry.put("signature", im.signature());
                        allMethods.add(entry);
                    }
                }
            }
        } else {
            for (ClassInfo ci : ctx.getAllClasses()) {
                for (MethodInfo m : ci.methods()) {
                    int loc = m.endLine() - m.startLine() + 1;
                    if (loc <= 0) continue;
                    int complexity = Math.max(1, loc / 5);

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("class", ci.qualifiedName());
                    entry.put("method", m.name());
                    entry.put("loc", loc);
                    entry.put("complexity", complexity);
                    entry.put("signature", m.signature());
                    allMethods.add(entry);
                }
            }
        }

        // Sort by LOC descending (real method length), then by complexity
        allMethods.sort(Comparator.<Map<String, Object>, Integer>comparing(
                m -> ((Number) m.get("loc")).intValue()).reversed()
                .thenComparing(Comparator.<Map<String, Object>, Integer>comparing(
                        m -> ((Number) m.get("complexity")).intValue()).reversed()));

        List<Map<String, Object>> top = allMethods.subList(0, Math.min(TOP_N, allMethods.size()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMethodsAnalyzed", allMethods.size());
        result.put("top", top);

        ctx.formatter().printResult(result);
        return top.size();
    }
}
