package com.jsrc.app.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.index.IndexSymbolAdapter;
import com.jsrc.app.symbol.SymbolResolver;

/**
 * Resolves a user-provided target string (class name, method ref, file path)
 * to matching files and/or method locations. Reusable across commands.
 */
public final class TargetResolver {

    private TargetResolver() {}

    /**
     * A resolved method location with class name, method name, and line range.
     */
    public record MethodMatch(String className, String methodName, int startLine, int endLine) {}

    /**
     * Result of resolving a target string.
     */
    public record TargetResult(
            List<Path> files,
            List<MethodMatch> methodMatches,
            Set<String> matchingClasses,
            boolean ambiguous
    ) {
        public boolean isEmpty() {
            return files.isEmpty() && methodMatches.isEmpty();
        }
    }

    /**
     * Finds files matching a target by exact class name, exact file name,
     * or path ending with the target.
     */
    public static List<Path> findFileMatches(List<Path> javaFiles, String target) {
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

    /**
     * Resolves class names to their source files.
     */
    public static List<Path> resolveClassesToFiles(List<Path> javaFiles, Set<String> classNames) {
        List<Path> matches = new ArrayList<>();
        for (Path file : javaFiles) {
            String fileNameNoExt = file.getFileName().toString().replace(".java", "");
            if (classNames.contains(fileNameNoExt)) {
                matches.add(file);
            }
        }
        return matches;
    }

    public static List<Path> resolveClassesToFiles(
            List<Path> javaFiles,
            Set<String> classNames,
            IndexedCodebase indexed) {
        Set<String> indexedPaths = classNames.stream()
                .map(indexed::findFileForClass)
                .flatMap(java.util.Optional::stream)
                .map(path -> Path.of(path).normalize().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (indexedPaths.isEmpty()) {
            return resolveClassesToFiles(javaFiles, classNames);
        }
        return javaFiles.stream()
                .filter(file -> indexedPaths.stream().anyMatch(path ->
                        file.normalize().endsWith(Path.of(path))))
                .toList();
    }

    /**
     * Resolves a parsed method reference against the index to find matching
     * methods with their line ranges.
     *
     * @return result with method matches, matching classes, and ambiguity flag
     */
    public static TargetResult resolveMethodInIndex(MethodResolver.MethodRef ref,
                                                     IndexedCodebase indexed) {
        List<MethodMatch> matches = new ArrayList<>();
        Set<String> matchingClasses = new LinkedHashSet<>();
        SymbolResolver resolver = IndexSymbolAdapter.create(indexed.getEntries());
        List<SymbolResolver.MethodSymbol> resolvedMethods;
        boolean ambiguous = false;
        if (ref.hasClassName()) {
            var resolution = resolver.resolveMethod(
                    ref.className(), ref.methodName(), ref.paramTypes(),
                    SymbolResolver.Context.empty());
            if (resolution instanceof SymbolResolver.Resolution.Found<SymbolResolver.MethodSymbol> found) {
                resolvedMethods = List.of(found.value());
            } else if (resolution instanceof SymbolResolver.Resolution.Ambiguous<SymbolResolver.MethodSymbol> multiple) {
                resolvedMethods = multiple.candidates();
                ambiguous = true;
            } else {
                resolvedMethods = List.of();
            }
        } else {
            resolvedMethods = resolver.findMethods(ref.methodName(), ref.paramTypes());
        }

        for (var method : resolvedMethods) {
            String className = method.owner().canonicalName();
            matches.add(new MethodMatch(
                    className, method.name(), method.startLine(), method.endLine()));
            matchingClasses.add(className);
        }

        ambiguous = ambiguous || (matchingClasses.size() > 1 && !ref.hasClassName());
        return new TargetResult(List.of(), matches, matchingClasses, ambiguous);
    }
}
