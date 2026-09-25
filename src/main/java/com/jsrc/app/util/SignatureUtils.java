package com.jsrc.app.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared utilities for parsing Java method signatures.
 * Eliminates duplication across CallGraphBuilder, MethodTargetResolver, and SmellsCommand.
 */
public final class SignatureUtils {

    private SignatureUtils() {}

    /**
     * Extracts the parameter portion from a method signature string.
     * E.g. "public void foo(String s, int x)" → "(String, int)"
     *
     * @return "(Type1, Type2)" or "()" if no params or unparseable
     */
    public static String extractParams(String signature) {
        List<String> parameterTypes = extractParameterTypes(signature);
        return "(" + String.join(", ", parameterTypes) + ")";
    }

    /**
     * Extracts and normalizes parameter types from a method signature.
     */
    public static List<String> extractParameterTypes(String signature) {
        if (signature == null) return List.of();
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) return List.of();
        String inner = signature.substring(open + 1, close).trim();
        if (inner.isEmpty()) return List.of();

        List<String> params = splitOutsideGenerics(inner);
        return params.stream()
                .map(SignatureUtils::extractParameterType)
                .toList();
    }

    /**
     * Normalizes a type for canonical method identity.
     */
    public static String normalizeType(String type) {
        String normalized = type.trim()
                .replace("...", "[]")
                .replaceAll("\\s+", "")
                .replace(",", ",");
        return normalized;
    }

    /**
     * Normalizes a type and removes generic arguments for method identity matching.
     */
    public static String eraseType(String type) {
        String normalized = normalizeType(type);
        StringBuilder erased = new StringBuilder(normalized.length());
        int genericDepth = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (current == '<') {
                genericDepth++;
            } else if (current == '>') {
                genericDepth--;
            } else if (genericDepth == 0) {
                erased.append(current);
            }
        }
        return erased.toString();
    }

    /** Erases a method parameter type, mapping unbounded type variables to Object. */
    public static String eraseParameterType(String type) {
        String erasedType = eraseType(type);
        return erasedType.matches("[A-Z]") ? "Object" : erasedType;
    }

    /** Compares parameter types after erasure, qualification, and boxing normalization. */
    public static boolean sameErasedType(String left, String right) {
        return invocationMatchScore(left, right) >= 0
                || invocationMatchScore(right, left) >= 0;
    }

    /**
     * Scores assignment of an argument type to a declared parameter type.
     * Lower scores are more specific: exact, boxing, primitive widening, fallback reference.
     */
    public static int invocationMatchScore(String parameterType, String argumentType) {
        String parameter = eraseType(parameterType);
        String argument = eraseType(argumentType);
        if (sameQualifiedType(parameter, argument)) return 0;
        if (boxedType(parameter).equals(boxedType(argument))) return 1;
        if (isPrimitiveWidening(argument, parameter)) return 2;
        if ((parameter.matches("[A-Z]") || "Object".equals(simpleType(parameter)))
                && !isPrimitive(argument)) {
            return 3;
        }
        return -1;
    }

    private static boolean sameQualifiedType(String left, String right) {
        return left.equals(right)
                || left.endsWith("." + right)
                || right.endsWith("." + left);
    }

    private static boolean isPrimitiveWidening(String argument, String parameter) {
        String from = simpleType(argument);
        String to = simpleType(parameter);
        return switch (from) {
            case "byte" -> switch (to) {
                case "short", "int", "long", "float", "double" -> true;
                default -> false;
            };
            case "short", "char" -> switch (to) {
                case "int", "long", "float", "double" -> true;
                default -> false;
            };
            case "int" -> switch (to) {
                case "long", "float", "double" -> true;
                default -> false;
            };
            case "long" -> "float".equals(to) || "double".equals(to);
            case "float" -> "double".equals(to);
            default -> false;
        };
    }

    private static String boxedType(String type) {
        String simpleType = simpleType(type);
        return switch (simpleType) {
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "char" -> "Character";
            case "short" -> "Short";
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            default -> simpleType;
        };
    }

    private static String simpleType(String type) {
        return type.startsWith("java.lang.")
                ? type.substring("java.lang.".length())
                : type;
    }

    private static boolean isPrimitive(String type) {
        return switch (simpleType(type)) {
            case "boolean", "byte", "char", "short", "int", "long", "float", "double" -> true;
            default -> false;
        };
    }

    /**
     * Parses and normalizes a comma-separated parameter type list.
     */
    public static List<String> parseParameterTypes(String parameterTypes) {
        if (parameterTypes == null || parameterTypes.isBlank()) return List.of();
        return splitOutsideGenerics(parameterTypes).stream()
                .map(SignatureUtils::normalizeType)
                .toList();
    }

    /**
     * Counts parameters from a method signature string.
     * E.g. "public void foo(String s, int x)" → 2, "void bar()" → 0.
     * Handles generics correctly (e.g. {@code HashMap<K, V>} counts as one param).
     *
     * @return parameter count, or -1 if unparseable
     */
    public static int countParams(String signature) {
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

    /**
     * Splits a parameter string by commas, respecting generic depth.
     * E.g. "HashMap&lt;K, V&gt; m, String s" → ["HashMap&lt;K, V&gt; m", "String s"]
     */
    private static List<String> splitOutsideGenerics(String params) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(params.substring(start, i));
                start = i + 1;
            }
        }
        result.add(params.substring(start));
        return result;
    }

    private static String extractParameterType(String parameter) {
        String value = parameter.trim()
                .replaceFirst("^(?:final\\s+)", "");
        int genericDepth = 0;
        for (int i = value.length() - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (c == '>') genericDepth++;
            else if (c == '<') genericDepth--;
            else if (Character.isWhitespace(c) && genericDepth == 0) {
                return normalizeType(value.substring(0, i));
            }
        }
        return normalizeType(value);
    }

}
