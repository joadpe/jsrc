package com.jsrc.app.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;

/**
 * Extracts and resolves call edges from Java source files.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Extract direct, reflective, and constructor call edges from source</li>
 *   <li>Resolve callee class using local variables, parameters, fields, and class names</li>
 *   <li>Produce {@code ?field:} and {@code ?ret:} markers for cross-class resolution</li>
 *   <li>Post-build marker resolution using field type and return type maps</li>
 * </ul>
 */
public class EdgeResolver {

    private static final Logger logger = LoggerFactory.getLogger(EdgeResolver.class);

    public record Extraction(
            List<CallEdge> edges,
            Map<String, List<IndexedMethod>> syntheticMethods) {
        public Extraction {
            edges = List.copyOf(edges);
            syntheticMethods = syntheticMethods.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
        }
    }

    private record LexicalTypes(
            Map<String, String> local,
            Map<String, String> declared) {
    }

    /**
     * Extracts call edges from a Java file using JavaParser.
     * Resolves callee class names using field types, parameter types,
     * and local variable types for accurate call graph edges.
     */
    public List<CallEdge> extractCallEdges(Path file, JavaParser jp) {
        return extract(file, jp).edges();
    }

    public Extraction extract(Path file, JavaParser jp) {
        List<CallEdge> edges = new ArrayList<>();
        Map<String, List<IndexedMethod>> syntheticMethods = new HashMap<>();
        try {
            String source = Files.readString(file);
            var result = jp.parse(source);
            if (!result.getResult().isPresent()) {
                return new Extraction(edges, syntheticMethods);
            }

            CompilationUnit cu = result.getResult().get();
            for (com.github.javaparser.ast.body.TypeDeclaration<?> declaration
                    : cu.findAll(com.github.javaparser.ast.body.TypeDeclaration.class)) {
                String className = qualifiedClassName(declaration);

                Map<String, String> fieldTypes = new HashMap<>();
                for (FieldDeclaration field : declaration.getFields()) {
                    String fieldType = field.getCommonType().asString();
                    int genIdx = fieldType.indexOf('<');
                    if (genIdx > 0) fieldType = fieldType.substring(0, genIdx);
                    for (VariableDeclarator var : field.getVariables()) {
                        fieldTypes.put(var.getNameAsString(), fieldType);
                    }
                }

                for (MethodDeclaration md : declaration.getMethods()) {
                    LexicalTypes lexicalTypes = lexicalTypes(md);
                    extractEdgesFromCallable(edges, md, className, md.getNameAsString(),
                            fieldTypes, lexicalTypes, syntheticMethods);
                }
                for (ConstructorDeclaration cd : declaration.getMembers().stream()
                        .filter(ConstructorDeclaration.class::isInstance)
                        .map(ConstructorDeclaration.class::cast)
                        .toList()) {
                    LexicalTypes lexicalTypes = lexicalTypes(cd);
                    extractEdgesFromCallable(edges, cd, className,
                            declaration.getNameAsString(),
                            fieldTypes, lexicalTypes, syntheticMethods);
                }
            }
            for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
                if (creation.getAnonymousClassBody().isEmpty()) continue;
                String className = com.jsrc.app.util.JavaParserTypeNames
                        .qualifiedAnonymousName(creation);
                List<com.github.javaparser.ast.body.BodyDeclaration<?>> body =
                        creation.getAnonymousClassBody().orElseThrow();
                Map<String, String> fieldTypes = new HashMap<>();
                body.stream()
                        .filter(FieldDeclaration.class::isInstance)
                        .map(FieldDeclaration.class::cast)
                        .forEach(field -> {
                            String fieldType = field.getCommonType().asString();
                            for (VariableDeclarator variable : field.getVariables()) {
                                fieldTypes.put(variable.getNameAsString(), fieldType);
                            }
                        });
                for (MethodDeclaration method : body.stream()
                        .filter(MethodDeclaration.class::isInstance)
                        .map(MethodDeclaration.class::cast)
                        .toList()) {
                    syntheticMethods.computeIfAbsent(
                                    className, ignored -> new ArrayList<>())
                            .add(toIndexedMethod(method));
                    extractEdgesFromCallable(
                            edges, method, className, method.getNameAsString(),
                            fieldTypes, lexicalTypes(method), syntheticMethods);
                }
            }
        } catch (IOException ex) {
            logger.debug("Error extracting call edges from {}: {}", file, ex.getMessage());
        }
        return new Extraction(edges, syntheticMethods);
    }

    private static IndexedMethod toIndexedMethod(MethodDeclaration method) {
        int startLine = method.getBegin().map(position -> position.line).orElse(-1);
        int endLine = method.getEnd().map(position -> position.line).orElse(startLine);
        return new IndexedMethod(
                method.getNameAsString(),
                method.getDeclarationAsString(true, true, true),
                startLine,
                endLine,
                method.getTypeAsString(),
                method.getAnnotations().stream()
                        .map(annotation -> annotation.getNameAsString())
                        .toList());
    }

    /**
     * Extracts reflective call edges based on invoker config.
     * E.g. ejecutarMetodo("calcularImporte", ...) → CallerAdaptadorBean.calcularImporte()
     */
    public List<CallEdge> extractReflectiveEdges(Path file, JavaParser jp,
                                                  List<com.jsrc.app.config.ArchitectureConfig.InvokerDef> invokers) {
        List<CallEdge> edges = new ArrayList<>();
        try {
            String source = Files.readString(file);
            var result = jp.parse(source);
            if (!result.getResult().isPresent()) return edges;

            CompilationUnit cu = result.getResult().get();
            Map<String, com.jsrc.app.config.ArchitectureConfig.InvokerDef> invokerMap = new HashMap<>();
            for (var inv : invokers) {
                invokerMap.put(inv.method(), inv);
            }

            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String callerClass = qualifiedClassName(cid);
                for (MethodDeclaration md : cid.getMethods()) {
                    List<String> callerParameterTypes = md.getParameters().stream()
                            .map(parameter -> parameter.getTypeAsString()
                                    + (parameter.isVarArgs() ? "..." : ""))
                            .map(com.jsrc.app.util.SignatureUtils::normalizeType)
                            .toList();
                    for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                        var inv = invokerMap.get(call.getNameAsString());
                        if (inv == null) continue;
                        if (call.getArguments().size() <= inv.targetArg()) continue;
                        var arg = call.getArguments().get(inv.targetArg());
                        if (!(arg instanceof com.github.javaparser.ast.expr.StringLiteralExpr strLit)) continue;

                        String targetMethod = strLit.getValue();
                        String prefix = callerClass;
                        for (String suffix : inv.callerSuffixes()) {
                            if (prefix.endsWith(suffix)) {
                                prefix = prefix.substring(0, prefix.length() - suffix.length());
                                break;
                            }
                        }
                        String convention = inv.resolveClass();
                        String targetClass = prefix + convention.substring(0, 1).toUpperCase()
                                + convention.substring(1);

                        int line = call.getBegin().map(p -> p.line).orElse(-1);
                        edges.add(new CallEdge(callerClass, md.getNameAsString(),
                                callerParameterTypes, callerParameterTypes.size(),
                                targetClass, targetMethod, List.of(), line, -1,
                                com.jsrc.app.model.InvocationKind.REFLECTIVE,
                                com.jsrc.app.model.ResolutionLevel.INFERRED,
                                List.of("CONFIGURED_INVOKER")));
                    }
                }
            }
        } catch (IOException ex) {
            logger.debug("Error extracting reflective edges from {}: {}", file, ex.getMessage());
        }
        return edges;
    }

    /**
     * Resolves {@code ?field:} and {@code ?ret:} callee class markers in index entries.
     * Uses field type and return type information from all indexed classes.
     * <p>
     * Modifies the entries list in place, replacing entries whose edges changed.
     * Runs iteratively (up to 5 passes) for nested marker chains.
     */
    public void resolveMarkers(List<IndexEntry> entries) {
        Map<String, String> fieldTypeMap = new HashMap<>();
        Map<String, String> returnTypeMap = new HashMap<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                for (IndexedField f : ic.fields()) {
                    fieldTypeMap.put(ic.qualifiedName() + "." + f.name(), f.type());
                    fieldTypeMap.putIfAbsent(ic.name() + "." + f.name(), f.type());
                }
                for (IndexedMethod im : ic.methods()) {
                    if (im.returnType() != null && !im.returnType().isEmpty()
                            && !"void".equals(im.returnType())) {
                        String rt = im.returnType();
                        int genIdx = rt.indexOf('<');
                        if (genIdx > 0) rt = rt.substring(0, genIdx);
                        returnTypeMap.put(ic.qualifiedName() + "." + im.name(), rt);
                        returnTypeMap.putIfAbsent(ic.name() + "." + im.name(), rt);
                    }
                }
            }
        }
        if (fieldTypeMap.isEmpty() && returnTypeMap.isEmpty()) return;

        for (int pass = 0; pass < 5; pass++) {
            boolean changed = false;
            List<IndexEntry> newEntries = new ArrayList<>();
            for (IndexEntry entry : entries) {
                List<CallEdge> newEdges = new ArrayList<>();
                boolean entryChanged = false;
                for (CallEdge edge : entry.callEdges()) {
                    if (edge.calleeClass().startsWith("?field:")
                            || edge.calleeClass().startsWith("?ret:")) {
                        String resolved = resolveMarker(edge.calleeClass(),
                                fieldTypeMap, returnTypeMap);
                        if (resolved != null && !resolved.startsWith("?")) {
                            newEdges.add(new CallEdge(edge.callerClass(), edge.callerMethod(),
                                    edge.callerParameterTypes(), edge.callerParamCount(),
                                    resolved, edge.calleeMethod(),
                                    edge.calleeParameterTypes(), edge.line(), edge.argCount(),
                                    edge.invocationKind(), edge.resolutionLevel(), edge.evidence()));
                            entryChanged = true;
                            changed = true;
                            continue;
                        }
                    }
                    newEdges.add(edge);
                }
                newEntries.add(entryChanged
                        ? new IndexEntry(entry.path(), entry.contentHash(),
                                entry.lastModified(), entry.classes(), newEdges)
                        : entry);
            }
            entries.clear();
            entries.addAll(newEntries);
            if (!changed) break;
        }
    }

    // ---- internal ----

    private static LexicalTypes lexicalTypes(
            com.github.javaparser.ast.body.CallableDeclaration<?> callable) {
        Map<String, String> localTypes = new HashMap<>();
        Map<String, String> declaredTypes = new HashMap<>();
        List<com.github.javaparser.ast.Node> ancestors = new ArrayList<>();
        com.github.javaparser.ast.Node current = callable.getParentNode().orElse(null);
        while (current != null) {
            ancestors.add(current);
            current = current.getParentNode().orElse(null);
        }
        java.util.Collections.reverse(ancestors);

        for (com.github.javaparser.ast.Node ancestor : ancestors) {
            if (ancestor instanceof com.github.javaparser.ast.body.TypeDeclaration<?> type) {
                for (FieldDeclaration field : type.getFields()) {
                    String fieldType = field.getCommonType().asString();
                    for (VariableDeclarator variable : field.getVariables()) {
                        putType(localTypes, declaredTypes,
                                variable.getNameAsString(), fieldType);
                    }
                }
            }
            if (ancestor instanceof com.github.javaparser.ast.body.CallableDeclaration<?> outer) {
                for (Parameter parameter : outer.getParameters()) {
                    putType(localTypes, declaredTypes,
                            parameter.getNameAsString(), parameter.getTypeAsString());
                }
                for (VariableDeclarator variable
                        : outer.findAll(VariableDeclarator.class)) {
                    if (isVisibleCapturedVariable(variable, callable)) {
                        putType(localTypes, declaredTypes,
                                variable.getNameAsString(), variable.getTypeAsString());
                    }
                }
            }
            if (ancestor instanceof com.github.javaparser.ast.expr.LambdaExpr lambda) {
                List<String> parameterTypes = lambdaParameterTypes(lambda, declaredTypes);
                for (int index = 0; index < lambda.getParameters().size(); index++) {
                    putType(localTypes, declaredTypes,
                            lambda.getParameter(index).getNameAsString(),
                            parameterTypes.get(index));
                }
            }
            if (ancestor instanceof com.github.javaparser.ast.stmt.CatchClause catchClause) {
                Parameter parameter = catchClause.getParameter();
                putType(localTypes, declaredTypes,
                        parameter.getNameAsString(), parameter.getTypeAsString());
            }
        }
        return new LexicalTypes(Map.copyOf(localTypes), Map.copyOf(declaredTypes));
    }

    private static boolean isVisibleCapturedVariable(
            VariableDeclarator variable,
            com.github.javaparser.ast.body.CallableDeclaration<?> callable) {
        return isVisibleVariableAt(variable, callable);
    }

    private static boolean isVisibleVariableAt(
            VariableDeclarator variable,
            com.github.javaparser.ast.Node node) {
        var resourceOwner = resourceOwner(variable);
        if (resourceOwner != null) {
            if (!startsBefore(variable, node)) {
                return false;
            }
            if (isAncestor(resourceOwner.getTryBlock(), node)) {
                return true;
            }
            var resources = resourceOwner.getResources();
            int declarationIndex = -1;
            for (int index = 0; index < resources.size(); index++) {
                if (isAncestor(resources.get(index), variable)) {
                    declarationIndex = index;
                    break;
                }
            }
            for (int index = declarationIndex + 1; index < resources.size(); index++) {
                if (isAncestor(resources.get(index), node)) {
                    return true;
                }
            }
            return false;
        }
        com.github.javaparser.ast.Node scope = lexicalScope(variable);
        return scope != null
                && isAncestor(scope, node)
                && startsBefore(variable, node);
    }

    private static com.github.javaparser.ast.stmt.TryStmt resourceOwner(
            VariableDeclarator variable) {
        return variable.findAncestor(com.github.javaparser.ast.stmt.TryStmt.class)
                .filter(tryStmt -> tryStmt.getResources().stream()
                        .anyMatch(resource -> isAncestor(resource, variable)))
                .orElse(null);
    }

    private static LexicalTypes lexicalTypesAt(
            com.github.javaparser.ast.Node node,
            Map<String, String> baseLocalTypes,
            Map<String, String> baseDeclaredTypes) {
        Map<String, String> localTypes = new HashMap<>(baseLocalTypes);
        Map<String, String> declaredTypes = new HashMap<>(baseDeclaredTypes);
        var callable = node.findAncestor(
                com.github.javaparser.ast.body.CallableDeclaration.class).orElse(null);
        if (callable == null) {
            return new LexicalTypes(localTypes, declaredTypes);
        }
        for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class)) {
            if (belongsToOwner(variable, callable)
                    && isVisibleVariableAt(variable, node)) {
                putType(localTypes, declaredTypes,
                        variable.getNameAsString(), variable.getTypeAsString());
            }
        }
        List<com.github.javaparser.ast.stmt.CatchClause> catchClauses
                = new ArrayList<>();
        com.github.javaparser.ast.Node current = node;
        while (current != null && current != callable) {
            if (current instanceof com.github.javaparser.ast.stmt.CatchClause catchClause) {
                catchClauses.add(catchClause);
            }
            current = current.getParentNode().orElse(null);
        }
        java.util.Collections.reverse(catchClauses);
        for (var catchClause : catchClauses) {
            Parameter parameter = catchClause.getParameter();
            putType(localTypes, declaredTypes,
                    parameter.getNameAsString(), parameter.getTypeAsString());
        }
        return new LexicalTypes(localTypes, declaredTypes);
    }

    private static com.github.javaparser.ast.Node lexicalScope(
            com.github.javaparser.ast.Node node) {
        com.github.javaparser.ast.Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof com.github.javaparser.ast.stmt.BlockStmt
                    || current instanceof com.github.javaparser.ast.stmt.ForStmt
                    || current instanceof com.github.javaparser.ast.stmt.ForEachStmt
                    || current instanceof com.github.javaparser.ast.expr.LambdaExpr
                    || current instanceof com.github.javaparser.ast.stmt.SwitchEntry) {
                return current;
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    private static boolean isAncestor(
            com.github.javaparser.ast.Node ancestor,
            com.github.javaparser.ast.Node node) {
        com.github.javaparser.ast.Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean startsBefore(
            com.github.javaparser.ast.Node first,
            com.github.javaparser.ast.Node second) {
        var firstPosition = first.getBegin().orElse(null);
        var secondPosition = second.getBegin().orElse(null);
        if (firstPosition == null || secondPosition == null) return false;
        return firstPosition.line < secondPosition.line
                || firstPosition.line == secondPosition.line
                && firstPosition.column < secondPosition.column;
    }

    private static void putType(
            Map<String, String> localTypes,
            Map<String, String> declaredTypes,
            String name,
            String type) {
        declaredTypes.put(name, type);
        int genericStart = type.indexOf('<');
        localTypes.put(name, genericStart > 0 ? type.substring(0, genericStart) : type);
    }

    private static void extractEdgesFromCallable(List<CallEdge> edges,
                                                  com.github.javaparser.ast.body.CallableDeclaration<?> callable,
                                                  String className, String callerMethod,
                                                  Map<String, String> fieldTypes,
                                                  LexicalTypes lexicalTypes,
                                                  Map<String, List<IndexedMethod>> syntheticMethods) {
        List<String> callerParameterTypes = callable.getParameters().stream()
                .map(parameter -> parameter.getTypeAsString()
                        + (parameter.isVarArgs() ? "..." : ""))
                .map(com.jsrc.app.util.SignatureUtils::normalizeType)
                .toList();
        int callerParamCount = callerParameterTypes.size();
        Map<String, String> localTypes = new HashMap<>(lexicalTypes.local());
        Map<String, String> declaredTypes = new HashMap<>(lexicalTypes.declared());
        for (Parameter param : callable.getParameters()) {
            String pType = param.getTypeAsString();
            declaredTypes.put(param.getNameAsString(), pType);
            int gi = pType.indexOf('<');
            if (gi > 0) pType = pType.substring(0, gi);
            localTypes.put(param.getNameAsString(), pType);
        }
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            if (!belongsToOwner(call, callable)
                    || call.findAncestor(
                            com.github.javaparser.ast.expr.LambdaExpr.class).isPresent()) {
                continue;
            }
            LexicalTypes callTypes = lexicalTypesAt(call, localTypes, declaredTypes);
            String calleeMethod = call.getNameAsString();
            String calleeClass = resolveCalleeClass(
                    call, className, fieldTypes, callTypes.local());
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            int argCount = call.getArguments().size();
            List<String> calleeParameterTypes = argumentTypes(
                    call.getArguments(), fieldTypes, callTypes.local());
            var invocationKind = extractedInvocationKind(call);
            edges.add(new CallEdge(
                    className, callerMethod, callerParameterTypes, callerParamCount,
                    calleeClass, calleeMethod, calleeParameterTypes, line, argCount,
                    invocationKind,
                    com.jsrc.app.model.ResolutionLevel.UNRESOLVED,
                    invocationKind == com.jsrc.app.model.InvocationKind.SPECIAL
                            ? List.of("SUPER_INVOCATION")
                            : List.of()));
        }
        List<com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt>
                constructorInvocations = callable instanceof ConstructorDeclaration constructor
                ? constructor.getBody().getStatements().stream()
                        .filter(com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt.class::isInstance)
                        .map(com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt.class::cast)
                        .toList()
                : List.of();
        for (com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt invocation
                : constructorInvocations) {
            LexicalTypes invocationTypes = lexicalTypesAt(
                    invocation, localTypes, declaredTypes);
            String calleeClass = invocation.isThis()
                    ? className
                    : directSuperType(invocation);
            List<String> calleeParameterTypes = argumentTypes(
                    invocation.getArguments(), fieldTypes, invocationTypes.local());
            edges.add(new CallEdge(
                    className,
                    callerMethod,
                    callerParameterTypes,
                    callerParamCount,
                    calleeClass,
                    constructorName(calleeClass),
                    calleeParameterTypes,
                    invocation.getBegin().map(position -> position.line).orElse(-1),
                    calleeParameterTypes.size(),
                    com.jsrc.app.model.InvocationKind.SPECIAL,
                    com.jsrc.app.model.ResolutionLevel.UNRESOLVED,
                    List.of(invocation.isThis()
                            ? "THIS_CONSTRUCTOR_INVOCATION"
                            : "SUPER_CONSTRUCTOR_INVOCATION")));
        }
        extractLambdaEdges(edges, callable, className, callerMethod, fieldTypes,
                localTypes, declaredTypes, syntheticMethods);
        extractMethodReferenceEdges(edges, callable, className, callerMethod,
                callerParameterTypes, fieldTypes, localTypes, declaredTypes);
        for (ObjectCreationExpr newExpr : callable.findAll(ObjectCreationExpr.class)) {
            if (!belongsToOwner(newExpr, callable)
                    || newExpr.findAncestor(
                            com.github.javaparser.ast.expr.LambdaExpr.class).isPresent()) {
                continue;
            }
            LexicalTypes creationTypes = lexicalTypesAt(
                    newExpr, localTypes, declaredTypes);
            addObjectCreationEdge(edges, newExpr, className, callerMethod,
                    callerParameterTypes, fieldTypes, creationTypes.local());
        }
    }

    private static void extractLambdaEdges(
            List<CallEdge> edges,
            com.github.javaparser.ast.body.CallableDeclaration<?> callable,
            String className,
            String callerMethod,
            Map<String, String> fieldTypes,
            Map<String, String> enclosingLocalTypes,
            Map<String, String> declaredTypes,
            Map<String, List<IndexedMethod>> syntheticMethods) {
        int ordinal = 0;
        for (com.github.javaparser.ast.expr.LambdaExpr lambda
                : callable.findAll(com.github.javaparser.ast.expr.LambdaExpr.class)) {
            if (!belongsToOwner(lambda, callable)) continue;
            ordinal++;
            String syntheticName = syntheticLambdaName(
                    callable, callerMethod, ordinal);
            LexicalTypes lambdaScope = lexicalTypesAt(
                    lambda, enclosingLocalTypes, declaredTypes);
            Map<String, String> lambdaTypes = new HashMap<>(lambdaScope.local());
            Map<String, String> lambdaDeclaredTypes =
                    new HashMap<>(lambdaScope.declared());
            addEnclosingLambdaTypes(lambda, lambdaTypes, lambdaDeclaredTypes);
            List<String> parameterTypes = lambdaParameterTypes(
                    lambda, lambdaDeclaredTypes);
            int startLine = lambda.getBegin().map(position -> position.line).orElse(-1);
            int endLine = lambda.getEnd().map(position -> position.line).orElse(startLine);
            String signature = "void " + syntheticName + "("
                    + String.join(", ", parameterTypes) + ")";
            syntheticMethods.computeIfAbsent(className, ignored -> new ArrayList<>())
                    .add(new IndexedMethod(
                            syntheticName, signature, startLine, endLine, "void", List.of()));

            for (int index = 0; index < lambda.getParameters().size(); index++) {
                String parameterName = lambda.getParameter(index).getNameAsString();
                String parameterType = parameterTypes.get(index);
                putType(lambdaTypes, lambdaDeclaredTypes,
                        parameterName, parameterType);
            }

            for (MethodCallExpr call : lambda.findAll(MethodCallExpr.class)) {
                if (!belongsToLambda(call, lambda)) continue;

                LexicalTypes callTypes = lexicalTypesAt(
                        call, lambdaTypes, lambdaDeclaredTypes);
                String calleeClass = resolveCalleeClass(
                        call, className, fieldTypes, callTypes.local());
                int line = call.getBegin().map(position -> position.line).orElse(-1);
                List<String> calleeParameterTypes = argumentTypes(
                        call.getArguments(), fieldTypes, callTypes.local());
                var invocationKind = extractedInvocationKind(call);
                edges.add(new CallEdge(
                        className,
                        syntheticName,
                        parameterTypes,
                        parameterTypes.size(),
                        calleeClass,
                        call.getNameAsString(),
                        calleeParameterTypes,
                        line,
                        call.getArguments().size(),
                        invocationKind,
                        com.jsrc.app.model.ResolutionLevel.UNRESOLVED,
                        invocationKind == com.jsrc.app.model.InvocationKind.SPECIAL
                                ? List.of("SUPER_INVOCATION")
                                : List.of()));
            }
            for (com.github.javaparser.ast.expr.MethodReferenceExpr reference
                    : lambda.findAll(com.github.javaparser.ast.expr.MethodReferenceExpr.class)) {
                if (!belongsToLambda(reference, lambda)) continue;
                LexicalTypes referenceTypes = lexicalTypesAt(
                        reference, lambdaTypes, lambdaDeclaredTypes);
                addMethodReferenceEdge(edges, reference, className, syntheticName,
                        parameterTypes, fieldTypes, referenceTypes.local(),
                        referenceTypes.declared(), callable);
            }
            for (ObjectCreationExpr newExpr : lambda.findAll(ObjectCreationExpr.class)) {
                if (!belongsToLambda(newExpr, lambda)) continue;
                LexicalTypes creationTypes = lexicalTypesAt(
                        newExpr, lambdaTypes, lambdaDeclaredTypes);
                addObjectCreationEdge(edges, newExpr, className, syntheticName,
                        parameterTypes, fieldTypes, creationTypes.local());
            }
        }
    }

    private static String syntheticLambdaName(
            com.github.javaparser.ast.body.CallableDeclaration<?> callable,
            String callerMethod,
            int lambdaOrdinal) {
        com.github.javaparser.ast.body.TypeDeclaration<?> owner = callable.findAncestor(
                        com.github.javaparser.ast.body.TypeDeclaration.class)
                .map(type -> (com.github.javaparser.ast.body.TypeDeclaration<?>) type)
                .orElse(null);
        if (owner == null) return callerMethod + "$lambda$" + lambdaOrdinal;

        var overloads = owner.getMembers().stream()
                        .filter(com.github.javaparser.ast.body.CallableDeclaration.class::isInstance)
                        .map(member -> (com.github.javaparser.ast.body.CallableDeclaration<?>) member)
                        .filter(member -> member.getNameAsString().equals(callerMethod))
                        .toList();
        if (overloads.size() < 2) {
            return callerMethod + "$lambda$" + lambdaOrdinal;
        }
        int overloadOrdinal = overloads.indexOf(callable) + 1;
        return callerMethod + "$overload$" + overloadOrdinal
                + "$lambda$" + lambdaOrdinal;
    }

    private static void addEnclosingLambdaTypes(
            com.github.javaparser.ast.expr.LambdaExpr lambda,
            Map<String, String> localTypes,
            Map<String, String> declaredTypes) {
        List<com.github.javaparser.ast.expr.LambdaExpr> ancestors = new ArrayList<>();
        com.github.javaparser.ast.Node current = lambda.getParentNode().orElse(null);
        while (current != null
                && !(current instanceof com.github.javaparser.ast.body.CallableDeclaration<?>)) {
            if (current instanceof com.github.javaparser.ast.expr.LambdaExpr enclosing) {
                ancestors.add(enclosing);
            }
            current = current.getParentNode().orElse(null);
        }
        java.util.Collections.reverse(ancestors);
        for (com.github.javaparser.ast.expr.LambdaExpr enclosing : ancestors) {
            List<String> parameterTypes = lambdaParameterTypes(enclosing, declaredTypes);
            for (int index = 0; index < enclosing.getParameters().size(); index++) {
                putType(localTypes, declaredTypes,
                        enclosing.getParameter(index).getNameAsString(),
                        parameterTypes.get(index));
            }
        }
    }

    private static boolean belongsToLambda(
            com.github.javaparser.ast.Node node,
            com.github.javaparser.ast.expr.LambdaExpr lambda) {
        return belongsToOwner(node, lambda)
                && node.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class)
                .filter(lambda::equals)
                .isPresent();
    }

    private static boolean belongsToOwner(
            com.github.javaparser.ast.Node node,
            com.github.javaparser.ast.Node owner) {
        com.github.javaparser.ast.Node expectedCallable =
                owner instanceof com.github.javaparser.ast.body.CallableDeclaration<?> callable
                        ? callable
                        : owner.findAncestor(
                                com.github.javaparser.ast.body.CallableDeclaration.class)
                                .orElse(null);
        if (node.findAncestor(com.github.javaparser.ast.body.CallableDeclaration.class)
                .orElse(null) != expectedCallable) {
            return false;
        }

        com.github.javaparser.ast.Node expectedType = owner.findAncestor(
                com.github.javaparser.ast.body.TypeDeclaration.class).orElse(null);
        if (node.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                .orElse(null) != expectedType) {
            return false;
        }

        com.github.javaparser.ast.Node ancestor = node.getParentNode().orElse(null);
        while (ancestor != null && ancestor != owner) {
            if (ancestor instanceof com.github.javaparser.ast.body.BodyDeclaration<?> declaration
                    && declaration.getParentNode().orElse(null)
                    instanceof ObjectCreationExpr anonymousCreation
                    && anonymousCreation.getAnonymousClassBody().isPresent()) {
                return false;
            }
            ancestor = ancestor.getParentNode().orElse(null);
        }
        return ancestor == owner;
    }

    private static List<String> lambdaParameterTypes(
            com.github.javaparser.ast.expr.LambdaExpr lambda,
            Map<String, String> declaredTypes) {
        List<String> inferred = inferFunctionalParameterTypes(lambda, declaredTypes);
        return java.util.stream.IntStream.range(0, lambda.getParameters().size())
                .mapToObj(index -> {
                    Parameter parameter = lambda.getParameter(index);
                    if (!parameter.getType().isUnknownType()) {
                        return parameter.getTypeAsString();
                    }
                    return index < inferred.size()
                            ? inferred.get(index)
                            : CallEdge.UNKNOWN_PARAMETER_TYPE;
                })
                .map(type -> type == null || type.isBlank()
                        ? CallEdge.UNKNOWN_PARAMETER_TYPE
                        : com.jsrc.app.util.SignatureUtils.normalizeType(type))
                .toList();
    }

    private static List<String> inferFunctionalParameterTypes(
            com.github.javaparser.ast.expr.LambdaExpr lambda,
            Map<String, String> declaredTypes) {
        var callable = lambda.findAncestor(
                        com.github.javaparser.ast.body.CallableDeclaration.class)
                .map(value -> (com.github.javaparser.ast.body.CallableDeclaration<?>) value)
                .orElse(null);
        FunctionalContext context = contextualFunctionalType(
                lambda, callable, declaredTypes);
        if (context.ambiguous()) return List.of();
        if (context.type() == null) {
            return knownForEachParameterTypes(lambda, declaredTypes);
        }
        List<String> inferred = functionalInputTypes(context.type(), lambda);
        return inferred == null ? List.of() : inferred;
    }

    private static List<String> knownForEachParameterTypes(
            com.github.javaparser.ast.expr.LambdaExpr lambda,
            Map<String, String> declaredTypes) {
        if (!(lambda.getParentNode().orElse(null) instanceof MethodCallExpr call)
                || !call.getNameAsString().equals("forEach")
                || call.getScope().isEmpty()
                || !(call.getScope().get() instanceof NameExpr name)) {
            return List.of();
        }
        String receiverType = declaredTypes.get(name.getNameAsString());
        if (receiverType == null) return List.of();
        List<String> arguments = genericArguments(receiverType);
        String qualifiedRawType = receiverType;
        int genericStart = qualifiedRawType.indexOf('<');
        if (genericStart >= 0) {
            qualifiedRawType = qualifiedRawType.substring(0, genericStart);
        }
        String rawType = qualifiedRawType.substring(
                Math.max(qualifiedRawType.lastIndexOf('.') + 1, 0));
        if (!isKnownJdkForEachType(lambda, qualifiedRawType, rawType)) {
            return List.of();
        }
        if (rawType.equals("Map") && lambda.getParameters().size() == 2
                && arguments.size() >= 2) {
            return List.of(arguments.get(0), arguments.get(1));
        }
        if (java.util.Set.of("Iterable", "Collection", "List", "Set", "Stream")
                .contains(rawType)
                && lambda.getParameters().size() == 1
                && !arguments.isEmpty()) {
            return List.of(arguments.getFirst());
        }
        return List.of();
    }

    private static boolean isKnownJdkForEachType(
            com.github.javaparser.ast.expr.LambdaExpr lambda,
            String qualifiedRawType,
            String rawType) {
        var unit = lambda.findCompilationUnit().orElse(null);
        if (unit != null && unit.findAll(
                        com.github.javaparser.ast.body.TypeDeclaration.class).stream()
                .anyMatch(type -> type.getNameAsString().equals(rawType))) {
            return false;
        }
        if (java.util.Set.of(
                        "java.lang.Iterable",
                        "java.util.Collection",
                        "java.util.List",
                        "java.util.Set",
                        "java.util.Map",
                        "java.util.stream.Stream")
                .contains(qualifiedRawType)) {
            return true;
        }
        if (unit == null) return rawType.equals("Iterable");
        String packageName = rawType.equals("Stream")
                ? "java.util.stream"
                : "java.util";
        return rawType.equals("Iterable")
                || unit.getImports().stream().anyMatch(importDeclaration ->
                        (!importDeclaration.isAsterisk()
                                && importDeclaration.getNameAsString()
                                .equals(packageName + "." + rawType))
                                || (importDeclaration.isAsterisk()
                                && importDeclaration.getNameAsString()
                                .equals(packageName)));
    }

    private static void extractMethodReferenceEdges(
            List<CallEdge> edges,
            com.github.javaparser.ast.body.CallableDeclaration<?> callable,
            String className,
            String callerMethod,
            List<String> callerParameterTypes,
            Map<String, String> fieldTypes,
            Map<String, String> localTypes,
            Map<String, String> declaredTypes) {
        for (com.github.javaparser.ast.expr.MethodReferenceExpr reference
                : callable.findAll(com.github.javaparser.ast.expr.MethodReferenceExpr.class)) {
            if (!belongsToOwner(reference, callable)
                    || reference.findAncestor(
                            com.github.javaparser.ast.expr.LambdaExpr.class).isPresent()) {
                continue;
            }
            LexicalTypes referenceTypes = lexicalTypesAt(
                    reference, localTypes, declaredTypes);
            addMethodReferenceEdge(edges, reference, className, callerMethod,
                    callerParameterTypes, fieldTypes, referenceTypes.local(),
                    referenceTypes.declared(), callable);
        }
    }

    private static void addMethodReferenceEdge(
            List<CallEdge> edges,
            com.github.javaparser.ast.expr.MethodReferenceExpr reference,
            String className,
            String callerMethod,
            List<String> callerParameterTypes,
            Map<String, String> fieldTypes,
            Map<String, String> localTypes,
            Map<String, String> declaredTypes,
            com.github.javaparser.ast.body.CallableDeclaration<?> callable) {
        String scopeName = reference.getScope().toString();
        String calleeClass = localTypes.getOrDefault(scopeName, fieldTypes.get(scopeName));
        if (calleeClass == null) {
            calleeClass = resolveExpressionType(
                    reference.getScope(), className, fieldTypes, localTypes);
        }
        if (calleeClass == null) calleeClass = reference.getScope().toString();
        String calleeMethod = "new".equals(reference.getIdentifier())
                ? constructorName(calleeClass)
                : reference.getIdentifier();
        List<String> referencedParameterTypes = methodReferenceParameterTypes(
                reference, callable, declaredTypes);
        int line = reference.getBegin().map(position -> position.line).orElse(-1);
        List<String> evidence = new ArrayList<>();
        evidence.add("METHOD_REFERENCE_EXPRESSION");
        if ("new".equals(reference.getIdentifier())) {
            evidence.add("CONSTRUCTOR_REFERENCE");
        } else if (localTypes.containsKey(scopeName) || fieldTypes.containsKey(scopeName)) {
            evidence.add("BOUND_METHOD_REFERENCE");
        } else {
            evidence.add("TYPE_SCOPED_METHOD_REFERENCE");
        }
        if (referencedParameterTypes == null) {
            String functionalArgument = functionalArgumentEvidence(
                    reference, className, fieldTypes, localTypes);
            if (functionalArgument != null) evidence.add(functionalArgument);
        }
        edges.add(new CallEdge(
                className,
                callerMethod,
                callerParameterTypes,
                callerParameterTypes.size(),
                calleeClass,
                calleeMethod,
                referencedParameterTypes == null
                        ? List.of(CallEdge.UNKNOWN_PARAMETER_TYPE)
                        : referencedParameterTypes,
                line,
                referencedParameterTypes == null ? -1 : referencedParameterTypes.size(),
                com.jsrc.app.model.InvocationKind.METHOD_REFERENCE,
                com.jsrc.app.model.ResolutionLevel.UNRESOLVED,
                evidence));
    }

    private static void addObjectCreationEdge(
            List<CallEdge> edges,
            ObjectCreationExpr newExpr,
            String className,
            String callerMethod,
            List<String> callerParameterTypes,
            Map<String, String> fieldTypes,
            Map<String, String> localTypes) {
        String targetClass = newExpr.getType().getNameAsString();
        int line = newExpr.getBegin().map(position -> position.line).orElse(-1);
        List<String> calleeParameterTypes = argumentTypes(
                newExpr.getArguments(), fieldTypes, localTypes);
        edges.add(new CallEdge(
                className,
                callerMethod,
                callerParameterTypes,
                callerParameterTypes.size(),
                targetClass,
                targetClass,
                calleeParameterTypes,
                line,
                newExpr.getArguments().size(),
                com.jsrc.app.model.InvocationKind.CONSTRUCTOR,
                com.jsrc.app.model.ResolutionLevel.UNRESOLVED,
                List.of("OBJECT_CREATION")));
    }

    private static List<String> methodReferenceParameterTypes(
            com.github.javaparser.ast.expr.MethodReferenceExpr reference,
            com.github.javaparser.ast.body.CallableDeclaration<?> callable,
            Map<String, String> declaredTypes) {
        String functionalType = methodReferenceFunctionalType(
                reference, callable, declaredTypes);
        if (functionalType == null) return null;
        return functionalInputTypes(functionalType, reference);
    }

    private static String functionalArgumentEvidence(
            com.github.javaparser.ast.expr.MethodReferenceExpr reference,
            String className,
            Map<String, String> fieldTypes,
            Map<String, String> localTypes) {
        if (!(reference.getParentNode().orElse(null) instanceof MethodCallExpr call)) {
            return null;
        }
        int argumentIndex = call.getArguments().indexOf(reference);
        if (argumentIndex < 0) return null;
        String receiver = resolveCalleeClass(call, className, fieldTypes, localTypes);
        return String.join(
                "|",
                "FUNCTIONAL_ARGUMENT",
                receiver,
                call.getNameAsString(),
                Integer.toString(call.getArguments().size()),
                Integer.toString(argumentIndex));
    }

    private static String methodReferenceFunctionalType(
            com.github.javaparser.ast.expr.MethodReferenceExpr reference,
            com.github.javaparser.ast.body.CallableDeclaration<?> callable,
            Map<String, String> declaredTypes) {
        return contextualFunctionalType(reference, callable, declaredTypes).type();
    }

    private static FunctionalContext contextualFunctionalType(
            com.github.javaparser.ast.expr.Expression expression,
            com.github.javaparser.ast.body.CallableDeclaration<?> callable,
            Map<String, String> declaredTypes) {
        var parent = expression.getParentNode().orElse(null);
        if (parent instanceof VariableDeclarator variable) {
            return FunctionalContext.resolved(variable.getTypeAsString());
        }
        if (parent instanceof com.github.javaparser.ast.expr.CastExpr cast) {
            return FunctionalContext.resolved(cast.getTypeAsString());
        }
        if (parent instanceof com.github.javaparser.ast.stmt.ReturnStmt
                && callable instanceof MethodDeclaration method) {
            return FunctionalContext.resolved(method.getTypeAsString());
        }
        if (parent instanceof com.github.javaparser.ast.expr.AssignExpr assignment
                && assignment.getTarget().isNameExpr()) {
            return FunctionalContext.resolved(
                    declaredTypes.get(assignment.getTarget().asNameExpr().getNameAsString()));
        }
        if (parent instanceof MethodCallExpr call) {
            int argumentIndex = call.getArguments().indexOf(expression);
            if (argumentIndex < 0) return FunctionalContext.absent();
            String receiverType = invokedReceiverType(call, declaredTypes);
            if (receiverType == null) return FunctionalContext.absent();
            List<MethodDeclaration> candidates = call.findCompilationUnit()
                    .map(unit -> unit.findAll(MethodDeclaration.class).stream()
                    .filter(method -> method.getNameAsString().equals(call.getNameAsString()))
                    .filter(method -> method.getParameters().size() == call.getArguments().size())
                    .filter(method -> argumentIndex < method.getParameters().size())
                    .filter(method -> method.findAncestor(
                            com.github.javaparser.ast.body.TypeDeclaration.class)
                            .map(type -> matchesType(receiverType,
                                    com.jsrc.app.util.JavaParserTypeNames.qualifiedName(type)))
                            .orElse(false))
                    .toList())
                    .orElse(List.of());
            if (candidates.size() > 1) return FunctionalContext.ambiguousContext();
            if (candidates.isEmpty()) return FunctionalContext.absent();
            MethodDeclaration candidate = candidates.getFirst();
            String functionalType = candidate.getParameter(argumentIndex).getTypeAsString();
            return FunctionalContext.resolved(substituteOwnerTypeArguments(
                    functionalType, candidate, receiverType));
        }
        return FunctionalContext.absent();
    }

    private record FunctionalContext(String type, boolean ambiguous) {
        private static FunctionalContext resolved(String type) {
            return type == null || type.isBlank()
                    ? absent()
                    : new FunctionalContext(type, false);
        }

        private static FunctionalContext absent() {
            return new FunctionalContext(null, false);
        }

        private static FunctionalContext ambiguousContext() {
            return new FunctionalContext(null, true);
        }
    }

    private static String substituteOwnerTypeArguments(
            String type,
            MethodDeclaration method,
            String receiverType) {
        var owner = method.findAncestor(
                        com.github.javaparser.ast.body.TypeDeclaration.class)
                .map(value -> (com.github.javaparser.ast.body.TypeDeclaration<?>) value)
                .orElse(null);
        if (!(owner instanceof com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters<?>
                parameterizedOwner)
                || parameterizedOwner.getTypeParameters().isEmpty()) {
            return type;
        }
        List<String> arguments = genericArguments(receiverType);
        if (arguments.size() != parameterizedOwner.getTypeParameters().size()) return type;

        String resolved = type;
        for (int index = 0; index < arguments.size(); index++) {
            String parameter = parameterizedOwner.getTypeParameters()
                    .get(index).getNameAsString();
            resolved = resolved.replaceAll(
                    "\\b" + java.util.regex.Pattern.quote(parameter) + "\\b",
                    java.util.regex.Matcher.quoteReplacement(arguments.get(index)));
        }
        return resolved;
    }

    private static String invokedReceiverType(
            MethodCallExpr call,
            Map<String, String> declaredTypes) {
        if (call.getScope().isEmpty()) {
            return call.findAncestor(
                            com.github.javaparser.ast.body.TypeDeclaration.class)
                    .map(com.jsrc.app.util.JavaParserTypeNames::qualifiedName)
                    .orElse(null);
        }
        var scope = call.getScope().get();
        if (scope instanceof NameExpr name) {
            return declaredTypes.getOrDefault(
                    name.getNameAsString(), name.getNameAsString());
        }
        if (scope instanceof com.github.javaparser.ast.expr.ThisExpr) {
            return call.findAncestor(
                            com.github.javaparser.ast.body.TypeDeclaration.class)
                    .map(com.jsrc.app.util.JavaParserTypeNames::qualifiedName)
                    .orElse(null);
        }
        return null;
    }

    private static boolean matchesType(String expected, String actual) {
        String normalized = expected;
        int genericStart = normalized.indexOf('<');
        if (genericStart >= 0) normalized = normalized.substring(0, genericStart);
        return actual.equals(normalized)
                || actual.endsWith("." + normalized)
                || normalized.endsWith("." + actual);
    }

    static List<String> functionalInputTypes(
            String functionalType,
            com.github.javaparser.ast.Node context) {
        String rawType = functionalType;
        int genericStart = rawType.indexOf('<');
        if (genericStart >= 0) rawType = rawType.substring(0, genericStart);
        int qualifier = rawType.lastIndexOf('.');
        String simpleType = qualifier >= 0 ? rawType.substring(qualifier + 1) : rawType;
        List<String> arguments = genericArguments(functionalType);
        return switch (simpleType) {
            case "Runnable", "Supplier", "Callable" -> List.of();
            case "Function", "Consumer", "Predicate", "UnaryOperator" ->
                    arguments.isEmpty() ? null : List.of(arguments.getFirst());
            case "BiFunction", "BiConsumer", "BiPredicate" ->
                    arguments.size() < 2 ? null : List.of(arguments.get(0), arguments.get(1));
            case "BinaryOperator", "Comparator" ->
                    arguments.isEmpty()
                            ? null
                            : List.of(arguments.getFirst(), arguments.getFirst());
            default -> customFunctionalInputTypes(simpleType, context);
        };
    }

    private static List<String> customFunctionalInputTypes(
            String simpleType,
            com.github.javaparser.ast.Node context) {
        return context.findCompilationUnit()
                .stream()
                .flatMap(unit -> unit.findAll(MethodDeclaration.class).stream())
                .filter(method -> method.findAncestor(
                                com.github.javaparser.ast.body.TypeDeclaration.class)
                        .map(type -> type.getNameAsString().equals(simpleType))
                        .orElse(false))
                .filter(method -> !method.isStatic())
                .map(method -> method.getParameters().stream()
                        .map(parameter -> com.jsrc.app.util.SignatureUtils.normalizeType(
                                parameter.getTypeAsString()
                                        + (parameter.isVarArgs() ? "..." : "")))
                        .toList())
                .findFirst()
                .orElse(null);
    }

    static List<String> genericArguments(String type) {
        int start = type.indexOf('<');
        int end = type.lastIndexOf('>');
        if (start < 0 || end <= start) return List.of();
        String body = type.substring(start + 1, end);
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int segmentStart = 0;
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '<') depth++;
            else if (current == '>') depth--;
            else if (current == ',' && depth == 0) {
                arguments.add(com.jsrc.app.util.SignatureUtils.normalizeType(
                        body.substring(segmentStart, index)));
                segmentStart = index + 1;
            }
        }
        arguments.add(com.jsrc.app.util.SignatureUtils.normalizeType(
                body.substring(segmentStart)));
        return List.copyOf(arguments);
    }

    private static String constructorName(String typeName) {
        String erased = typeName;
        int genericStart = erased.indexOf('<');
        if (genericStart >= 0) erased = erased.substring(0, genericStart);
        int separator = Math.max(erased.lastIndexOf('.'), erased.lastIndexOf('$'));
        return separator >= 0 ? erased.substring(separator + 1) : erased;
    }

    private static List<String> argumentTypes(
            com.github.javaparser.ast.NodeList<Expression> arguments,
            Map<String, String> fieldTypes,
            Map<String, String> localTypes) {
        List<String> types = new ArrayList<>();
        for (Expression argument : arguments) {
            String type = argumentType(argument, fieldTypes, localTypes);
            types.add(type == null
                    ? CallEdge.UNKNOWN_PARAMETER_TYPE
                    : com.jsrc.app.util.SignatureUtils.normalizeType(type));
        }
        return List.copyOf(types);
    }

    private static String argumentType(Expression argument,
                                       Map<String, String> fieldTypes,
                                       Map<String, String> localTypes) {
        if (argument instanceof NameExpr nameExpr) {
            return localTypes.getOrDefault(nameExpr.getNameAsString(),
                    fieldTypes.get(nameExpr.getNameAsString()));
        }
        if (argument instanceof com.github.javaparser.ast.expr.StringLiteralExpr) return "String";
        if (argument instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr) return "int";
        if (argument instanceof com.github.javaparser.ast.expr.LongLiteralExpr) return "long";
        if (argument instanceof com.github.javaparser.ast.expr.DoubleLiteralExpr) return "double";
        if (argument instanceof com.github.javaparser.ast.expr.BooleanLiteralExpr) return "boolean";
        if (argument instanceof com.github.javaparser.ast.expr.CharLiteralExpr) return "char";
        if (argument instanceof com.github.javaparser.ast.expr.ObjectCreationExpr creation) {
            return creation.getTypeAsString();
        }
        if (argument instanceof com.github.javaparser.ast.expr.CastExpr cast) {
            return cast.getTypeAsString();
        }
        return null;
    }

    private static String qualifiedClassName(
            com.github.javaparser.ast.body.TypeDeclaration<?> declaration) {
        return com.jsrc.app.util.JavaParserTypeNames.qualifiedName(declaration);
    }

    /**
     * Resolves the class of the callee in a method call expression.
     * Checks: this → current class, variable → local/param/field type,
     * FieldAccessExpr → field type lookup with marker encoding, static → class name.
     */
    static String resolveCalleeClass(MethodCallExpr call, String currentClass,
                                      Map<String, String> fieldTypes,
                                      Map<String, String> localTypes) {
        if (call.getScope().isEmpty()) return currentClass;
        var scope = call.getScope().get();
        if (scope instanceof ThisExpr) return currentClass;
        if (scope instanceof com.github.javaparser.ast.expr.SuperExpr superExpression) {
            return superType(call, superExpression);
        }
        if (scope instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            String type = localTypes.get(varName);
            if (type != null) return type;
            type = fieldTypes.get(varName);
            if (type != null) return type;
            return varName;
        }
        if (scope instanceof FieldAccessExpr fae) {
            String fieldName = fae.getNameAsString();
            var objExpr = fae.getScope();
            String objType = resolveExpressionType(objExpr, currentClass, fieldTypes, localTypes);
            if (objType != null) {
                return "?field:" + objType + "." + fieldName;
            }
        }
        return "?";
    }

    private static com.jsrc.app.model.InvocationKind extractedInvocationKind(
            MethodCallExpr call) {
        return call.getScope()
                .filter(com.github.javaparser.ast.expr.SuperExpr.class::isInstance)
                .map(ignored -> com.jsrc.app.model.InvocationKind.SPECIAL)
                .orElse(com.jsrc.app.model.InvocationKind.UNKNOWN);
    }

    private static String superType(
            com.github.javaparser.ast.Node node,
            com.github.javaparser.ast.expr.SuperExpr superExpression) {
        return superExpression.getTypeName()
                .map(Object::toString)
                .orElseGet(() -> node.findAncestor(
                                com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                        .flatMap(declaration -> declaration.getExtendedTypes().stream().findFirst())
                        .map(com.github.javaparser.ast.type.ClassOrInterfaceType::getNameWithScope)
                        .orElse("?"));
    }

    private static String directSuperType(com.github.javaparser.ast.Node node) {
        return node.findAncestor(
                        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .flatMap(declaration -> declaration.getExtendedTypes().stream().findFirst())
                .map(com.github.javaparser.ast.type.ClassOrInterfaceType::getNameWithScope)
                .orElse("?");
    }

    /**
     * Resolves the type of an expression (variable, this, field access, method call).
     * Produces {@code ?field:} and {@code ?ret:} markers for deferred resolution.
     */
    static String resolveExpressionType(Expression expr, String currentClass,
                                         Map<String, String> fieldTypes,
                                         Map<String, String> localTypes) {
        if (expr instanceof ThisExpr) return currentClass;
        if (expr instanceof com.github.javaparser.ast.expr.SuperExpr superExpression) {
            return superType(expr, superExpression);
        }
        if (expr instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            String type = localTypes.get(varName);
            if (type != null) return type;
            type = fieldTypes.get(varName);
            if (type != null) return type;
            return varName;
        }
        if (expr instanceof FieldAccessExpr fae) {
            String objType = resolveExpressionType(fae.getScope(), currentClass,
                    fieldTypes, localTypes);
            if (objType != null) {
                return "?field:" + objType + "." + fae.getNameAsString();
            }
        }
        if (expr instanceof MethodCallExpr mce) {
            String methodName = mce.getNameAsString();
            if (mce.getScope().isEmpty()) {
                return "?ret:" + currentClass + "." + methodName;
            }
            String scopeType = resolveExpressionType(mce.getScope().get(), currentClass,
                    fieldTypes, localTypes);
            if (scopeType != null) {
                return "?ret:" + scopeType + "." + methodName;
            }
        }
        return null;
    }

    /**
     * Resolves a marker string to a concrete type.
     * Supports nested {@code ?field:OwnerType.fieldName} and {@code ?ret:ClassName.methodName}.
     */
    /** Resolves caller and callee type names using the common project symbol resolver. */
    public void resolveSymbols(List<IndexEntry> entries) {
        var resolver = new SemanticCallResolver(entries);
        List<IndexEntry> resolvedEntries = new ArrayList<>(entries.size());
        for (IndexEntry entry : entries) {
            List<CallEdge> resolvedEdges = new ArrayList<>();
            for (CallEdge edge : entry.callEdges()) {
                resolvedEdges.addAll(resolver.resolve(edge));
            }
            resolvedEntries.add(entry.withEdges(resolvedEdges));
        }
        entries.clear();
        entries.addAll(resolvedEntries);
    }

    public static String resolveMarker(String marker,
                                       Map<String, String> fieldTypeMap,
                                       Map<String, String> returnTypeMap) {
        if (marker.startsWith("?field:")) {
            String payload = marker.substring("?field:".length());
            int dotIdx = payload.lastIndexOf('.');
            if (dotIdx < 0) return null;

            String ownerType = payload.substring(0, dotIdx);
            String fieldName = payload.substring(dotIdx + 1);

            if (ownerType.startsWith("?")) {
                ownerType = resolveMarker(ownerType, fieldTypeMap, returnTypeMap);
                if (ownerType == null || ownerType.startsWith("?")) return null;
            }

            return fieldTypeMap.get(ownerType + "." + fieldName);
        }

        if (marker.startsWith("?ret:")) {
            String payload = marker.substring("?ret:".length());
            int dotIdx = payload.lastIndexOf('.');
            if (dotIdx < 0) return null;

            String ownerType = payload.substring(0, dotIdx);
            String methodName = payload.substring(dotIdx + 1);

            if (ownerType.startsWith("?")) {
                ownerType = resolveMarker(ownerType, fieldTypeMap, returnTypeMap);
                if (ownerType == null || ownerType.startsWith("?")) return null;
            }

            return returnTypeMap.get(ownerType + "." + methodName);
        }

        return marker;
    }
}
