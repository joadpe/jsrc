package com.jsrc.app.command;

import java.nio.file.Path;

import com.jsrc.app.parser.model.ClassInfo;
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
