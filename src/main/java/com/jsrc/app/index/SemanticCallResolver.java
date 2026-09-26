package com.jsrc.app.index;

import com.jsrc.app.model.InvocationKind;
import com.jsrc.app.model.ResolutionLevel;
import com.jsrc.app.model.TypeId;
import com.jsrc.app.symbol.SymbolResolver;
import com.jsrc.app.symbol.SymbolResolver.Context;
import com.jsrc.app.symbol.SymbolResolver.MethodSymbol;
import com.jsrc.app.symbol.SymbolResolver.Resolution;
import com.jsrc.app.symbol.SymbolResolver.TypeSymbol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves raw call edges into canonical, explainable dispatch targets. */
public final class SemanticCallResolver {

    private final SymbolResolver symbolResolver;
    private final List<IndexedClass> classes;
    private final Map<String, IndexedClass> classesByName;

    public SemanticCallResolver(List<IndexEntry> entries) {
        this.symbolResolver = IndexSymbolAdapter.create(entries);
        this.classes = entries.stream()
                .flatMap(entry -> entry.classes().stream())
                .toList();
        this.classesByName = new HashMap<>();
        for (IndexedClass indexedClass : classes) {
            classesByName.put(indexedClass.qualifiedName(), indexedClass);
            classesByName.putIfAbsent(indexedClass.name(), indexedClass);
        }
    }

    public List<CallEdge> resolve(CallEdge edge) {
        CallerContext caller = resolveCaller(edge);
        Resolution<TypeSymbol> receiverResolution = symbolResolver.resolveType(
                edge.calleeClass(), caller.context());
        if (!(receiverResolution instanceof Resolution.Found<TypeSymbol> receiver)) {
            return List.of(copy(
                    edge,
                    caller.className(),
                    edge.calleeClass(),
                    edge.invocationKind(),
                    ResolutionLevel.UNRESOLVED,
                    List.of("UNRESOLVED_RECEIVER")));
        }

        String receiverName = receiver.value().id().canonicalName();
        List<String> argumentTypes = edge.calleeParameterTypes().stream()
                .anyMatch(CallEdge.UNKNOWN_PARAMETER_TYPE::equals)
                ? null
                : edge.calleeParameterTypes();
        CallEdge preparedEdge = edge;
        if (argumentTypes == null) {
            List<String> inferredArguments = inferFunctionalArgumentTypes(
                    edge, caller.context());
            if (inferredArguments != null) {
                argumentTypes = inferredArguments;
                preparedEdge = withCalleeArguments(
                        edge,
                        inferredArguments,
                        "FUNCTIONAL_TYPE_FROM_CALL_ARGUMENT");
            }
        }
        MethodLookup lookup = resolveMethod(
                preparedEdge, receiverName, argumentTypes, caller.context());
        Resolution<MethodSymbol> methodResolution = lookup.resolution();
        argumentTypes = lookup.argumentTypes();
        CallEdge resolvedEdge = lookup.edge();
        if (!(methodResolution instanceof Resolution.Found<MethodSymbol> method)) {
            return List.of(copy(
                    resolvedEdge,
                    caller.className(),
                    receiverName,
                    resolvedEdge.invocationKind(),
                    ResolutionLevel.UNRESOLVED,
                    List.of(methodResolution instanceof Resolution.Ambiguous<?>
                            ? "AMBIGUOUS_TARGET"
                            : "UNRESOLVED_METHOD")));
        }

        InvocationKind kind = invocationKind(resolvedEdge, receiver.value(), method.value());
        if (kind == InvocationKind.STATIC
                || kind == InvocationKind.SPECIAL
                || kind == InvocationKind.CONSTRUCTOR) {
            return List.of(exact(resolvedEdge, caller.className(), method.value(), kind));
        }

        List<MethodSymbol> runtimeTargets = runtimeTargets(
                receiver.value(), resolvedEdge.calleeMethod(), argumentTypes, caller.context());
        if (runtimeTargets.isEmpty()) {
            if (isConcrete(method.value())) {
                return List.of(exact(resolvedEdge, caller.className(), method.value(), kind));
            }
            return List.of(copy(
                    resolvedEdge,
                    caller.className(),
                    method.value().owner().canonicalName(),
                    kind,
                    ResolutionLevel.UNRESOLVED,
                    List.of("ABSTRACT_TARGET_WITHOUT_IMPLEMENTATION")));
        }

        Map<String, MethodSymbol> uniqueTargets = new LinkedHashMap<>();
        for (MethodSymbol target : runtimeTargets) {
            uniqueTargets.put(target.id().canonicalName(), target);
        }
        boolean exact = uniqueTargets.size() == 1
                && uniqueTargets.values().iterator().next().id().equals(method.value().id());
        List<CallEdge> resolved = new ArrayList<>();
        for (MethodSymbol target : uniqueTargets.values()) {
            List<String> evidence = new ArrayList<>();
            evidence.add("SYMBOL_RESOLVER");
            ResolutionLevel level;
            if (exact) {
                evidence.add("EXACT_SIGNATURE");
                level = ResolutionLevel.EXACT;
            } else if (target.owner().equals(method.value().owner()) && isConcrete(target)) {
                IndexedClass targetOwner = classesByName.get(
                        target.owner().canonicalName());
                evidence.add(targetOwner != null && targetOwner.isInterface()
                        ? "INHERITED_DEFAULT"
                        : "DECLARED_TARGET");
                level = ResolutionLevel.INFERRED;
            } else {
                evidence.add("CHA_IMPLEMENTATION");
                level = ResolutionLevel.INFERRED;
            }
            resolved.add(copy(
                    resolvedEdge,
                    caller.className(),
                    target.owner().canonicalName(),
                    kind,
                    level,
                    evidence));
        }
        return List.copyOf(resolved);
    }

    private List<String> inferFunctionalArgumentTypes(
            CallEdge edge,
            Context callerContext) {
        String marker = edge.evidence().stream()
                .filter(value -> value.startsWith("FUNCTIONAL_ARGUMENT|"))
                .findFirst()
                .orElse(null);
        if (marker == null) return null;
        String[] parts = marker.split("\\|", -1);
        if (parts.length != 5) return null;

        int parameterCount;
        int argumentIndex;
        try {
            parameterCount = Integer.parseInt(parts[3]);
            argumentIndex = Integer.parseInt(parts[4]);
        } catch (NumberFormatException exception) {
            return null;
        }

        Resolution<TypeSymbol> ownerResolution = symbolResolver.resolveType(
                parts[1], callerContext);
        if (!(ownerResolution instanceof Resolution.Found<TypeSymbol> owner)) return null;
        IndexedClass indexedClass = classesByName.get(owner.value().id().canonicalName());
        if (indexedClass == null) return null;

        List<String> functionalTypes = indexedClass.methods().stream()
                .filter(method -> method.name().equals(parts[2]))
                .filter(method -> method.paramCount() == parameterCount)
                .map(method -> com.jsrc.app.util.SignatureUtils
                        .extractParameterTypes(method.signature()))
                .filter(parameters -> argumentIndex < parameters.size())
                .map(parameters -> parameters.get(argumentIndex))
                .distinct()
                .toList();
        if (functionalTypes.size() != 1) return null;
        return EdgeResolver.functionalInputTypes(functionalTypes.getFirst(), null);
    }

    private MethodLookup resolveMethod(
            CallEdge edge,
            String receiverName,
            List<String> argumentTypes,
            Context callerContext) {
        Resolution<MethodSymbol> direct = symbolResolver.resolveMethod(
                receiverName, edge.calleeMethod(), argumentTypes, callerContext);
        if (!edge.evidence().contains("TYPE_SCOPED_METHOD_REFERENCE")
                || argumentTypes == null
                || argumentTypes.isEmpty()
                || !TypeId.namesMatch(
                        com.jsrc.app.util.SignatureUtils.eraseParameterType(
                                argumentTypes.getFirst()),
                        receiverName)) {
            return new MethodLookup(direct, argumentTypes, edge);
        }

        List<String> unboundArguments = List.copyOf(
                argumentTypes.subList(1, argumentTypes.size()));
        Resolution<MethodSymbol> unbound = symbolResolver.resolveMethod(
                receiverName, edge.calleeMethod(), unboundArguments, callerContext);
        MethodSymbol staticTarget = direct instanceof Resolution.Found<MethodSymbol> found
                && isStatic(found.value()) ? found.value() : null;
        MethodSymbol instanceTarget = unbound instanceof Resolution.Found<MethodSymbol> found
                && !isStatic(found.value()) ? found.value() : null;

        if (staticTarget != null && instanceTarget != null) {
            return new MethodLookup(
                    new Resolution.Ambiguous<>(
                            List.of(staticTarget, instanceTarget),
                            List.of("Qualify the intended static or instance target")),
                    argumentTypes,
                    edge);
        }
        if (instanceTarget != null) {
            return new MethodLookup(
                    unbound,
                    unboundArguments,
                    withCalleeArguments(
                            edge,
                            unboundArguments,
                            "UNBOUND_INSTANCE_METHOD_REFERENCE"));
        }
        if (staticTarget != null) {
            return new MethodLookup(
                    direct,
                    argumentTypes,
                    withEvidence(edge, "STATIC_METHOD_REFERENCE"));
        }
        return new MethodLookup(
                new Resolution.Unresolved<>(
                        edge.calleeMethod(),
                        "No matching static or unbound instance method",
                        List.of()),
                argumentTypes,
                edge);
    }

    private boolean isStatic(MethodSymbol method) {
        IndexedMethod indexedMethod = findMethod(method);
        return indexedMethod != null && containsModifier(indexedMethod.signature(), "static");
    }

    private static CallEdge withCalleeArguments(
            CallEdge edge,
            List<String> argumentTypes,
            String evidence) {
        return new CallEdge(
                edge.callerClass(),
                edge.callerMethod(),
                edge.callerParameterTypes(),
                edge.callerParamCount(),
                edge.calleeClass(),
                edge.calleeMethod(),
                argumentTypes,
                edge.line(),
                argumentTypes.size(),
                edge.invocationKind(),
                edge.resolutionLevel(),
                appendEvidence(edge, evidence));
    }

    private static CallEdge withEvidence(CallEdge edge, String evidence) {
        return new CallEdge(
                edge.callerClass(),
                edge.callerMethod(),
                edge.callerParameterTypes(),
                edge.callerParamCount(),
                edge.calleeClass(),
                edge.calleeMethod(),
                edge.calleeParameterTypes(),
                edge.line(),
                edge.argCount(),
                edge.invocationKind(),
                edge.resolutionLevel(),
                appendEvidence(edge, evidence));
    }

    private static List<String> appendEvidence(CallEdge edge, String evidence) {
        var combined = new java.util.LinkedHashSet<>(edge.evidence());
        combined.add(evidence);
        return List.copyOf(combined);
    }

    private CallerContext resolveCaller(CallEdge edge) {
        Resolution<TypeSymbol> resolution = symbolResolver.resolveType(
                edge.callerClass(), Context.empty());
        if (resolution instanceof Resolution.Found<TypeSymbol> caller) {
            TypeSymbol type = caller.value();
            return new CallerContext(
                    type.id().canonicalName(),
                    new Context(type.id().packageName(), type.imports(), type.id()));
        }
        return new CallerContext(edge.callerClass(), Context.empty());
    }

    private InvocationKind invocationKind(
            CallEdge edge,
            TypeSymbol receiver,
            MethodSymbol method) {
        if (edge.invocationKind() != InvocationKind.UNKNOWN) {
            return edge.invocationKind();
        }
        IndexedMethod indexedMethod = findMethod(method);
        String signature = indexedMethod == null ? "" : indexedMethod.signature();
        if (containsModifier(signature, "static")) return InvocationKind.STATIC;
        if (containsModifier(signature, "private")) return InvocationKind.SPECIAL;
        IndexedClass owner = classesByName.get(method.owner().canonicalName());
        if (receiver.id().equals(method.owner()) && owner != null && owner.isInterface()) {
            return InvocationKind.INTERFACE;
        }
        if (owner != null && owner.isInterface()) return InvocationKind.INTERFACE;
        return InvocationKind.VIRTUAL;
    }

    private List<MethodSymbol> runtimeTargets(
            TypeSymbol receiver,
            String methodName,
            List<String> argumentTypes,
            Context callerContext) {
        List<MethodSymbol> targets = new ArrayList<>();
        for (IndexedClass candidate : classes) {
            if (candidate.isInterface() || candidate.isAbstract()) continue;
            if (!isSubtype(candidate, receiver.id(), new HashSet<>())) continue;

            Resolution<MethodSymbol> resolution = symbolResolver.resolveMethod(
                    candidate.qualifiedName(), methodName, argumentTypes, callerContext);
            if (resolution instanceof Resolution.Found<MethodSymbol> found
                    && isConcrete(found.value())) {
                targets.add(found.value());
            }
        }
        return targets;
    }

    private boolean isSubtype(
            IndexedClass candidate,
            TypeId expected,
            Set<String> visited) {
        if (!visited.add(candidate.qualifiedName())) return false;
        if (TypeId.namesMatch(candidate.qualifiedName(), expected.canonicalName())) return true;

        TypeId candidateId = TypeId.from(candidate.packageName(), candidate.name());
        Context context = new Context(candidate.packageName(), candidate.imports(), candidateId);
        for (String relation : candidate.superClass()) {
            if (relationMatches(relation, expected, context, visited)) return true;
        }
        for (String relation : candidate.interfaces()) {
            if (relationMatches(relation, expected, context, visited)) return true;
        }
        return false;
    }

    private boolean relationMatches(
            String relation,
            TypeId expected,
            Context context,
            Set<String> visited) {
        Resolution<TypeSymbol> resolution = symbolResolver.resolveType(relation, context);
        if (!(resolution instanceof Resolution.Found<TypeSymbol> found)) return false;
        if (found.value().id().equals(expected)) return true;
        IndexedClass parent = classesByName.get(found.value().id().canonicalName());
        return parent != null && isSubtype(parent, expected, visited);
    }

    private boolean isConcrete(MethodSymbol method) {
        IndexedClass owner = classesByName.get(method.owner().canonicalName());
        IndexedMethod indexedMethod = findMethod(method);
        if (indexedMethod == null) return owner == null || !owner.isInterface();
        String signature = indexedMethod.signature();
        if (containsModifier(signature, "abstract")) return false;
        if (owner != null && owner.isInterface()) {
            return containsModifier(signature, "default")
                    || containsModifier(signature, "static")
                    || containsModifier(signature, "private");
        }
        return true;
    }

    private IndexedMethod findMethod(MethodSymbol method) {
        IndexedClass owner = classesByName.get(method.owner().canonicalName());
        if (owner == null) return null;
        List<IndexedMethod> candidates = owner.methods().stream()
                .filter(candidate -> candidate.name().equals(method.name()))
                .filter(candidate -> candidate.paramCount() == method.parameterTypes().size())
                .toList();
        return candidates.stream()
                .filter(candidate -> method.startLine() > 0
                        && candidate.startLine() == method.startLine())
                .findFirst()
                .or(() -> candidates.stream()
                        .filter(candidate -> sameParameterIdentity(
                                com.jsrc.app.util.SignatureUtils.extractParameterTypes(
                                        candidate.signature()),
                                method.parameterTypes()))
                        .findFirst())
                .orElse(null);
    }

    private static boolean sameParameterIdentity(List<String> left, List<String> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            String leftType = com.jsrc.app.util.SignatureUtils.eraseParameterType(left.get(i));
            String rightType = com.jsrc.app.util.SignatureUtils.eraseParameterType(right.get(i));
            if (!leftType.equals(rightType)
                    && !leftType.endsWith("." + rightType)
                    && !rightType.endsWith("." + leftType)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsModifier(String signature, String modifier) {
        return (" " + signature + " ").contains(" " + modifier + " ");
    }

    private static CallEdge exact(
            CallEdge source,
            String callerClass,
            MethodSymbol target,
            InvocationKind kind) {
        return copy(
                source,
                callerClass,
                target.owner().canonicalName(),
                kind,
                ResolutionLevel.EXACT,
                List.of("SYMBOL_RESOLVER", "EXACT_SIGNATURE"));
    }

    private static CallEdge copy(
            CallEdge source,
            String callerClass,
            String calleeClass,
            InvocationKind kind,
            ResolutionLevel level,
            List<String> evidence) {
        var combinedEvidence = new java.util.LinkedHashSet<String>();
        combinedEvidence.addAll(source.evidence());
        combinedEvidence.addAll(evidence);
        return new CallEdge(
                callerClass,
                source.callerMethod(),
                source.callerParameterTypes(),
                source.callerParamCount(),
                calleeClass,
                source.calleeMethod(),
                source.calleeParameterTypes(),
                source.line(),
                source.argCount(),
                kind,
                level,
                List.copyOf(combinedEvidence));
    }

    private record CallerContext(String className, Context context) {}

    private record MethodLookup(
            Resolution<MethodSymbol> resolution,
            List<String> argumentTypes,
            CallEdge edge) {}
}
