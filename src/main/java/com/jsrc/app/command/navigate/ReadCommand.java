package com.jsrc.app.command.navigate;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.model.CommandHint;
import com.jsrc.app.model.HintContext;

import java.nio.file.Path;
import java.util.ArrayList;
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

        // Heuristic: FQCN class read vs Class.method dispatch
        // If no parens and last segment starts uppercase → treat as class FQCN
        // e.g. "com.example.MyClass" → class read, not "example.MyClass" method
        // e.g. "MyClass.run" or "com.example.MyClass.run" → method read (lowercase)
        boolean isFqcnClass = !target.contains("(") && target.contains(".") 
                && Character.isUpperCase(target.charAt(target.lastIndexOf('.') + 1));

        if (isFqcnClass) {
            // Treat as class FQCN regardless of MethodResolver parse
            Path classFile = findFileForClass(ctx, target);
            
            // FQCN fast-fail: if target looks like FQCN and index missed, don't full-scan
            if (classFile == null) {
                System.err.printf("'%s' not found.%n", target);
                return 0;
            }
            
            result = reader.readClass(List.of(classFile), target).orElse(null);
        } else if (ref.hasClassName()) {
            result = findMethodRead(ctx, reader, ref);
        } else if (target.contains("(")) {
            result = findMethodReadAllFiles(ctx, ref);
        } else {
            // Simple name class read
            Path classFile = findFileForClass(ctx, target);
            
            // FQCN fast-fail: if target looks like FQCN and index missed, don't full-scan
            if (classFile == null && target.contains(".")) {
                System.err.printf("'%s' not found.%n", target);
                return 0;
            }
            
            List<Path> classSearch = classFile != null ? List.of(classFile) : ctx.javaFiles();
            result = reader.readClass(classSearch, target).orElse(null);
            if (result == null && !target.contains(".")) {
                // Only try method fallback for simple names, not FQCN
                result = findMethodReadAllFiles(ctx, ref);
            }
        }

        if (result != null) {
            boolean isClassRead = isFqcnClass || (!ref.hasClassName() && !target.contains("("));
            if (isClassRead) {
                printClassRead(ctx, result);
            } else {
                printMethodRead(ctx, result);
            }
            return 1;
        }
        System.err.printf("'%s' not found.%n", target);
        return 0;
    }

    private void printClassRead(CommandContext ctx, SourceReader.ReadResult result) {
        List<String> methodNames = extractMethodNames(ctx, result);
        var hintCtx = HintContext.forClass(result.className(), methodNames);

        // Build hints per command-hints-map.md
        var hints = new ArrayList<CommandHint>();
        hints.add(CommandHint.resolve("read {class}.METHOD",
                "Read a specific method (see methods list)", hintCtx));
        hints.add(CommandHint.resolve("mini {class}", "Compact class summary", hintCtx));
        hints.add(CommandHint.resolve("smells {class}", "Check for code smells", hintCtx));
        hints.add(CommandHint.resolve("deps {class}", "See dependencies", hintCtx));

        if (!ctx.fullOutput() && result.content() != null
                && result.content().length() > 25000) {
            // Truncated large class
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("class", result.className());
            compact.put("file", result.file().toString());
            compact.put("lines", result.startLine() + "-" + result.endLine());
            compact.put("chars", result.content().length());
            if (!methodNames.isEmpty()) {
                compact.put("methods", methodNames);
            }
            String[] lines = result.content().split("\n");
            int previewLines = Math.min(100, lines.length);
            compact.put("preview", String.join("\n",
                    java.util.Arrays.copyOf(lines, previewLines)));
            compact.put("truncated", lines.length > previewLines);
            ctx.formatter().printResultWithHints(compact, hints);
        } else {
            // Full class output
            var enriched = new java.util.LinkedHashMap<String, Object>();
            enriched.put("class", result.className());
            enriched.put("file", result.file().toString());
            enriched.put("line", result.startLine());
            if (!methodNames.isEmpty()) {
                enriched.put("methods", methodNames);
            }
            enriched.put("content", result.content());
            ctx.formatter().printResultWithHints(enriched, hints);
        }
    }

    private void printMethodRead(CommandContext ctx, SourceReader.ReadResult result) {
        List<String> siblings = extractSiblingMethods(ctx, result);
        var hintCtx = HintContext.forMethod(result.className(), result.methodName(), siblings);

        // Build hints per command-hints-map.md
        var hints = new ArrayList<CommandHint>();
        hints.add(CommandHint.resolve("callers {method}", "Who calls this method?", hintCtx));
        hints.add(CommandHint.resolve("impact {method}", "Change risk assessment", hintCtx));
        hints.add(CommandHint.resolve("test-for {class}.{method}",
                "Find tests for this method", hintCtx));
        hints.add(CommandHint.resolve("callees {method}",
                "What does this method call?", hintCtx));
        if (!siblings.isEmpty()) {
            hints.add(CommandHint.resolve("read {class}.METHOD",
                    "Read a sibling method (see siblingMethods)", hintCtx));
        }

        var enriched = new java.util.LinkedHashMap<String, Object>();
        enriched.put("class", result.className());
        enriched.put("method", result.methodName());
        enriched.put("file", result.file().toString());
        enriched.put("line", result.startLine());
        enriched.put("content", result.content());
        if (!siblings.isEmpty()) {
            enriched.put("siblingMethods", siblings);
        }
        ctx.formatter().printResultWithHints(enriched, hints);
    }

    private SourceReader.ReadResult findMethodRead(CommandContext ctx, SourceReader reader,
                                                    MethodResolver.MethodRef ref) {
        Path targetFile = findFileForClass(ctx, ref.className());
        
        // FQCN fast-fail: if className looks like FQCN and index missed, don't full-scan
        if (targetFile == null && ref.className().contains(".")) {
            return null;
        }
        
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
        // Index stores relative paths; javaFiles may be absolute
        // Try multiple matching strategies
        for (Path f : files) {
            String fStr = f.toString();
            // Exact match
            if (fStr.equals(indexPath)) return f;
            // Absolute vs relative: file ends with index path
            if (fStr.endsWith(indexPath)) return f;
            // Normalize and compare (handle ./ prefixes, etc)
            if (fStr.endsWith("/" + indexPath)) return f;
            // Index path might have leading ./ or ./src/, strip and retry
            String normalizedIndex = indexPath.startsWith("./") ? indexPath.substring(2) : indexPath;
            if (fStr.endsWith(normalizedIndex) || fStr.endsWith("/" + normalizedIndex)) return f;
        }
        return null;
    }

    private static Path findFileForClass(CommandContext ctx, String className) {
        if (ctx.indexed() != null) {
            // Try as qualified name first
            var path = ctx.indexed().findFileForClass(className);
            if (path.isPresent()) {
                return findFileByPath(ctx.javaFiles(), path.get());
            }
            
            // If className looks like FQCN (contains '.'), fail fast
            // instead of falling through to full scan
            if (className.contains(".")) {
                return null;
            }
        }
        
        // Fallback: try simple name match on filename
        for (Path f : ctx.javaFiles()) {
            if (f.getFileName().toString().equals(className + ".java")) return f;
        }
        return null;
    }
}
