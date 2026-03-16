package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.MethodTargetResolver;

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
 * When multiple classes contain the same method name, returns an ambiguity
 * response for the caller to disambiguate.
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

    private int scanTarget(CommandContext ctx) {
        // 1. Try direct file match first (class name or file path)
        List<Path> directMatches = findFileMatches(ctx.javaFiles(), target);
        if (!directMatches.isEmpty()) {
            return scanFiles(ctx, directMatches);
        }

        // 2. Try as method reference — resolve via call graph for class discovery
        if (ctx.indexed() != null) {
            var ref = MethodResolver.parse(target);
            var signatures = MethodTargetResolver.buildSignatureMap(ctx.indexed());
            var packages = MethodTargetResolver.buildClassPackageMap(ctx.indexed());

            // Find classes containing this method from the index
            Set<String> matchingClasses = new LinkedHashSet<>();
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (var im : ic.methods()) {
                        if (im.name().equals(ref.methodName())) {
                            if (ref.hasClassName() && !ic.name().equals(ref.className())) {
                                continue;
                            }
                            if (ref.hasParamTypes()) {
                                int paramCount = countParamsInSignature(im.signature());
                                if (paramCount >= 0 && paramCount != ref.paramTypes().size()) {
                                    continue;
                                }
                            }
                            matchingClasses.add(ic.name());
                        }
                    }
                }
            }

            if (matchingClasses.size() > 1 && !ref.hasClassName()) {
                // Ambiguous — build candidate list
                List<String> candidates = new ArrayList<>();
                for (var entry : ctx.indexed().getEntries()) {
                    for (var ic : entry.classes()) {
                        if (matchingClasses.contains(ic.name())) {
                            for (var im : ic.methods()) {
                                if (im.name().equals(ref.methodName())) {
                                    String pkg = ic.packageName();
                                    String qualified = pkg.isEmpty()
                                            ? ic.name() : pkg + "." + ic.name();
                                    String params = extractParams(im.signature());
                                    candidates.add(qualified + "." + im.name() + params);
                                }
                            }
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

            // Resolve class names to files
            if (!matchingClasses.isEmpty()) {
                List<Path> fileMatches = new ArrayList<>();
                for (Path file : ctx.javaFiles()) {
                    String fileName = file.getFileName().toString().replace(".java", "");
                    if (matchingClasses.contains(fileName)) {
                        fileMatches.add(file);
                    }
                }
                if (!fileMatches.isEmpty()) {
                    return scanFiles(ctx, fileMatches);
                }
            }
        }

        System.err.printf("No files matching '%s' found in codebase%n", target);
        return 0;
    }

    private int scanFiles(CommandContext ctx, List<Path> files) {
        int totalSmells = 0;
        for (Path file : files) {
            var smells = ctx.parser().detectSmells(file);
            totalSmells += smells.size();
            ctx.formatter().printSmells(smells, file);
        }
        return totalSmells;
    }

    /**
     * Finds files matching a target by class name, file name, or path fragment.
     */
    private List<Path> findFileMatches(List<Path> javaFiles, String target) {
        // Strip .java extension from target for comparison
        String cleanTarget = target.endsWith(".java")
                ? target.substring(0, target.length() - 5) : target;

        List<Path> matches = new ArrayList<>();
        for (Path file : javaFiles) {
            String fileName = file.getFileName().toString();
            String fileNameNoExt = fileName.replace(".java", "");

            if (fileNameNoExt.equals(cleanTarget)
                    || fileName.equals(target)
                    || file.toString().contains(target)) {
                matches.add(file);
            }
        }
        return matches;
    }

    private static int countParamsInSignature(String signature) {
        if (signature == null || signature.isEmpty()) return -1;
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open) return -1;
        String inner = signature.substring(open + 1, close).trim();
        if (inner.isEmpty()) return 0;
        int depth = 0;
        int count = 1;
        for (char c : inner.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    private static String extractParams(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) return "()";
        return signature.substring(open, close + 1);
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
