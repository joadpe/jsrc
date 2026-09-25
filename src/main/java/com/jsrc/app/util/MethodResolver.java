package com.jsrc.app.util;

import java.util.Arrays;
import java.util.List;

import com.jsrc.app.parser.model.MethodInfo;

/**
 * Parses method references with optional parameter types for disambiguation.
 * <p>
 * Formats:
 * <ul>
 *   <li>{@code process} — matches all overloads</li>
 *   <li>{@code process(int)} — matches by param types</li>
 *   <li>{@code process(int,String)} — multiple params</li>
 *   <li>{@code Class.process} — class + method</li>
 *   <li>{@code Class.process(int)} — class + method + params</li>
 * </ul>
 */
public final class MethodResolver {

    /**
     * Parsed method reference.
     */
    public record MethodRef(
            String className,
            String methodName,
            List<String> paramTypes
    ) {
        public boolean hasClassName() {
            return className != null && !className.isEmpty();
        }

        /**
         * True if param types were specified (including empty parens for zero-arg methods).
         * null = no parens specified, List.of() = explicitly zero params.
         */
        public boolean hasParamTypes() {
            return paramTypes != null;
        }
    }

    private MethodResolver() {}

    /**
     * Parses a method reference string.
     *
     * @param input e.g. "process", "process(int)", "Service.process(int,String)"
     * @return parsed reference
     */
    public static MethodRef parse(String input) {
        String className = null;
        String methodPart = input;

        // Extract param types if present
        List<String> paramTypes = null;
        int parenStart = methodPart.indexOf('(');
        if (parenStart >= 0) {
            int parenEnd = methodPart.lastIndexOf(')');
            if (parenEnd > parenStart + 1) {
                String paramsStr = methodPart.substring(parenStart + 1, parenEnd);
                paramTypes = SignatureUtils.parseParameterTypes(paramsStr);
            } else {
                // process() → explicitly 0 params
                paramTypes = List.of();
            }
            methodPart = methodPart.substring(0, parenStart);
        }

        // Extract class name if present (last dot before method)
        int lastDot = methodPart.lastIndexOf('.');
        if (lastDot >= 0) {
            className = methodPart.substring(0, lastDot);
            methodPart = methodPart.substring(lastDot + 1);
        }

        return new MethodRef(className, methodPart, paramTypes);
    }

    /**
     * Filters methods by the parsed reference.
     */
    public static List<MethodInfo> filter(List<MethodInfo> methods, MethodRef ref) {
        List<MethodInfo> candidates = methods.stream()
                .filter(m -> m.name().equals(ref.methodName()))
                .filter(m -> {
                    if (!ref.hasClassName()) return true;
                    return com.jsrc.app.model.TypeId.namesMatch(
                            m.className(), ref.className());
                })
                .toList();
        if (!ref.hasParamTypes()) return candidates;

        int bestScore = Integer.MAX_VALUE;
        List<MethodInfo> matches = new java.util.ArrayList<>();
        for (MethodInfo candidate : candidates) {
            if (candidate.parameters().size() != ref.paramTypes().size()) continue;
            int score = 0;
            for (int i = 0; i < ref.paramTypes().size(); i++) {
                int parameterScore = SignatureUtils.invocationMatchScore(
                        candidate.parameters().get(i).type(), ref.paramTypes().get(i));
                if (parameterScore < 0) {
                    score = -1;
                    break;
                }
                score += parameterScore;
            }
            if (score < 0 || score > bestScore) continue;
            if (score < bestScore) {
                matches.clear();
                bestScore = score;
            }
            matches.add(candidate);
        }
        return List.copyOf(matches);
    }

    /**
     * Strips generic type parameters from a comma-separated param string.
     * E.g. "HashMap&lt;String, Integer&gt;, List&lt;Foo&gt;" → "HashMap, List"
     */
    private static String stripGenerics(String params) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
