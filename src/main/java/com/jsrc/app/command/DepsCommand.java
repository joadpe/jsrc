package com.jsrc.app.command;


public class DepsCommand implements Command {
    private final String className;

    public DepsCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var analyzer = ctx.dependencyAnalyzer();
        var result = analyzer.analyze(ctx.javaFiles(), className);
        if (result.isPresent()) {
            ctx.formatter().printDependencies(result.get());
            return 1;
        }
        System.err.printf("Class '%s' not found.%n", className);
        return 0;
    }
}
