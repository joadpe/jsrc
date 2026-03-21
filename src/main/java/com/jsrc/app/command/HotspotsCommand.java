package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalClasses", allClasses.size());
        result.put("classesWithCallers", hotspots.size());
        result.put("top", top);
        ctx.formatter().printResult(result);
        return top.size();
    }
}
