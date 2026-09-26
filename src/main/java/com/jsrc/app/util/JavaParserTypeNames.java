package com.jsrc.app.util;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayDeque;

/** Builds canonical binary and qualified names for JavaParser type declarations. */
public final class JavaParserTypeNames {

    private JavaParserTypeNames() {
    }

    public static String binaryName(TypeDeclaration<?> declaration) {
        if (declaration.getParentNode().orElse(null)
                instanceof com.github.javaparser.ast.stmt.LocalClassDeclarationStmt) {
            var enclosingType = declaration.findAncestor(TypeDeclaration.class).orElse(null);
            var callable = declaration.findAncestor(
                    com.github.javaparser.ast.body.CallableDeclaration.class).orElse(null);
            if (enclosingType != null && callable != null) {
                int ordinal = callable.findAll(TypeDeclaration.class).stream()
                        .filter(JavaParserTypeNames::isLocalType)
                        .filter(type -> type.findAncestor(
                                com.github.javaparser.ast.body.CallableDeclaration.class)
                                .orElse(null) == callable)
                        .toList()
                        .indexOf(declaration) + 1;
                return binaryName(enclosingType) + "$" + callableSegment(callable)
                        + "$" + declaration.getNameAsString() + "$" + ordinal;
            }
        }

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
        return qualifiedName(binaryName, declaration);
    }

    public static String anonymousBinaryName(
            com.github.javaparser.ast.expr.ObjectCreationExpr creation) {
        var enclosingType = creation.findAncestor(TypeDeclaration.class).orElse(null);
        if (enclosingType == null) return "anonymous$1";

        var callable = creation.findAncestor(
                com.github.javaparser.ast.body.CallableDeclaration.class).orElse(null);
        Node scope = callable == null ? enclosingType : callable;
        int ordinal = scope.findAll(
                        com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                .filter(candidate -> candidate.getAnonymousClassBody().isPresent())
                .filter(candidate -> candidate.findAncestor(
                        com.github.javaparser.ast.body.CallableDeclaration.class)
                        .orElse(null) == callable)
                .toList()
                .indexOf(creation) + 1;
        String callableName = callable == null ? "initializer" : callableSegment(callable);
        return binaryName(enclosingType) + "$" + callableName
                + "$anonymous$" + ordinal;
    }

    public static String qualifiedAnonymousName(
            com.github.javaparser.ast.expr.ObjectCreationExpr creation) {
        return qualifiedName(anonymousBinaryName(creation), creation);
    }

    private static boolean isLocalType(TypeDeclaration<?> declaration) {
        return declaration.getParentNode().orElse(null)
                instanceof com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
    }

    private static String callableSegment(
            com.github.javaparser.ast.body.CallableDeclaration<?> callable) {
        TypeDeclaration<?> owner = callable.findAncestor(TypeDeclaration.class)
                .map(type -> (TypeDeclaration<?>) type)
                .orElse(null);
        if (owner == null) return callable.getNameAsString() + "$1";
        var overloads = owner.getMembers().stream()
                .filter(com.github.javaparser.ast.body.CallableDeclaration.class::isInstance)
                .map(member -> (com.github.javaparser.ast.body.CallableDeclaration<?>) member)
                .filter(member -> member.getNameAsString()
                        .equals(callable.getNameAsString()))
                .toList();
        return callable.getNameAsString() + "$" + (overloads.indexOf(callable) + 1);
    }

    private static String qualifiedName(String binaryName, Node node) {
        return node.findCompilationUnit()
                .flatMap(compilationUnit -> compilationUnit.getPackageDeclaration())
                .map(packageDeclaration -> packageDeclaration.getNameAsString()
                        + "." + binaryName)
                .orElse(binaryName);
    }
}
