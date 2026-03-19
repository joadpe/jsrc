package com.jsrc.app.command;


public class DepsCommand implements Command {
    private final String className;

    public DepsCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        // Fast path: use index if available
        if (ctx.indexed() != null) {
            var indexed = ctx.indexed().getDependencies(className);
            if (indexed.isPresent()) {
                ctx.formatter().printDependencies(indexed.get());
                return 1;
            }
        }
        // Fallback: parse on-the-fly
        var result = ctx.dependencyAnalyzer().analyze(ctx.javaFiles(), className);
        if (result.isPresent()) {
            ctx.formatter().printDependencies(result.get());
            return 1;
        }
        System.err.printf("Class '%s' not found.%n", className);
        return 0;
    }
}
