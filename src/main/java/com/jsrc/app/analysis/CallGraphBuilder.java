package com.jsrc.app.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Builds a directed call graph across all Java files in a codebase.
 * <p>
 * For each method, discovers all {@link MethodCallExpr} nodes and resolves
 * the receiver type using local variables, parameters, fields, and class names.
 * Unresolvable calls fall back to name-only matching (className = "?").
 */
public class CallGraphBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CallGraphBuilder.class);

    private final JavaParser javaParser;

    private final Map<MethodReference, Set<MethodCall>> callerIndex = new HashMap<>();
    private final Map<MethodReference, Set<MethodCall>> calleeIndex = new HashMap<>();
    private final Set<MethodReference> allMethods = new HashSet<>();
    private final Map<String, Set<MethodReference>> methodsByName = new HashMap<>();

    public CallGraphBuilder() {
        var config = new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(config);
    }

    /**
     * Parses all given files and builds the call graph.
     * Uses two passes to avoid holding all ASTs in memory:
     * <ol>
     *   <li>Pass 1: register classes and fields (lightweight context only, AST discarded)</li>
     *   <li>Pass 2: re-parse each file and analyze method calls (AST discarded after each file)</li>
     * </ol>
     */
    public void build(List<Path> javaFiles) {
        if (javaFiles.isEmpty()) {
            callerIndex.clear();
            calleeIndex.clear();
            allMethods.clear();
            methodsByName.clear();
            return;
        }

        List<Path> normalizedFiles = javaFiles.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        Path sourceRoot = commonSourceRoot(normalizedFiles);
        var index = new com.jsrc.app.index.CodebaseIndex();
        index.build(
                new com.jsrc.app.parser.HybridJavaParser(),
                normalizedFiles,
                sourceRoot,
                List.of());
        loadFromIndex(index.getEntries());
    }

    private static Path commonSourceRoot(List<Path> files) {
        Path common = files.getFirst().getParent();
        for (Path file : files) {
            while (common != null && !file.startsWith(common)) {
                common = common.getParent();
            }
        }
        return common == null ? Path.of("").toAbsolutePath().normalize() : common;
    }

    /**
     * Loads the call graph from pre-computed index entries.
     * Much faster than {@link #build(List)} — no file I/O or parsing needed.
     */
    public void loadFromIndex(List<com.jsrc.app.index.IndexEntry> entries) {
        callerIndex.clear();
        calleeIndex.clear();
        allMethods.clear();
        methodsByName.clear();

        for (var entry : entries) {
            // Register methods from classes
            for (var ic : entry.classes()) {
                for (var im : ic.methods()) {
                    var parameterTypes = com.jsrc.app.util.SignatureUtils
                            .extractParameterTypes(im.signature());
                    MethodReference ref = new MethodReference(
                            ic.qualifiedName(), im.name(), parameterTypes, null);
                    allMethods.add(ref);
                    methodsByName.computeIfAbsent(im.name(), k -> new HashSet<>()).add(ref);
                }
            }

            // Load call edges
            for (var edge : entry.callEdges()) {
                MethodReference caller = edge.callerParameterTypes().size() == edge.callerParamCount()
                        ? new MethodReference(edge.callerClass(), edge.callerMethod(),
                                edge.callerParameterTypes(), null)
                        : resolveRegistered(edge.callerClass(), edge.callerMethod(),
                                edge.callerParamCount());
                MethodReference callee = hasKnownParameterTypes(
                        edge.calleeParameterTypes(), edge.argCount())
                        ? resolveRegistered(new MethodReference(
                                edge.calleeClass(), edge.calleeMethod(),
                                edge.calleeParameterTypes(), null))
                        : resolveRegistered(
                                edge.calleeClass(), edge.calleeMethod(), edge.argCount());
                MethodCall call = new MethodCall(
                        caller,
                        callee,
                        edge.line(),
                        edge.invocationKind(),
                        edge.resolutionLevel(),
                        edge.evidence());

                addEdge(call);
            }
        }

        // Post-process: resolve "?" callee classes using return type map
        resolveUnknownCallees(entries);
        canonicalizeRegisteredEdges();

        logger.info("Call graph loaded from index: {} methods, {} call edges",
                allMethods.size(), callerIndex.values().stream().mapToInt(Set::size).sum());
    }

    private static boolean hasKnownParameterTypes(List<String> parameterTypes, int parameterCount) {
        return parameterTypes.size() == parameterCount
                && parameterTypes.stream().noneMatch(
                        com.jsrc.app.index.CallEdge.UNKNOWN_PARAMETER_TYPE::equals);
    }

    /**
     * Resolves callee class "?" by looking up the return type of the method
     * that produces the receiver. Runs iteratively to handle chained calls like
     * {@code fto.getExplotacion().getIdiomaDefecto().getIdioma()}.
     * <p>
     * Also resolves "?field:OwnerType.fieldName" markers from field access chains.
     * <p>
     * Pass 1: resolves ?.getIdiomaDefecto() → Explotacion.getIdiomaDefecto()
     * (because getExplotacion() returns Explotacion and is on the same line).
     * Pass 2: resolves ?.getIdioma() → IdiomaDefecto.getIdioma()
     * (because getIdiomaDefecto() was resolved in pass 1 and returns IdiomaDefecto).
     */
    private void resolveUnknownCallees(List<com.jsrc.app.index.IndexEntry> entries) {
        resolveUnknownCallees(semanticMetadata(entries));
    }

    private void resolveUnknownCallees(SemanticMetadata metadata) {
        resolveFieldMarkers(metadata.fieldTypes(), metadata.returnTypes());
        if (metadata.returnTypes().isEmpty()) return;

        for (int pass = 0; pass < 5; pass++) {
            boolean changed = resolvePass(metadata.returnTypes(), metadata.qualifiedNames());
            if (!changed) break;
            logger.debug("Return type resolution pass {} completed", pass + 1);
        }
    }

    private SemanticMetadata semanticMetadata(List<com.jsrc.app.index.IndexEntry> entries) {
        Map<String, Set<String>> simpleToQualified = new HashMap<>();
        Map<String, String> fieldTypeMap = new HashMap<>();
        Map<String, String> returnTypes = new HashMap<>();
        for (var entry : entries) {
            for (var ic : entry.classes()) {
                simpleToQualified.computeIfAbsent(ic.name(), k -> new HashSet<>())
                        .add(ic.qualifiedName());
                for (var f : ic.fields()) {
                    fieldTypeMap.put(ic.qualifiedName() + "." + f.name(), f.type());
                    fieldTypeMap.putIfAbsent(ic.name() + "." + f.name(), f.type());
                }
                for (var im : ic.methods()) {
                    if (im.returnType() != null && !im.returnType().isEmpty()
                            && !"void".equals(im.returnType())) {
                        String rt = stripGenerics(im.returnType());
                        String resolved = resolveTypeViaImports(rt, ic.imports(), ic.packageName(), simpleToQualified);
                        returnTypes.put(ic.qualifiedName() + "." + im.name(), resolved);
                        returnTypes.putIfAbsent(ic.name() + "." + im.name(), resolved);
                    }
                }
            }
        }

        return new SemanticMetadata(fieldTypeMap, returnTypes, simpleToQualified);
    }

    private SemanticMetadata semanticMetadata(Iterable<ClassContext> contexts) {
        Map<String, Set<String>> simpleToQualified = new HashMap<>();
        Map<String, ClassContext> uniqueContexts = new HashMap<>();
        for (ClassContext context : contexts) {
            if (context.qualifiedName == null) continue;
            uniqueContexts.put(context.qualifiedName, context);
            simpleToQualified.computeIfAbsent(context.simpleName, ignored -> new HashSet<>())
                    .add(context.qualifiedName);
        }

        Map<String, String> fieldTypeMap = new HashMap<>();
        Map<String, String> returnTypes = new HashMap<>();
        for (ClassContext context : uniqueContexts.values()) {
            context.fieldTypes.forEach((name, type) -> {
                fieldTypeMap.put(context.qualifiedName + "." + name, type);
                fieldTypeMap.putIfAbsent(context.simpleName + "." + name, type);
            });
            context.returnTypes.forEach((name, type) -> {
                String resolved = resolveTypeViaImports(stripGenerics(type), context.imports,
                        context.packageName, simpleToQualified);
                returnTypes.put(context.qualifiedName + "." + name, resolved);
                returnTypes.putIfAbsent(context.simpleName + "." + name, resolved);
            });
        }

        return new SemanticMetadata(fieldTypeMap, returnTypes, simpleToQualified);
    }

    /**
     * Resolves "?field:" and "?ret:" callee class markers in the call graph.
     * Delegates marker parsing to {@link com.jsrc.app.index.EdgeResolver#resolveMarker}.
     */
    private void resolveFieldMarkers(Map<String, String> fieldTypeMap,
                                     Map<String, String> returnTypeMap) {
        for (int pass = 0; pass < 5; pass++) {
            boolean changed = false;
            Map<MethodReference, Set<MethodCall>> newCalleeIndex = new HashMap<>();

            for (var callerEntry : calleeIndex.entrySet()) {
                MethodReference caller = callerEntry.getKey();
                Set<MethodCall> calls = callerEntry.getValue();
                Set<MethodCall> updatedCalls = new HashSet<>();

                for (MethodCall call : calls) {
                    String calleeClass = call.callee().className();
                    if (calleeClass.startsWith("?field:") || calleeClass.startsWith("?ret:")) {
                        String resolved = com.jsrc.app.index.EdgeResolver.resolveMarker(
                                calleeClass, fieldTypeMap, returnTypeMap);
                        if (resolved != null && !resolved.startsWith("?")) {
                            MethodReference newCallee = resolveRegistered(
                                    resolved, call.callee().methodName(),
                                    call.callee().parameterCount());
                            MethodCall newCall = new MethodCall(caller, newCallee, call.line());
                            updatedCalls.add(newCall);

                            callerIndex.getOrDefault(call.callee(), Collections.emptySet()).remove(call);
                            callerIndex.computeIfAbsent(newCallee, k -> new HashSet<>()).add(newCall);
                            allMethods.add(newCallee);
                            methodsByName.computeIfAbsent(newCallee.methodName(), k -> new HashSet<>()).add(newCallee);
                            changed = true;
                            continue;
                        }
                    }
                    updatedCalls.add(call);
                }
                newCalleeIndex.put(caller, updatedCalls);
            }

            if (changed) {
                calleeIndex.clear();
                calleeIndex.putAll(newCalleeIndex);
                callerIndex.entrySet().removeIf(e -> e.getValue().isEmpty());
            } else {
                break;
            }
        }
    }

    /**
     * Single pass of unknown callee resolution.
     * Returns true if any callees were resolved.
     */
    private boolean resolvePass(Map<String, String> returnTypes,
                                Map<String, Set<String>> qualifiedNames) {
        Map<MethodReference, Set<MethodCall>> newCalleeIndex = new HashMap<>();
        boolean changed = false;

        for (var callerEntry : calleeIndex.entrySet()) {
            MethodReference caller = callerEntry.getKey();
            Set<MethodCall> calls = callerEntry.getValue();

            // Collect all resolved calls on each line (for return type lookup)
            Map<Integer, List<MethodCall>> resolvedByLine = new HashMap<>();
            for (MethodCall call : calls) {
                if (!"?".equals(call.callee().className())) {
                    resolvedByLine.computeIfAbsent(call.line(), k -> new java.util.ArrayList<>()).add(call);
                }
            }

            Set<MethodCall> updatedCalls = new HashSet<>();
            for (MethodCall call : calls) {
                if (!"?".equals(call.callee().className())) {
                    updatedCalls.add(call);
                    continue;
                }

                String resolvedClass = resolveCalleeClass(call, resolvedByLine, returnTypes, qualifiedNames);

                if (resolvedClass != null) {
                    MethodReference newCallee = resolveRegistered(
                            resolvedClass, call.callee().methodName(),
                            call.callee().parameterCount());
                    MethodCall newCall = new MethodCall(caller, newCallee, call.line());
                    updatedCalls.add(newCall);

                    // Update callerIndex
                    callerIndex.getOrDefault(call.callee(), Collections.emptySet()).remove(call);
                    callerIndex.computeIfAbsent(newCallee, k -> new HashSet<>()).add(newCall);

                    allMethods.add(newCallee);
                    methodsByName.computeIfAbsent(newCallee.methodName(), k -> new HashSet<>()).add(newCallee);
                    changed = true;
                } else {
                    updatedCalls.add(call);
                }
            }

            newCalleeIndex.put(caller, updatedCalls);
        }

        if (changed) {
            calleeIndex.clear();
            calleeIndex.putAll(newCalleeIndex);
            callerIndex.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        return changed;
    }

    /**
     * Tries to resolve a "?" callee class using return types of other calls on the same line,
     * or by unique method name match across the codebase.
     * <p>
     * When the return type is a qualified name (e.g. "com.app.Explotacion"), uses it
     * to disambiguate classes with the same simple name in different packages.
     *
     * @param qualifiedNames map of simple class name → set of qualified names (for disambiguation)
     */
    private String resolveCalleeClass(MethodCall call,
                                       Map<Integer, List<MethodCall>> resolvedByLine,
                                       Map<String, String> returnTypes,
                                       Map<String, Set<String>> qualifiedNames) {
        // Strategy 1: same-line calls whose return type declares this method
        List<MethodCall> sameLine = resolvedByLine.getOrDefault(call.line(), List.of());
        for (MethodCall resolved : sameLine) {
            String rt = returnTypes.get(resolved.callee().className() + "." + resolved.callee().methodName());
            if (rt != null) {
                String simpleRt = rt.contains(".") ? rt.substring(rt.lastIndexOf('.') + 1) : rt;

                // Verify the return type class has the target method
                Set<MethodReference> candidates = methodsByName.getOrDefault(call.callee().methodName(), Set.of());
                boolean hasMethod = candidates.stream()
                        .anyMatch(m -> m.className().equals(simpleRt)
                                || m.className().endsWith("." + simpleRt));
                if (!hasMethod) continue;

                // If multiple classes share the simple name, use the qualified return type
                // to pick the correct one by checking which class has this method in returnTypes
                Set<String> qualifieds = qualifiedNames.getOrDefault(simpleRt, Set.of());
                if (qualifieds.size() > 1 && rt.contains(".")) {
                    // The return type map keys use simple names. Check if the method exists
                    // specifically under a class with the correct qualified name by verifying
                    // returnTypes contains an entry for this class.method
                    String checkKey = simpleRt + "." + call.callee().methodName();
                    String methodReturnType = returnTypes.get(checkKey);
                    if (methodReturnType != null || hasMethod) {
                        // Accept only if the qualified RT is among known qualifieds
                        if (qualifieds.contains(rt)) {
                            return rt;
                        }
                    }
                } else {
                    return rt;
                }
            }
        }

        // Strategy 2: unique method name — only one class has this method
        Set<MethodReference> candidates = methodsByName.get(call.callee().methodName());
        if (candidates != null) {
            Set<String> classes = new HashSet<>();
            for (MethodReference c : candidates) {
                if (!"?".equals(c.className())) classes.add(c.className());
            }
            if (classes.size() == 1) {
                return classes.iterator().next();
            }
        }

        return null;
    }

    /**
     * Resolves a simple return type name to a qualified name using the declaring
     * class's imports. E.g. "Explotacion" → "com.agbar.occam.negocio.modelos.Explotacion"
     * if the declaring class imports that package.
     *
     * @param simpleType        simple type name from return type
     * @param imports           import statements of the declaring class
     * @param declaringPkg      package of the declaring class
     * @param simpleToQualified map of simple name → set of qualified names
     * @return qualified name if resolvable, otherwise the simple name
     */
    private static String resolveTypeViaImports(String simpleType, java.util.List<String> imports,
                                                 String declaringPkg,
                                                 Map<String, Set<String>> simpleToQualified) {
        if (simpleType == null || simpleType.isEmpty()) return simpleType;
        if (simpleType.contains(".")) return simpleType;

        // Check explicit imports: import com.app.Explotacion;
        for (String imp : imports) {
            if (imp.endsWith("." + simpleType)) {
                return imp;
            }
        }

        // Check wildcard imports: import com.app.*;
        Set<String> qualifieds = simpleToQualified.getOrDefault(simpleType, Set.of());
        for (String imp : imports) {
            if (imp.endsWith(".*")) {
                String pkg = imp.substring(0, imp.length() - 2);
                String candidate = pkg + "." + simpleType;
                if (qualifieds.contains(candidate)) {
                    return candidate;
                }
            }
        }

        // Same package?
        if (declaringPkg != null && !declaringPkg.isEmpty()) {
            String samePackage = declaringPkg + "." + simpleType;
            if (qualifieds.contains(samePackage)) {
                return samePackage;
            }
        }

        // Only one qualified name exists — use it
        if (qualifieds.size() == 1) {
            return qualifieds.iterator().next();
        }

        return simpleType;
    }

    /**
     * Adds a single call edge to the graph (e.g. reflective calls from InvokerResolver).
     */
    public void addEdge(MethodCall call) {
        MethodReference caller = call.caller();
        MethodReference callee = call.callee();
        allMethods.add(caller);
        allMethods.add(callee);
        methodsByName.computeIfAbsent(caller.methodName(), k -> new HashSet<>()).add(caller);
        methodsByName.computeIfAbsent(callee.methodName(), k -> new HashSet<>()).add(callee);
        calleeIndex.computeIfAbsent(caller, k -> new HashSet<>()).add(call);
        callerIndex.computeIfAbsent(callee, k -> new HashSet<>()).add(call);
    }

    private void canonicalizeRegisteredEdges() {
        Map<MethodReference, Set<MethodCall>> canonicalCalleeIndex = new HashMap<>();
        Map<MethodReference, Set<MethodCall>> canonicalCallerIndex = new HashMap<>();

        for (Set<MethodCall> calls : calleeIndex.values()) {
            for (MethodCall call : calls) {
                MethodReference caller = resolveRegistered(call.caller());
                MethodReference callee = resolveRegistered(call.callee());
                MethodCall canonicalCall = new MethodCall(
                        caller,
                        callee,
                        call.line(),
                        call.invocationKind(),
                        call.resolutionLevel(),
                        call.evidence());
                canonicalCalleeIndex.computeIfAbsent(caller, ignored -> new HashSet<>())
                        .add(canonicalCall);
                canonicalCallerIndex.computeIfAbsent(callee, ignored -> new HashSet<>())
                        .add(canonicalCall);
            }
        }

        calleeIndex.clear();
        calleeIndex.putAll(canonicalCalleeIndex);
        callerIndex.clear();
        callerIndex.putAll(canonicalCallerIndex);
    }

    /**
     * Returns all calls where {@code method} is the callee (who calls this method?).
     */
    public Set<MethodCall> getCallersOf(MethodReference method) {
        return callerIndex.getOrDefault(method, Collections.emptySet());
    }

    /**
     * Returns all calls where {@code method} is the caller (what does this method call?).
     */
    public Set<MethodCall> getCalleesOf(MethodReference method) {
        return calleeIndex.getOrDefault(method, Collections.emptySet());
    }

    public Set<MethodReference> getAllMethods() {
        return Collections.unmodifiableSet(allMethods);
    }

    /**
     * Finds all registered methods matching the given name (across all classes).
     */
    public Set<MethodReference> findMethodsByName(String methodName) {
        return methodsByName.getOrDefault(methodName, Collections.emptySet());
    }

    /**
     * Returns all method references that appear as callees in the caller index.
     * Used for fuzzy matching across interface/implementation boundaries.
     */
    public Set<MethodReference> getAllCallerIndexKeys() {
        return Collections.unmodifiableSet(callerIndex.keySet());
    }

    /**
     * Returns true if no method in the graph calls this method.
     */
    public boolean isRoot(MethodReference method) {
        Set<MethodCall> callers = callerIndex.get(method);
        return callers == null || callers.isEmpty();
    }

    /**
     * Creates an immutable {@link CallGraph} snapshot from the current builder state.
     * Call after {@link #build(List)} or {@link #loadFromIndex(List)}.
     */
    public CallGraph toCallGraph() {
        // Deep-copy sets to ensure immutability
        Map<MethodReference, Set<MethodCall>> callerCopy = new HashMap<>();
        for (var e : callerIndex.entrySet()) {
            callerCopy.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        Map<MethodReference, Set<MethodCall>> calleeCopy = new HashMap<>();
        for (var e : calleeIndex.entrySet()) {
            calleeCopy.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        Map<String, Set<MethodReference>> byNameCopy = new HashMap<>();
        for (var e : methodsByName.entrySet()) {
            byNameCopy.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        return CallGraph.of(callerCopy, calleeCopy, Set.copyOf(allMethods), byNameCopy);
    }

    // -- registration pass --

    private void registerClasses(CompilationUnit cu, Path file,
                                 Map<String, ClassContext> classContexts) {
        for (com.github.javaparser.ast.body.TypeDeclaration<?> cid
                : cu.findAll(com.github.javaparser.ast.body.TypeDeclaration.class)) {
            String qualifiedKey = buildQualifiedKey(cid);
            String className = cid.getNameAsString();
            String packageName = cu.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            List<String> imports = cu.getImports().stream()
                    .map(declaration -> declaration.getNameAsString()
                            + (declaration.isAsterisk() ? ".*" : ""))
                    .toList();
            ClassContext ctx = new ClassContext(
                    file, qualifiedKey, className, packageName, imports);

            for (FieldDeclaration field : cid.getFields()) {
                String fieldType = field.getCommonType().asString();
                for (VariableDeclarator var : field.getVariables()) {
                    ctx.fieldTypes.put(var.getNameAsString(), fieldType);
                }
            }

            for (MethodDeclaration md : cid.getMethods()) {
                ctx.returnTypes.put(md.getNameAsString(), md.getTypeAsString());
                MethodReference ref = new MethodReference(
                        qualifiedKey, md.getNameAsString(),
                        parameterTypes(md), file);
                allMethods.add(ref);
                methodsByName.computeIfAbsent(md.getNameAsString(), k -> new HashSet<>()).add(ref);
            }

            // Register constructors as methods named after the class
            for (ConstructorDeclaration cd : cid.getMembers().stream()
                    .filter(ConstructorDeclaration.class::isInstance)
                    .map(ConstructorDeclaration.class::cast)
                    .toList()) {
                MethodReference ref = new MethodReference(
                        qualifiedKey, className,
                        parameterTypes(cd), file);
                allMethods.add(ref);
                methodsByName.computeIfAbsent(className, k -> new HashSet<>()).add(ref);
            }

            classContexts.put(qualifiedKey, ctx);
            classContexts.putIfAbsent(className, ctx);
        }
    }

    private String buildQualifiedKey(
            com.github.javaparser.ast.body.TypeDeclaration<?> cid) {
        StringBuilder sb = new StringBuilder(cid.getNameAsString());
        Node parent = cid.getParentNode().orElse(null);
        while (parent instanceof com.github.javaparser.ast.body.TypeDeclaration<?> outer) {
            sb.insert(0, outer.getNameAsString() + "$");
            parent = outer.getParentNode().orElse(null);
        }
        cid.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(packageDeclaration -> packageDeclaration.getNameAsString() + ".")
                .ifPresent(prefix -> sb.insert(0, prefix));
        return sb.toString();
    }

    // -- call analysis pass --

    private void analyzeMethodCalls(CompilationUnit cu, Path file,
                                    Map<String, ClassContext> classContexts) {
        for (com.github.javaparser.ast.body.TypeDeclaration<?> cid
                : cu.findAll(com.github.javaparser.ast.body.TypeDeclaration.class)) {
            String qualifiedKey = buildQualifiedKey(cid);
            String className = cid.getNameAsString();
            ClassContext classCtx = classContexts.getOrDefault(qualifiedKey,
                    classContexts.getOrDefault(className, new ClassContext(file)));

            for (MethodDeclaration md : cid.getMethods()) {
                MethodReference caller = new MethodReference(
                        qualifiedKey, md.getNameAsString(),
                        parameterTypes(md), file);

                Map<String, String> localTypes = buildLocalTypeMap(md);

                analyzeCallsInBody(caller, md, qualifiedKey, localTypes, classCtx, classContexts);
            }

            // Analyze constructor bodies
            for (ConstructorDeclaration cd : cid.getMembers().stream()
                    .filter(ConstructorDeclaration.class::isInstance)
                    .map(ConstructorDeclaration.class::cast)
                    .toList()) {
                MethodReference caller = new MethodReference(
                        qualifiedKey, className,
                        parameterTypes(cd), file);

                Map<String, String> localTypes = buildLocalTypeMap(cd);
                analyzeCallsInBody(caller, cd, qualifiedKey, localTypes, classCtx, classContexts);
            }
        }
    }

    private List<String> parameterTypes(
            com.github.javaparser.ast.body.CallableDeclaration<?> callable) {
        return callable.getParameters().stream()
                .map(parameter -> parameter.getTypeAsString()
                        + (parameter.isVarArgs() ? "..." : ""))
                .map(com.jsrc.app.util.SignatureUtils::normalizeType)
                .toList();
    }

    private void analyzeCallsInBody(MethodReference caller, Node body, String className,
                                      Map<String, String> localTypes, ClassContext classCtx,
                                      Map<String, ClassContext> classContexts) {
        // Method calls
        for (MethodCallExpr callExpr : body.findAll(MethodCallExpr.class)) {
            MethodReference callee = resolveCallee(callExpr, className, localTypes, classCtx, classContexts);
            List<String> calleeParameterTypes = argumentTypes(
                    callExpr.getArguments(), className, localTypes, classCtx, classContexts);
            if (calleeParameterTypes.size() == callExpr.getArguments().size()) {
                callee = new MethodReference(callee.className(), callee.methodName(),
                        calleeParameterTypes, callee.filePath());
            }
            if (!"?".equals(callee.className()) && !callee.className().startsWith("?")) {
                callee = resolveRegistered(callee);
            }
            int line = callExpr.getBegin().map(p -> p.line).orElse(-1);
            MethodCall call = new MethodCall(caller, callee, line);
            calleeIndex.computeIfAbsent(caller, k -> new HashSet<>()).add(call);
            callerIndex.computeIfAbsent(callee, k -> new HashSet<>()).add(call);
        }

        // Constructor invocations: new Foo(...)
        for (ObjectCreationExpr newExpr : body.findAll(ObjectCreationExpr.class)) {
            String targetClass = newExpr.getType().getNameAsString();
            MethodReference callee = new MethodReference(targetClass, targetClass,
                    newExpr.getArguments().size(), null);
            List<String> calleeParameterTypes = argumentTypes(
                    newExpr.getArguments(), className, localTypes, classCtx, classContexts);
            if (calleeParameterTypes.size() == newExpr.getArguments().size()) {
                callee = new MethodReference(targetClass, targetClass,
                        calleeParameterTypes, null);
            }
            callee = resolveRegistered(callee);
            int line = newExpr.getBegin().map(p -> p.line).orElse(-1);
            MethodCall call = new MethodCall(caller, callee, line);
            calleeIndex.computeIfAbsent(caller, k -> new HashSet<>()).add(call);
            callerIndex.computeIfAbsent(callee, k -> new HashSet<>()).add(call);
        }
    }

    @SuppressWarnings("null")
    private MethodReference resolveCallee(MethodCallExpr callExpr, String currentClass,
                                          Map<String, String> localTypes,
                                          ClassContext classCtx,
                                          Map<String, ClassContext> allClasses) {
        String methodName = callExpr.getNameAsString();
        int argCount = callExpr.getArguments().size();

        if (callExpr.getScope().isEmpty()) {
            return new MethodReference(currentClass, methodName, argCount, classCtx.filePath);
        }

        var scope = callExpr.getScope().get();

        if (scope instanceof ThisExpr) {
            return new MethodReference(currentClass, methodName, argCount, classCtx.filePath);
        }

        if (scope instanceof NameExpr nameExpr) {
            String varName = nameExpr.getNameAsString();
            String resolvedType = resolveVariableType(varName, localTypes, classCtx, allClasses);
            if (resolvedType != null) {
                resolvedType = resolveDirectType(resolvedType, classCtx, allClasses);
                ClassContext targetClass = findQualifiedClass(resolvedType, allClasses);
                Path targetFile = targetClass != null ? targetClass.filePath : null;
                return new MethodReference(resolvedType, methodName, argCount, targetFile);
            }

            String staticType = resolveDirectType(varName, classCtx, allClasses);
            ClassContext targetClass = findQualifiedClass(staticType, allClasses);
            if (targetClass != null) {
                return new MethodReference(
                        staticType, methodName, argCount, targetClass.filePath);
            }
        }

        if (scope instanceof FieldAccessExpr fae) {
            String resolvedType = resolveFieldAccessType(fae, currentClass, localTypes, classCtx, allClasses);
            if (resolvedType != null) {
                resolvedType = resolveDirectType(resolvedType, classCtx, allClasses);
                ClassContext targetClass = findQualifiedClass(resolvedType, allClasses);
                Path targetFile = targetClass != null ? targetClass.filePath : null;
                return new MethodReference(resolvedType, methodName, argCount, targetFile);
            }
        }

        return MethodReference.unresolved(methodName, argCount);
    }

    private String resolveDirectType(String type, ClassContext classCtx,
                                     Map<String, ClassContext> allClasses) {
        List<com.jsrc.app.symbol.SymbolResolver.TypeSymbol> symbols =
                new HashSet<>(allClasses.values()).stream()
                        .map(candidate -> {
                            String binaryName = candidate.packageName.isEmpty()
                                    ? candidate.qualifiedName
                                    : candidate.qualifiedName.substring(
                                            candidate.packageName.length() + 1);
                            return new com.jsrc.app.symbol.SymbolResolver.TypeSymbol(
                                    com.jsrc.app.model.TypeId.from(
                                            candidate.packageName, binaryName),
                                    candidate.imports,
                                    List.of(),
                                    List.of());
                        })
                        .toList();
        var resolver = new com.jsrc.app.symbol.SymbolResolver(symbols);
        String enclosingBinaryName = classCtx.packageName.isEmpty()
                ? classCtx.qualifiedName
                : classCtx.qualifiedName.substring(classCtx.packageName.length() + 1);
        var context = new com.jsrc.app.symbol.SymbolResolver.Context(
                classCtx.packageName,
                classCtx.imports,
                com.jsrc.app.model.TypeId.from(
                        classCtx.packageName, enclosingBinaryName));
        var resolution = resolver.resolveType(stripGenerics(type), context);
        if (resolution instanceof com.jsrc.app.symbol.SymbolResolver.Resolution.Found<
                com.jsrc.app.symbol.SymbolResolver.TypeSymbol> found) {
            return found.value().id().canonicalName();
        }
        return stripGenerics(type);
    }

    private ClassContext findQualifiedClass(String qualifiedName,
                                            Map<String, ClassContext> allClasses) {
        return new HashSet<>(allClasses.values()).stream()
                .filter(candidate -> candidate.qualifiedName.equals(qualifiedName))
                .findFirst()
                .orElse(null);
    }

    private List<String> argumentTypes(
            com.github.javaparser.ast.NodeList<Expression> arguments,
            String currentClass,
            Map<String, String> localTypes,
            ClassContext classCtx,
            Map<String, ClassContext> allClasses) {
        List<String> types = new java.util.ArrayList<>();
        for (Expression argument : arguments) {
            String type = argumentType(
                    argument, currentClass, localTypes, classCtx, allClasses);
            if (type == null) return List.of();
            types.add(com.jsrc.app.util.SignatureUtils.normalizeType(type));
        }
        return List.copyOf(types);
    }

    private String argumentType(Expression argument,
                                String currentClass,
                                Map<String, String> localTypes,
                                ClassContext classCtx,
                                Map<String, ClassContext> allClasses) {
        String resolved = resolveExpressionType(
                argument, currentClass, localTypes, classCtx, allClasses);
        if (resolved != null && !resolved.startsWith("?")) return resolved;
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

    /**
     * Resolves the type of a field access expression like {@code obj.field}.
     * Determines the type of {@code obj}, then looks up the field type in that class's context.
     */
    private String resolveFieldAccessType(FieldAccessExpr fae, String currentClass,
                                           Map<String, String> localTypes,
                                           ClassContext classCtx,
                                           Map<String, ClassContext> allClasses) {
        String fieldName = fae.getNameAsString();
        Expression objExpr = fae.getScope();

        String objType = resolveExpressionType(objExpr, currentClass, localTypes, classCtx, allClasses);
        if (objType == null) return null;

        // Look up the field type in the resolved class
        ClassContext ownerCtx = allClasses.get(objType);
        if (ownerCtx != null) {
            String fieldType = ownerCtx.fieldTypes.get(fieldName);
            if (fieldType != null) return stripGenerics(fieldType);
        }
        if (objType.startsWith("?")) {
            return "?field:" + objType + "." + fieldName;
        }
        return null;
    }

    /**
     * Resolves the type of an arbitrary expression: variable, this, field access chain.
     */
    private String resolveExpressionType(Expression expr, String currentClass,
                                          Map<String, String> localTypes,
                                          ClassContext classCtx,
                                          Map<String, ClassContext> allClasses) {
        if (expr instanceof ThisExpr) return currentClass;
        if (expr instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            String resolved = resolveVariableType(varName, localTypes, classCtx, allClasses);
            if (resolved != null) return resolved;
            if (allClasses.containsKey(varName)) return varName;
            return null;
        }
        if (expr instanceof FieldAccessExpr fae) {
            return resolveFieldAccessType(fae, currentClass, localTypes, classCtx, allClasses);
        }
        if (expr instanceof MethodCallExpr mce) {
            if (mce.getScope().isEmpty()) {
                return "?ret:" + currentClass + "." + mce.getNameAsString();
            }
            String scopeType = resolveExpressionType(
                    mce.getScope().get(), currentClass, localTypes, classCtx, allClasses);
            return scopeType == null
                    ? null
                    : "?ret:" + scopeType + "." + mce.getNameAsString();
        }
        return null;
    }

    private String resolveVariableType(String varName, Map<String, String> localTypes,
                                       ClassContext classCtx,
                                       Map<String, ClassContext> allClasses) {
        String type = localTypes.get(varName);
        if (type != null) return stripGenerics(type);

        type = classCtx.fieldTypes.get(varName);
        if (type != null) return stripGenerics(type);

        return null;
    }

    /**
     * Builds a map of variable/parameter name → type for a method or constructor.
     * Both extend CallableDeclaration, sharing the same parameter/local structure.
     */
    private Map<String, String> buildLocalTypeMap(com.github.javaparser.ast.body.CallableDeclaration<?> callable) {
        Map<String, String> types = new HashMap<>();
        for (Parameter param : callable.getParameters()) {
            types.put(param.getNameAsString(), param.getTypeAsString());
        }
        for (VariableDeclarator var : callable.findAll(VariableDeclarator.class)) {
            Node parent = var.getParentNode().orElse(null);
            if (parent != null && !(parent instanceof FieldDeclaration)) {
                types.put(var.getNameAsString(), var.getTypeAsString());
            }
        }
        return types;
    }

    private String stripGenerics(String type) {
        int idx = type.indexOf('<');
        return idx > 0 ? type.substring(0, idx) : type;
    }

    private CompilationUnit parseFile(Path path) {
        try {
            String source = Files.readString(path);
            var result = javaParser.parse(source);
            if (result.getResult().isPresent()) {
                if (!result.isSuccessful()) {
                    logger.debug("Parsed {} with {} warning(s)", path.getFileName(), result.getProblems().size());
                }
                return result.getResult().get();
            }
            logger.debug("Could not parse {}", path.getFileName());
        } catch (IOException ex) {
            logger.debug("Error reading {}: {}", path, ex.getMessage());
        }
        return null;
    }

    /**
     * Counts parameters from a method signature string.
     * E.g. "public void foo(String s, int x)" → 2, "void bar()" → 0.
     */
    /**
     * Finds a registered MethodReference by class+method name.
     * Returns the first match, or a new MR with -1 if not found.
     */
    private MethodReference resolveRegistered(String className, String methodName) {
        return resolveRegistered(className, methodName, -1);
    }

    private MethodReference resolveRegistered(MethodReference reference) {
        if (reference.hasKnownParameterTypes()) {
            Set<MethodReference> byName = methodsByName.get(reference.methodName());
            if (byName != null) {
                List<MethodReference> candidates = byName.stream()
                        .filter(candidate -> classMatches(
                                candidate.className(), reference.className()))
                        .filter(candidate -> parameterTypesMatch(
                                candidate.parameterTypes(), reference.parameterTypes()))
                        .toList();
                if (candidates.size() == 1) return candidates.getFirst();
            }
        }
        return resolveRegistered(reference.className(), reference.methodName(),
                reference.parameterCount());
    }

    private MethodReference resolveRegistered(String className, String methodName, int parameterCount) {
        Set<MethodReference> byName = methodsByName.get(methodName);
        if (byName != null) {
            List<MethodReference> candidates = byName.stream()
                    .filter(ref -> classMatches(ref.className(), className))
                    .filter(ref -> parameterCount < 0 || ref.parameterCount() == parameterCount)
                    .toList();
            if (candidates.size() == 1) return candidates.getFirst();
        }
        return new MethodReference(className, methodName, parameterCount, null);
    }

    private static boolean classMatches(String actual, String expected) {
        return com.jsrc.app.model.TypeId.namesMatch(actual, expected);
    }

    private static boolean parameterTypesMatch(
            List<String> actual, List<String> expected) {
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            if (!com.jsrc.app.util.SignatureUtils.sameErasedType(
                    actual.get(i), expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static class ClassContext {
        final Path filePath;
        final String qualifiedName;
        final String simpleName;
        final String packageName;
        final List<String> imports;
        final Map<String, String> fieldTypes = new HashMap<>();
        final Map<String, String> returnTypes = new HashMap<>();

        ClassContext(Path filePath) {
            this(filePath, null, null, "", List.of());
        }

        ClassContext(Path filePath, String qualifiedName, String simpleName,
                     String packageName, List<String> imports) {
            this.filePath = filePath;
            this.qualifiedName = qualifiedName;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.imports = List.copyOf(imports);
        }
    }

    private record SemanticMetadata(
            Map<String, String> fieldTypes,
            Map<String, String> returnTypes,
            Map<String, Set<String>> qualifiedNames) {
    }

}
