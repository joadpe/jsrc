package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.List;

import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.util.MethodResolver;

public class MethodSearchCommand implements Command {
    private final String methodInput;

    public MethodSearchCommand(String methodInput) {
        this.methodInput = methodInput;
    }

    @Override
    public int execute(CommandContext ctx) {
        var ref = MethodResolver.parse(methodInput);
        String methodName = ref.methodName();

        int totalFound = 0;
        for (Path file : ctx.javaFiles()) {
            List<MethodInfo> methods;
            if (ref.hasParamTypes()) {
                methods = ctx.parser().findMethods(file, methodName, ref.paramTypes());
            } else {
                methods = ctx.parser().findMethods(file, methodName);
            }
            if (ref.hasClassName()) {
                methods = methods.stream()
                        .filter(m -> m.className().equals(ref.className()))
                        .toList();
            }
            if (!methods.isEmpty()) {
                totalFound += methods.size();
                ctx.formatter().printMethods(methods, file, methodName);
            }
        }
        return totalFound;
    }
}
