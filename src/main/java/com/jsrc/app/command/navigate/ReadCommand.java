package com.jsrc.app.command.navigate;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

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
            if (isClassRead) {
                // Extract method list so agents know what to drill into
                List<String> methodNames = extractMethodNames(ctx, result);

                // Only truncate truly large classes (>750 LOC / ~25000 chars)
                // Smaller classes are shown in full — agents need source for reasoning
                if (!ctx.fullOutput() && result.content() != null
                        && result.content().length() > 25000) {
                    var compact = new java.util.LinkedHashMap<String, Object>();
                    compact.put("class", result.className());
                    compact.put("file", result.file().toString());
                    compact.put("lines", result.startLine() + "-" + result.endLine());
                    compact.put("chars", result.content().length());
                    if (!methodNames.isEmpty()) {
                        compact.put("methods", methodNames);
                    }
                    compact.put("hint", "Large class. Use 'read " + result.className()
                            + ".methodName' for specific methods, or --full for complete source.");
                    String[] lines = result.content().split("\n");
                    int previewLines = Math.min(100, lines.length);
                    compact.put("preview", String.join("\n", java.util.Arrays.copyOf(lines, previewLines)));
                    compact.put("truncated", lines.length > previewLines);
                    ctx.formatter().printResult(compact);
                } else {
                    // Full class output — still include method names for discoverability
                    var enriched = new java.util.LinkedHashMap<String, Object>();
                    enriched.put("class", result.className());
                    enriched.put("file", result.file().toString());
                    enriched.put("line", result.startLine());
                    if (!methodNames.isEmpty()) {
                        enriched.put("methods", methodNames);
                        enriched.put("hint", "Use 'read " + result.className()
                                + ".methodName' to read individual methods.");
                    }
                    enriched.put("content", result.content());
                    ctx.formatter().printResult(enriched);
                }
            } else {
                // Method read — include sibling methods for navigation
                List<String> siblings = extractSiblingMethods(ctx, result);
                if (!siblings.isEmpty()) {
                    var enriched = new java.util.LinkedHashMap<String, Object>();
                    enriched.put("class", result.className());
                    enriched.put("method", result.methodName());
                    enriched.put("file", result.file().toString());
                    enriched.put("line", result.startLine());
                    enriched.put("content", result.content());
                    enriched.put("siblingMethods", siblings);
                    enriched.put("hint", "Use 'read " + result.className()
                            + ".methodName' to read other methods in this class.");
                    ctx.formatter().printResult(enriched);
                } else {
                    ctx.formatter().printReadResult(result);
                }
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

    /**
     * Extracts sibling method names (other methods in the same class, excluding the current one).
     * Helps agents navigate to related methods without reading the whole file.
     */
    private List<String> extractSiblingMethods(CommandContext ctx, SourceReader.ReadResult result) {
        try {
            Path file = result.file();
            if (file == null || result.className() == null) return List.of();
            List<MethodInfo> allMethods = ctx.parser().findAllMethods(file);
            return allMethods.stream()
                    .filter(m -> m.className().equals(result.className()))
                    .map(MethodInfo::name)
                    .filter(name -> !name.equals(result.methodName()))
                    .distinct()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Extracts method names from the class for discoverability.
     * Agents can use these to drill into specific methods with 'read Class.method'.
     */
    private List<String> extractMethodNames(CommandContext ctx, SourceReader.ReadResult result) {
        try {
            Path file = result.file();
            if (file == null) return List.of();
            List<MethodInfo> allMethods = ctx.parser().findAllMethods(file);
            return allMethods.stream()
                    .filter(m -> m.className().equals(result.className()))
                    .map(MethodInfo::name)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
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
