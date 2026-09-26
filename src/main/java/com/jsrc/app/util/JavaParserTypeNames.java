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
            String enclosingOwner = enclosingBinaryOwner(declaration);
            var callable = declaration.findAncestor(
                    com.github.javaparser.ast.body.CallableDeclaration.class).orElse(null);
            if (enclosingOwner != null && callable != null) {
                var localTypes = callable.findAll(TypeDeclaration.class).stream()
                        .filter(JavaParserTypeNames::isLocalType)
                        .filter(type -> type.findAncestor(
                                com.github.javaparser.ast.body.CallableDeclaration.class)
                                .orElse(null) == callable)
                        .toList();
                int ordinal = identityOrdinal(localTypes, declaration);
                return enclosingOwner + "$" + callableSegment(callable)
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
        String enclosingOwner = enclosingBinaryOwner(creation);
        if (enclosingOwner == null) return "anonymous$1";

        var callable = creation.findAncestor(
                com.github.javaparser.ast.body.CallableDeclaration.class).orElse(null);
        Node scope = callable == null
                ? creation.findAncestor(TypeDeclaration.class)
                        .map(value -> (Node) value)
                        .orElse(creation)
                : callable;
        var anonymousTypes = scope.findAll(
                        com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                .filter(candidate -> candidate.getAnonymousClassBody().isPresent())
                .filter(candidate -> candidate.findAncestor(
                        com.github.javaparser.ast.body.CallableDeclaration.class)
                        .orElse(null) == callable)
                .toList();
        int ordinal = identityOrdinal(anonymousTypes, creation);
        String callableName = callable == null ? "initializer" : callableSegment(callable);
        return enclosingOwner + "$" + callableName
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
        if (callable.getParentNode().orElse(null)
                instanceof com.github.javaparser.ast.expr.ObjectCreationExpr creation
                && creation.getAnonymousClassBody().isPresent()) {
            var overloads = creation.getAnonymousClassBody().orElseThrow().stream()
                    .filter(com.github.javaparser.ast.body.CallableDeclaration.class::isInstance)
                    .map(member -> (com.github.javaparser.ast.body.CallableDeclaration<?>) member)
                    .filter(member -> member.getNameAsString()
                            .equals(callable.getNameAsString()))
                    .toList();
            int ordinal = identityOrdinal(overloads, callable);
            return callable.getNameAsString() + "$" + Math.max(ordinal, 1);
        }

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
        int ordinal = identityOrdinal(overloads, callable);
        return callable.getNameAsString() + "$" + Math.max(ordinal, 1);
    }

    private static int identityOrdinal(
            java.util.List<? extends Node> nodes,
            Node target) {
        return java.util.stream.IntStream.range(0, nodes.size())
                .filter(index -> nodes.get(index) == target)
                .findFirst()
                .orElse(-1) + 1;
    }

    private static String enclosingBinaryOwner(Node node) {
        var anonymous = node.findAncestor(
                        com.github.javaparser.ast.expr.ObjectCreationExpr.class)
                .filter(creation -> creation.getAnonymousClassBody().isPresent())
                .orElse(null);
        if (anonymous != null) return anonymousBinaryName(anonymous);

        TypeDeclaration<?> type = node.findAncestor(TypeDeclaration.class)
                .map(value -> (TypeDeclaration<?>) value)
                .orElse(null);
        return type == null ? null : binaryName(type);
    }

    private static String qualifiedName(String binaryName, Node node) {
        return node.findCompilationUnit()
                .flatMap(compilationUnit -> compilationUnit.getPackageDeclaration())
                .map(packageDeclaration -> packageDeclaration.getNameAsString()
                        + "." + binaryName)
                .orElse(binaryName);
    }
}
