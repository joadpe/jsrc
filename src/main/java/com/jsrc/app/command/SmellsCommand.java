package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.SignatureUtils;
import com.jsrc.app.util.TargetResolver;
import com.jsrc.app.util.TargetResolver.MethodMatch;

/**
 * Detects code smells in Java source files.
 * <p>
 * Accepts a target that can be:
 * <ul>
 *   <li>{@code --all} — scan the entire codebase</li>
 *   <li>A class name — {@code OrderService}</li>
 *   <li>A method name — {@code creaDocumento}</li>
 *   <li>A qualified reference — {@code Service.process(String, int)}</li>
 *   <li>A file path fragment — {@code src/main/Service.java}</li>
 * </ul>
 * Target resolution is delegated to {@link TargetResolver}.
 */
public class SmellsCommand implements Command {

    private final String target;

    public SmellsCommand(String target) {
        this.target = target;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (target == null) {
            printUsage();
            return 0;
        }

        if ("--all".equals(target)) {
            return scanAll(ctx);
        }

        return scanTarget(ctx);
    }

    private int scanAll(CommandContext ctx) {
        // Use cached smells from index when available (instant vs re-parsing all files)
        if (ctx.indexed() != null && ctx.indexed().hasCachedSmells()) {
            return scanAllFromIndex(ctx);
        }

        // No cache — detect smells and cache them in the index for next time
        int totalSmells = 0;
        boolean shouldPersist = ctx.indexed() != null;
        for (Path file : ctx.javaFiles()) {
            var smells = ctx.parser().detectSmells(file);
            totalSmells += smells.size();
            ctx.formatter().printSmells(smells, file);

            // Cache smells in index entry for future instant access
            if (shouldPersist && !smells.isEmpty()) {
                String relativePath = java.nio.file.Path.of(ctx.rootPath()).relativize(file).toString();
                var cached = smells.stream()
                        .map(s -> new com.jsrc.app.index.CachedSmell(
                                s.ruleId(), s.severity().name().substring(0, 1),
                                s.line(), s.methodName(), s.className(), s.message()))
                        .toList();
                ctx.indexed().setCachedSmells(relativePath, cached);
            }
        }

        // Persist updated index with cached smells
        if (shouldPersist) {
            ctx.indexed().save(java.nio.file.Path.of(ctx.rootPath()));
        }

        return totalSmells;
    }

    private int scanAllFromIndex(CommandContext ctx) {
        int totalSmells = 0;
        for (var entry : ctx.indexed().getEntries()) {
            var cachedSmells = ctx.indexed().getCachedSmells(entry.path());
            if (cachedSmells.isEmpty()) continue;
            // Convert CachedSmell to CodeSmell for formatter
            var smells = cachedSmells.stream()
                    .map(cs -> new com.jsrc.app.parser.model.CodeSmell(
                            cs.ruleId(),
                            com.jsrc.app.parser.model.CodeSmell.Severity.valueOf(
                                    switch (cs.severity()) {
                                        case "W" -> "WARNING";
                                        case "E" -> "ERROR";
                                        default -> "INFO";
                                    }),
                            cs.message(), cs.line(), cs.method(), cs.className()))
                    .toList();
            totalSmells += smells.size();
            ctx.formatter().printSmells(smells, java.nio.file.Path.of(entry.path()));
        }
        return totalSmells;
    }

    private int scanTarget(CommandContext ctx) {
        var ref = MethodResolver.parse(target);
        boolean isMethodRef = ref.hasParamTypes() || ref.hasClassName();

        // 1. Try direct file/class match first (skip for method refs with parens)
        if (!isMethodRef) {
            List<Path> directMatches = TargetResolver.findFileMatches(ctx.javaFiles(), target);
            if (!directMatches.isEmpty()) {
                return scanFiles(ctx, directMatches);
            }
        }

        // 2. Try as method reference via index
        if (ctx.indexed() != null) {
            var result = TargetResolver.resolveMethodInIndex(ref, ctx.indexed());

            if (result.ambiguous()) {
                return reportAmbiguity(ctx, ref, result.matchingClasses());
            }

            if (!result.methodMatches().isEmpty()) {
                List<Path> fileMatches = TargetResolver.resolveClassesToFiles(
                        ctx.javaFiles(), result.matchingClasses());
                if (!fileMatches.isEmpty()) {
                    return scanFilesWithLineFilter(ctx, fileMatches, result.methodMatches());
                }
            }
        }

        if (ctx.indexed() == null && isMethodRef) {
            System.err.printf("No index found. Run 'jsrc --index' first to search by method name.%n");
        } else {
            System.err.printf("No files matching '%s' found in codebase%n", target);
        }
        return 0;
    }

    private int reportAmbiguity(CommandContext ctx, MethodResolver.MethodRef ref,
                                java.util.Set<String> matchingClasses) {
        List<String> candidates = new ArrayList<>();
        for (var entry : ctx.indexed().getEntries()) {
            for (var ic : entry.classes()) {
                if (!matchingClasses.contains(ic.name())) continue;
                for (var im : ic.methods()) {
                    if (!im.name().equals(ref.methodName())) continue;
                    String pkg = ic.packageName();
                    String qualified = pkg.isEmpty() ? ic.name() : pkg + "." + ic.name();
                    String params = SignatureUtils.extractParams(im.signature());
                    candidates.add(qualified + "." + im.name() + params);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ambiguous", true);
        result.put("target", target);
        result.put("candidates", candidates.stream().sorted().distinct().toList());
        result.put("message",
                "Multiple classes contain this method. Use Class.method to disambiguate.");
        ctx.formatter().printResult(result);
        return 0;
    }

    private int scanFiles(CommandContext ctx, List<Path> files) {
        int totalSmells = 0;
        var allSmells = new java.util.ArrayList<com.jsrc.app.parser.model.CodeSmell>();
        for (Path file : files) {
            var smells = ctx.parser().detectSmells(file);
            totalSmells += smells.size();
            allSmells.addAll(smells);
            if (!ctx.mdOutput()) ctx.formatter().printSmells(smells, file);
        }
        if (ctx.mdOutput() && !allSmells.isEmpty()) {
            var sb = new StringBuilder();
            sb.append("# Code Smell Report\n\n");

            // Build method signature lookup from index
            var signatures = new java.util.HashMap<String, String>();
            if (ctx.indexed() != null) {
                for (var entry : ctx.indexed().getEntries()) {
                    for (var ic : entry.classes()) {
                        for (var im : ic.methods()) {
                            signatures.put(ic.qualifiedName() + "." + im.name(), im.signature());
                        }
                    }
                }
            }

            // Group by class → method
            var byClass = new java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, java.util.List<com.jsrc.app.parser.model.CodeSmell>>>();
            for (var s : allSmells) {
                String qualifiedClass = s.className();
                // Try to find qualified name from index
                if (ctx.indexed() != null) {
                    var fileForClass = ctx.indexed().findFileForClass(s.className());
                    if (fileForClass.isPresent()) {
                        var classInfo = ctx.indexed().getAllClasses().stream()
                                .filter(c -> c.name().equals(s.className()))
                                .findFirst();
                        if (classInfo.isPresent()) qualifiedClass = classInfo.get().qualifiedName();
                    }
                }
                byClass.computeIfAbsent(qualifiedClass, k -> new java.util.LinkedHashMap<>())
                        .computeIfAbsent(s.methodName(), k -> new java.util.ArrayList<>())
                        .add(s);
            }

            for (var classEntry : byClass.entrySet()) {
                sb.append("## ").append(classEntry.getKey()).append("\n\n");
                for (var methodEntry : classEntry.getValue().entrySet()) {
                    String methodName = methodEntry.getKey();
                    String sig = signatures.getOrDefault(classEntry.getKey() + "." + methodName, methodName + "()");
                    sb.append("### `").append(sig).append("`\n\n");
                    for (var smell : methodEntry.getValue()) {
                        String icon = "WARNING".equals(smell.severity().name()) ? "⚠️" : "ℹ️";
                        sb.append("- ").append(icon).append(" **").append(smell.ruleId()).append("** — ").append(smell.message()).append("\n");
                    }
                    sb.append("\n");
                }
            }

            sb.append("**Total: ").append(totalSmells).append(" smell(s)**\n");
            com.jsrc.app.output.MarkdownWriter.output(sb.toString(), ctx.outDir(), "smells-report");
        }
        return totalSmells;
    }

    private int scanFilesWithLineFilter(CommandContext ctx, List<Path> files,
                                         List<MethodMatch> matches) {
        int totalSmells = 0;
        for (Path file : files) {
            var smells = ctx.parser().detectSmells(file);
            String fileNameNoExt = file.getFileName().toString().replace(".java", "");

            List<MethodMatch> fileMatches = matches.stream()
                    .filter(m -> m.className().equals(fileNameNoExt))
                    .toList();

            if (!fileMatches.isEmpty()) {
                smells = smells.stream()
                        .filter(s -> fileMatches.stream().anyMatch(m ->
                                m.methodName().equals(s.methodName())
                                && s.line() >= m.startLine()
                                && s.line() <= m.endLine()))
                        .toList();
            }

            totalSmells += smells.size();
            ctx.formatter().printSmells(smells, file);
        }
        return totalSmells;
    }

    private void printUsage() {
        System.err.println("Usage: jsrc --smells <target>");
        System.err.println();
        System.err.println("Targets:");
        System.err.println("  <ClassName>              Scan a specific class");
        System.err.println("  <Class.method>           Scan class containing method");
        System.err.println("  <method(Type1,Type2)>    Disambiguate by signature");
        System.err.println("  <file-path>              Scan files matching path");
        System.err.println("  --all                    Scan the entire codebase");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  jsrc --smells OrderService");
        System.err.println("  jsrc --smells creaDocumento");
        System.err.println("  jsrc --smells GeneracionDocumentos.creaDocumento");
        System.err.println("  jsrc --smells --all --json");
    }
}
