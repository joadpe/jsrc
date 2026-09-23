package com.jsrc.app.accuracy;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedClass;
import com.jsrc.app.index.IndexedMethod;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.util.SignatureUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class IndexedSemanticObserver {

    private IndexedSemanticObserver() {}

    static SemanticObservation observe(Path projectRoot, List<Path> javaFiles) {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Objects.requireNonNull(javaFiles, "javaFiles must not be null");

        CodebaseIndex index = new CodebaseIndex();
        index.build(new HybridJavaParser(), javaFiles, projectRoot, List.of());

        Set<String> symbols = new HashSet<>();
        Map<MethodKey, List<String>> methods = new HashMap<>();
        for (var entry : index.getEntries()) {
            for (IndexedClass indexedClass : entry.classes()) {
                for (IndexedMethod method : indexedClass.methods()) {
                    String identity = methodIdentity(indexedClass, method);
                    symbols.add(identity);
                    methods.computeIfAbsent(
                                    new MethodKey(indexedClass.qualifiedName(), method.name(), method.paramCount()),
                                    ignored -> new ArrayList<>())
                            .add(identity);
                }
            }
        }

        Set<String> edges = new HashSet<>();
        for (var entry : index.getEntries()) {
            for (var edge : entry.callEdges()) {
                Optional<String> caller = resolve(
                        methods, edge.callerClass(), edge.callerMethod(), edge.callerParamCount());
                Optional<String> callee = resolve(
                        methods, edge.calleeClass(), edge.calleeMethod(), edge.argCount());
                if (caller.isPresent() && callee.isPresent()) {
                    edges.add(caller.get() + "->" + callee.get());
                }
            }
        }
        return new SemanticObservation(symbols, edges);
    }

    private static String methodIdentity(IndexedClass indexedClass, IndexedMethod method) {
        String parameters = SignatureUtils.extractParams(method.signature()).replace(", ", ",");
        return indexedClass.qualifiedName() + "#" + method.name() + parameters;
    }

    private static Optional<String> resolve(
            Map<MethodKey, List<String>> methods,
            String className,
            String methodName,
            int parameterCount) {
        if (parameterCount >= 0) {
            List<String> candidates = methods.entrySet().stream()
                    .filter(entry -> classMatches(entry.getKey().className(), className))
                    .filter(entry -> entry.getKey().methodName().equals(methodName))
                    .filter(entry -> entry.getKey().parameterCount() == parameterCount)
                    .flatMap(entry -> entry.getValue().stream())
                    .distinct()
                    .toList();
            return unique(candidates);
        }
        List<String> candidates = methods.entrySet().stream()
                .filter(entry -> classMatches(entry.getKey().className(), className))
                .filter(entry -> entry.getKey().methodName().equals(methodName))
                .flatMap(entry -> entry.getValue().stream())
                .distinct()
                .toList();
        return unique(candidates);
    }

    private static boolean classMatches(String canonicalClassName, String requestedClassName) {
        return canonicalClassName.equals(requestedClassName)
                || canonicalClassName.endsWith("." + requestedClassName);
    }

    private static Optional<String> unique(List<String> candidates) {
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private record MethodKey(String className, String methodName, int parameterCount) {}
}
