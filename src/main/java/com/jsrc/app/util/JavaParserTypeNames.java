package com.jsrc.app.util;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayDeque;

/** Builds canonical binary and qualified names for JavaParser type declarations. */
public final class JavaParserTypeNames {

    private JavaParserTypeNames() {
    }

    public static String binaryName(TypeDeclaration<?> declaration) {
        var names = new ArrayDeque<String>();
        Node current = declaration;
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                names.addFirst(type.getNameAsString());
            }
            current = current.getParentNode().orElse(null);
        }
        return String.join("$", names);
    }

    public static String qualifiedName(TypeDeclaration<?> declaration) {
        String binaryName = binaryName(declaration);
        return declaration.findCompilationUnit()
                .flatMap(compilationUnit -> compilationUnit.getPackageDeclaration())
                .map(packageDeclaration -> packageDeclaration.getNameAsString()
                        + "." + binaryName)
                .orElse(binaryName);
    }
}
