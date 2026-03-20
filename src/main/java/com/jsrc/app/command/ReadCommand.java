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
        var reader = ctx.sourceReader();
        var ref = MethodResolver.parse(target);
        SourceReader.ReadResult result = null;

        if (ref.hasClassName()) {
            result = findMethodRead(ctx, reader, ref);
        } else if (target.contains("(")) {
            result = findMethodReadAllFiles(ctx, ref);
        } else {
            // Fast path: locate file via index for class read
            Path classFile = findFileForClass(ctx, target);
            List<Path> classSearch = classFile != null ? List.of(classFile) : ctx.javaFiles();
            result = reader.readClass(classSearch, target).orElse(null);
            if (result == null) {
                result = findMethodReadAllFiles(ctx, ref);
            }
        }

        if (result != null) {
            // Compact mode: if content is large and it's a full-class read, truncate
            boolean isClassRead = !ref.hasClassName() && !target.contains("(");
            if (!ctx.fullOutput() && isClassRead && result.content() != null
                    && result.content().length() > 5000) {
                var compact = new java.util.LinkedHashMap<String, Object>();
                compact.put("class", result.className());
                compact.put("file", result.file().toString());
                compact.put("lines", result.startLine() + "-" + result.endLine());
                compact.put("chars", result.content().length());
                compact.put("hint", "Large class. Use --read Class.method for specific methods, or --full for complete source.");
                String[] lines = result.content().split("\n");
                int previewLines = Math.min(100, lines.length);
                compact.put("preview", String.join("\n", java.util.Arrays.copyOf(lines, previewLines)));
                compact.put("truncated", lines.length > previewLines);
                ctx.formatter().printResult(compact);
            } else {
                ctx.formatter().printReadResult(result);
            }
            return 1;
        }
        System.err.printf("'%s' not found.%n", target);
        return 0;
    }

    private SourceReader.ReadResult findMethodRead(CommandContext ctx, SourceReader reader,
                                                    MethodResolver.MethodRef ref) {
        // Fast path: locate file via index, then parse only that file
        Path targetFile = findFileForClass(ctx, ref.className());
        List<Path> searchFiles = targetFile != null ? List.of(targetFile) : ctx.javaFiles();

        if (ref.hasParamTypes() && targetFile != null) {
            var methods = MethodResolver.filter(
                    ctx.parser().findMethods(targetFile, ref.methodName()), ref);
            if (!methods.isEmpty()) {
                MethodInfo m = methods.getFirst();
                return new SourceReader.ReadResult(
                        m.className(), m.name(), targetFile,
                        m.startLine(), m.endLine(), m.content());
            }
            return null;
        }

        return reader.readMethod(searchFiles, ref.className(), ref.methodName()).orElse(null);
    }

    private SourceReader.ReadResult findMethodReadAllFiles(CommandContext ctx,
                                                            MethodResolver.MethodRef ref) {
        // Fast path: use index to find which file contains the method
        if (ctx.indexed() != null) {
            var indexed = ctx.indexed().findMethodsByName(ref.methodName());
            for (MethodInfo im : indexed) {
                var filePathOpt = ctx.indexed().findFileForClass(im.className());
                if (filePathOpt.isEmpty()) continue;
                String filePath = filePathOpt.get();
                Path file = findFileByPath(ctx.javaFiles(), filePath);
                if (file == null) continue;
                List<MethodInfo> methods = ctx.parser().findMethods(file, ref.methodName());
                methods = MethodResolver.filter(methods, ref);
                if (!methods.isEmpty()) {
                    MethodInfo m = methods.getFirst();
                    return new SourceReader.ReadResult(
                            m.className(), m.name(), file,
                            m.startLine(), m.endLine(), m.content());
                }
            }
        }

        // Fallback: scan all files
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

    private static Path findFileByPath(List<Path> files, String indexPath) {
        for (Path f : files) {
            if (f.toString().endsWith(indexPath) || indexPath.endsWith(f.toString())) return f;
        }
        return null;
    }

    private static Path findFileForClass(CommandContext ctx, String className) {
        // Try index first
        if (ctx.indexed() != null) {
            var path = ctx.indexed().findFileForClass(className);
            if (path.isPresent()) {
                return findFileByPath(ctx.javaFiles(), path.get());
            }
        }
        // Fallback: match by filename
        for (Path f : ctx.javaFiles()) {
            if (f.getFileName().toString().equals(className + ".java")) return f;
        }
        return null;
    }
}
