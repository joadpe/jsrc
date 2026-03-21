package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.List;

import com.jsrc.app.parser.model.ClassInfo;

public class ClassesCommand implements Command {

    private final String packageFilter;

    public ClassesCommand(String packageFilter) {
        this.packageFilter = packageFilter;
    }

    public ClassesCommand() {
        this(null);
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();

        // Filter by package if specified
        if (packageFilter != null && !packageFilter.isEmpty() && !packageFilter.startsWith("--")) {
            allClasses = allClasses.stream()
                    .filter(ci -> ci.packageName().equals(packageFilter)
                            || ci.packageName().startsWith(packageFilter + "."))
                    .toList();
        }

        // Compact mode (default): show count + first 50 classes
        if (!ctx.fullOutput() && allClasses.size() > 50) {
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("total", allClasses.size());
            if (packageFilter != null) compact.put("package", packageFilter);
            // Rank by caller count (most referenced = most important)
            var graph = ctx.callGraph();
            compact.put("classes", allClasses.stream()
                    .sorted((a, b) -> {
                        long callersA = graph.findMethodsByName(a.name()).stream()
                                .mapToLong(r -> graph.getCallersOf(r).size()).sum();
                        long callersB = graph.findMethodsByName(b.name()).stream()
                                .mapToLong(r -> graph.getCallersOf(r).size()).sum();
                        return Long.compare(callersB, callersA);
                    })
                    .limit(50)
                    .map(ci -> ci.qualifiedName().isEmpty() ? ci.name() : ci.qualifiedName())
                    .toList());
            compact.put("truncated", true);
            compact.put("hint", "Use --full to see all classes");
            ctx.formatter().printResult(compact);
        } else {
            // Small result or --full: show all
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("total", allClasses.size());
            if (packageFilter != null) compact.put("package", packageFilter);
            compact.put("classes", allClasses.stream()
                    .map(ci -> {
                        var m = new java.util.LinkedHashMap<String, Object>();
                        m.put("name", ci.name());
                        m.put("packageName", ci.packageName());
                        m.put("methods", ci.methods().size());
                        m.put("isInterface", ci.isInterface());
                        return m;
                    })
                    .toList());
            ctx.formatter().printResult(compact);
        }
        return allClasses.size();
    }
}
