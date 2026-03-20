package com.jsrc.app.command;

import java.nio.file.Path;

import java.util.Comparator;
import java.util.List;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.util.ClassResolver;

public class SummaryCommand implements Command {
    private final String className;

    public SummaryCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo ci = resolveOrExit(allClasses, className);
        if (ci == null) return 0;

        // Compact mode (default): limit to top 20 methods sorted by caller count
        if (!ctx.fullOutput() && ci.methods().size() > 20) {
            var graph = ctx.callGraph();
            String cn = ci.name();
            List<MethodInfo> trimmed = ci.methods().stream()
                    .sorted(Comparator.<MethodInfo, Integer>comparing(m ->
                            graph.findMethodsByName(m.name()).stream()
                                    .filter(r -> r.className().equals(cn))
                                    .mapToInt(r -> graph.getCallersOf(r).size())
                                    .sum())
                            .reversed()
                            .thenComparing(MethodInfo::name))
                    .limit(20)
                    .toList();
            ci = ci.withMethods(trimmed);
        }

        String filePath = ctx.indexed() != null
                ? ctx.indexed().findFileForClass(ci.name()).orElse("") : "";
        ctx.formatter().printClassSummary(ci, Path.of(filePath));
        return 1;
    }

    static ClassInfo resolveOrExit(java.util.List<ClassInfo> allClasses, String className) {
        var resolution = ClassResolver.resolve(allClasses, className);
        return switch (resolution) {
            case ClassResolver.Resolution.Found found -> found.classInfo();
            case ClassResolver.Resolution.Ambiguous ambiguous -> {
                ClassResolver.printAmbiguous(ambiguous.candidates(), className);
                yield null;
            }
            case ClassResolver.Resolution.NotFound n -> {
                System.err.printf("Class '%s' not found.%n", className);
                yield null;
            }
        };
    }
}
