package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.index.IndexedMethod;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.SignatureUtils;

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
 * When a method target is specified, only smells within that specific method
 * (matched by name and line range) are returned. This correctly handles
 * overloaded methods with different parameter counts.
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
        int totalSmells = 0;
        for (Path file : ctx.javaFiles()) {
            var smells = ctx.parser().detectSmells(file);
            totalSmells += smells.size();
            ctx.formatter().printSmells(smells, file);
        }
        return totalSmells;
    }

    /**
     * Resolved method match: class name, method name, and optional line range
     * for filtering smells to the exact overload.
     */
    private record MethodMatch(String className, String methodName, int startLine, int endLine) {}

    private int scanTarget(CommandContext ctx) {
        var ref = MethodResolver.parse(target);
        boolean isMethodRef = ref.hasParamTypes() || ref.hasClassName();

        // 1. Try direct file/class match first (skip for method refs with parens)
        if (!isMethodRef) {
            List<Path> directMatches = findFileMatches(ctx.javaFiles(), target);
            if (!directMatches.isEmpty()) {
                return scanFiles(ctx, directMatches, null);
            }
        }

        // 2. Try as method reference via index
        if (ctx.indexed() != null) {
            // Search index entries directly for matching methods
            List<MethodMatch> matches = new ArrayList<>();
            Set<String> matchingClasses = new LinkedHashSet<>();

            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (var im : ic.methods()) {
                        if (!im.name().equals(ref.methodName())) continue;
                        if (ref.hasClassName() && !ic.name().equals(ref.className())) continue;
                        if (ref.hasParamTypes()) {
                            int paramCount = SignatureUtils.countParams(im.signature());
                            if (paramCount >= 0 && paramCount != ref.paramTypes().size()) continue;
                        }
                        matches.add(new MethodMatch(ic.name(), im.name(), im.startLine(), im.endLine()));
                        matchingClasses.add(ic.name());
                    }
                }
            }

            // Check ambiguity: multiple classes, no class specified
            if (matchingClasses.size() > 1 && !ref.hasClassName()) {
                return reportAmbiguity(ctx, ref, matchingClasses);
            }

            // Resolve class names to files and scan with line-range filter
            if (!matches.isEmpty()) {
                List<Path> fileMatches = resolveClassesToFiles(ctx.javaFiles(), matchingClasses);
                if (!fileMatches.isEmpty()) {
                    return scanFilesWithLineFilter(ctx, fileMatches, matches);
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
                                Set<String> matchingClasses) {
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

    /**
     * Scans files for smells, filtering to a specific method name only.
     * Used when target is a class name (no line-range info).
     */
    private int scanFiles(CommandContext ctx, List<Path> files, String methodFilter) {
        int totalSmells = 0;
        for (Path file : files) {
            var smells = ctx.parser().detectSmells(file);
            if (methodFilter != null) {
                smells = smells.stream()
                        .filter(s -> methodFilter.equals(s.methodName()))
                        .toList();
            }
            totalSmells += smells.size();
            ctx.formatter().printSmells(smells, file);
        }
        return totalSmells;
    }

    /**
     * Scans files for smells, filtering by method name AND line range.
     * This correctly handles overloaded methods — only smells within the
     * specific overload's line range are included.
     */
    private int scanFilesWithLineFilter(CommandContext ctx, List<Path> files,
                                         List<MethodMatch> matches) {
        int totalSmells = 0;
        for (Path file : files) {
            var smells = ctx.parser().detectSmells(file);
            String fileNameNoExt = file.getFileName().toString().replace(".java", "");

            // Find matching methods in this file
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

    private List<Path> resolveClassesToFiles(List<Path> javaFiles, Set<String> classNames) {
        List<Path> matches = new ArrayList<>();
        for (Path file : javaFiles) {
            String fileNameNoExt = file.getFileName().toString().replace(".java", "");
            if (classNames.contains(fileNameNoExt)) {
                matches.add(file);
            }
        }
        return matches;
    }

    /**
     * Finds files matching a target by exact class name, exact file name,
     * or path ending with the target.
     */
    private List<Path> findFileMatches(List<Path> javaFiles, String target) {
        String cleanTarget = target.endsWith(".java")
                ? target.substring(0, target.length() - 5) : target;

        List<Path> matches = new ArrayList<>();
        for (Path file : javaFiles) {
            String fileName = file.getFileName().toString();
            String fileNameNoExt = fileName.replace(".java", "");

            if (fileNameNoExt.equals(cleanTarget)
                    || fileName.equals(target)
                    || file.toString().endsWith(target)) {
                matches.add(file);
            }
        }
        return matches;
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
