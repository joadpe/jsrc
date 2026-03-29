package com.jsrc.app.command.navigate;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.model.CommandHint;


public class DepsCommand implements Command {
    private final String className;

    public DepsCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        com.jsrc.app.model.DependencyResult deps = null;

        // Fast path: use index if available
        if (ctx.indexed() != null) {
            var indexed = ctx.indexed().getDependencies(className);
            if (indexed.isPresent()) deps = indexed.get();
        }
        // Fallback: parse on-the-fly
        if (deps == null) {
            var result = ctx.dependencyAnalyzer().analyze(ctx.javaFiles(), className);
            if (result.isPresent()) deps = result.get();
        }

        if (deps == null) {
            System.err.printf("Class '%s' not found.%n", className);
            return 0;
        }

        if (!ctx.fullOutput()) {
            // Compact: filter out java.*/javax.* imports, show only types
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("class", deps.className());
            compact.put("imports", deps.imports().stream()
                    .filter(i -> !i.startsWith("java.") && !i.startsWith("javax.")
                            && !i.startsWith("jakarta.") && !i.startsWith("org.slf4j.")
                            && !i.startsWith("org.apache.commons."))
                    .toList());
            compact.put("fieldTypes", deps.fieldDependencies().stream()
                    .map(com.jsrc.app.model.DependencyResult.FieldDep::type)
                    .distinct().toList());
            compact.put("constructorParamTypes", deps.constructorDependencies().stream()
                    .map(com.jsrc.app.model.DependencyResult.FieldDep::type)
                    .distinct().toList());

            var hints = java.util.List.of(
                new CommandHint("read " + className, "Read a dependency"),
                new CommandHint("related " + className, "Find coupled classes"),
                new CommandHint("imports " + className, "Who imports this class?")
            );

            ctx.formatter().printResultWithHints(compact, hints);
        } else {
            ctx.formatter().printDependencies(deps);
        }
        return 1;
    }
}
