package com.jsrc.app.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.model.TypeId;
import com.jsrc.app.symbol.SymbolResolver;

/**
 * Resolves a class name to a unique ClassInfo.
 * When multiple classes share the same simple name, returns candidates
 * for disambiguation.
 */
public final class ClassResolver {

    /**
     * Result of class resolution.
     */
    public sealed interface Resolution {
        record Found(ClassInfo classInfo) implements Resolution {}
        record Ambiguous(List<String> candidates) implements Resolution {}
        record NotFound(String className) implements Resolution {}
    }

    private ClassResolver() {}

    /**
     * Resolves a class by simple or qualified name.
     *
     * @param allClasses all known classes
     * @param className  simple or qualified class name
     * @return Found, Ambiguous, or NotFound
     */
    public static Resolution resolve(List<ClassInfo> allClasses, String className) {
        Map<String, ClassInfo> classesById = new LinkedHashMap<>();
        List<SymbolResolver.TypeSymbol> symbols = allClasses.stream()
                .map(classInfo -> {
                    TypeId id = TypeId.from(classInfo.packageName(), classInfo.name());
                    classesById.put(id.canonicalName(), classInfo);
                    List<String> superTypes = new java.util.ArrayList<>();
                    if (!classInfo.superClass().isEmpty()) {
                        superTypes.add(classInfo.superClass());
                    }
                    superTypes.addAll(classInfo.interfaces());
                    return new SymbolResolver.TypeSymbol(
                            id, List.of(), superTypes, List.of());
                })
                .toList();
        var resolved = new SymbolResolver(symbols)
                .resolveType(className, SymbolResolver.Context.empty());
        return switch (resolved) {
            case SymbolResolver.Resolution.Found<SymbolResolver.TypeSymbol> found ->
                    new Resolution.Found(classesById.get(found.value().id().canonicalName()));
            case SymbolResolver.Resolution.Ambiguous<SymbolResolver.TypeSymbol> ambiguous ->
                    new Resolution.Ambiguous(ambiguous.suggestions());
            case SymbolResolver.Resolution.Unresolved<SymbolResolver.TypeSymbol> ignored ->
                    new Resolution.NotFound(className);
        };
    }

    /**
     * Prints ambiguous result as JSON to stdout and returns exit indication.
     */
    public static void printAmbiguous(List<String> candidates, String className) {
        System.out.println(JsonWriter.toJson(ambiguousResult(candidates, className)));
    }

    /** Builds the structured ambiguous-resolution result. */
    public static Map<String, Object> ambiguousResult(
            List<String> candidates, String className) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ambiguous", true);
        result.put("class", className);
        result.put("candidates", candidates);
        result.put("suggestions", candidates);
        result.put("message", "Multiple classes named '" + className
                + "'. Use qualified name to disambiguate.");
        return result;
    }
}
