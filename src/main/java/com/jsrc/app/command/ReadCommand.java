package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.List;

import com.jsrc.app.parser.SourceReader;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.util.MethodResolver;

public class ReadCommand implements Command {
    private final String target;

    public ReadCommand(String target) {
        this.target = target;
    }

    @Override
    public int execute(CommandContext ctx) {
        var reader = new SourceReader(ctx.parser());
        var ref = MethodResolver.parse(target);
        SourceReader.ReadResult result;

        if (ref.hasClassName()) {
            result = findMethodRead(ctx, reader, ref);
        } else if (target.contains("(")) {
            result = findMethodReadAllFiles(ctx, ref);
        } else {
            result = reader.readClass(ctx.javaFiles(), target);
            if (result == null) {
                result = findMethodReadAllFiles(ctx, ref);
            }
        }

        if (result != null) {
            ctx.formatter().printReadResult(result);
            return 1;
        }
        System.err.printf("'%s' not found.%n", target);
        return 0;
    }

    private SourceReader.ReadResult findMethodRead(CommandContext ctx, SourceReader reader,
                                                    MethodResolver.MethodRef ref) {
        var result = reader.readMethod(ctx.javaFiles(), ref.className(), ref.methodName());
        if (result != null && ref.hasParamTypes()) {
            Path file = findFileForClass(ctx.javaFiles(), ref.className());
            if (file != null) {
                var methods = MethodResolver.filter(
                        ctx.parser().findMethods(file, ref.methodName()), ref);
                if (!methods.isEmpty()) {
                    MethodInfo m = methods.getFirst();
                    return new SourceReader.ReadResult(
                            m.className(), m.name(), file,
                            m.startLine(), m.endLine(), m.content());
                }
                return null;
            }
        }
        return result;
    }

    private SourceReader.ReadResult findMethodReadAllFiles(CommandContext ctx,
                                                            MethodResolver.MethodRef ref) {
        for (Path file : ctx.javaFiles()) {
            List<MethodInfo> methods = ctx.parser().findMethods(file, ref.methodName());
            methods = MethodResolver.filter(methods, ref);
            if (!methods.isEmpty()) {
                MethodInfo m = methods.getFirst();
                return new SourceReader.ReadResult(
                        m.className(), m.name(), file,
                        m.startLine(), m.endLine(), m.content());
            }
        }
        return null;
    }

    private static Path findFileForClass(List<Path> files, String className) {
        for (Path f : files) {
            if (f.getFileName().toString().equals(className + ".java")) return f;
        }
        return null;
    }
}
