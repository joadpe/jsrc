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
                // Strip generics: HashMap<String, Integer> → HashMap
                paramsStr = stripGenerics(paramsStr);
                paramTypes = Arrays.stream(paramsStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
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

            // If qualified name (com.foo.Bar), use simple name only
            int classLastDot = className.lastIndexOf('.');
            if (classLastDot >= 0 && classLastDot + 1 < className.length()
                    && Character.isUpperCase(className.charAt(classLastDot + 1))) {
                className = className.substring(classLastDot + 1);
            }
        }

        return new MethodRef(className, methodPart, paramTypes);
    }

    /**
     * Filters methods by the parsed reference.
     */
    public static List<MethodInfo> filter(List<MethodInfo> methods, MethodRef ref) {
        return methods.stream()
                .filter(m -> m.name().equals(ref.methodName()))
                .filter(m -> {
                    if (!ref.hasClassName()) return true;
                    return m.className().equals(ref.className())
                            || m.className().endsWith("." + ref.className());
                })
                .filter(m -> {
                    if (!ref.hasParamTypes()) return true;
                    if (m.parameters().size() != ref.paramTypes().size()) return false;
                    for (int i = 0; i < ref.paramTypes().size(); i++) {
                        String expected = ref.paramTypes().get(i);
                        String actual = m.parameters().get(i).type();
                        if (!actual.equals(expected) && !actual.endsWith("." + expected)) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();
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
