package com.jsrc.app.index;

import java.util.List;

/**
 * Compact class metadata stored in the index.
 *
 * @param name          simple class name
 * @param packageName   package declaration
 * @param startLine     start line in source
 * @param endLine       end line in source
 * @param modifiers     declared class modifiers (e.g. "public", "abstract", "final")
 * @param isInterface   true if interface
 * @param isAbstract    true if abstract
 * @param superClass    direct superclass name
 * @param interfaces    implemented interface names
 * @param typeParameters declared class type parameters
 * @param methods       method signatures indexed
 * @param annotations   annotation names on the class
 * @param imports       import statements in the file
 * @param fields        field name → type pairs for call graph resolution
 */
public record IndexedClass(
        String name,
        String packageName,
        int startLine,
        int endLine,
        List<String> modifiers,
        boolean isInterface,
        boolean isAbstract,
        List<String> superClass,
        List<String> interfaces,
        List<String> typeParameters,
        List<IndexedMethod> methods,
        List<String> annotations,
        List<String> imports,
        List<IndexedField> fields
) {
    /** Backward-compatible constructor without modifiers or type parameters. */
    public IndexedClass(
            String name, String packageName, int startLine, int endLine,
            boolean isInterface, boolean isAbstract,
            List<String> superClass, List<String> interfaces,
            List<IndexedMethod> methods, List<String> annotations,
            List<String> imports, List<IndexedField> fields) {
        this(name, packageName, startLine, endLine, List.of(), isInterface, isAbstract,
                superClass, interfaces, List.of(), methods, annotations, imports, fields);
    }

    /** Backward-compatible constructor without modifiers, type parameters or fields. */
    public IndexedClass(
            String name, String packageName, int startLine, int endLine,
            boolean isInterface, boolean isAbstract,
            List<String> superClass, List<String> interfaces,
            List<IndexedMethod> methods, List<String> annotations,
            List<String> imports) {
        this(name, packageName, startLine, endLine, List.of(), isInterface, isAbstract,
                superClass, interfaces, List.of(), methods, annotations, imports, List.of());
    }

    /** Returns a copy with a different method list, keeping every other component. */
    public IndexedClass withMethods(List<IndexedMethod> newMethods) {
        return new IndexedClass(name, packageName, startLine, endLine, modifiers,
                isInterface, isAbstract, superClass, interfaces, typeParameters,
                newMethods, annotations, imports, fields);
    }

    public String qualifiedName() {
        return packageName.isEmpty() ? name : packageName + "." + name;
    }
}
