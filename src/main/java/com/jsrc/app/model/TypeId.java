package com.jsrc.app.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Stable identity for a top-level or nested Java type. */
public record TypeId(String packageName, List<String> enclosingTypes, String simpleName) {

    public TypeId {
        packageName = Objects.requireNonNullElse(packageName, "");
        enclosingTypes = List.copyOf(enclosingTypes);
        simpleName = Objects.requireNonNull(simpleName, "simpleName");
    }

    /** Creates an identity from a package and a binary class name such as Outer$Inner. */
    public static TypeId from(String packageName, String binaryName) {
        String[] segments = Objects.requireNonNull(binaryName, "binaryName").split("\\$");
        List<String> enclosing = segments.length == 1
                ? List.of()
                : Arrays.asList(segments).subList(0, segments.length - 1);
        return new TypeId(packageName, enclosing, segments[segments.length - 1]);
    }

    public String binaryName() {
        if (enclosingTypes.isEmpty()) return simpleName;
        return String.join("$", enclosingTypes) + "$" + simpleName;
    }

    public String relativeSourceName() {
        if (enclosingTypes.isEmpty()) return simpleName;
        return String.join(".", enclosingTypes) + "." + simpleName;
    }

    public String canonicalName() {
        return qualify(binaryName());
    }

    public String sourceName() {
        return qualify(relativeSourceName());
    }

    public boolean matchesQualified(String candidate) {
        return canonicalName().equals(candidate)
                || sourceName().equals(candidate)
                || (!enclosingTypes.isEmpty()
                        && (binaryName().equals(candidate)
                                || relativeSourceName().equals(candidate)));
    }

    /** Matches canonical, source, simple, and nested owner notations. */
    public static boolean namesMatch(String actual, String expected) {
        if (actual.equals(expected)) return true;
        String actualSource = actual.replace('$', '.');
        String expectedSource = expected.replace('$', '.');
        return actualSource.equals(expectedSource)
                || actual.endsWith("." + expected)
                || actual.endsWith("$" + expected)
                || expected.endsWith("." + actual)
                || expected.endsWith("$" + actual)
                || actualSource.endsWith("." + expectedSource);
    }

    private String qualify(String name) {
        return packageName.isEmpty() ? name : packageName + "." + name;
    }
}
