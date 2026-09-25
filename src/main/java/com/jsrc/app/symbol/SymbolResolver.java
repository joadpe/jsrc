package com.jsrc.app.symbol;

import com.jsrc.app.model.TypeId;
import com.jsrc.app.model.MethodId;
import com.jsrc.app.util.SignatureUtils;
import java.util.List;

/** Resolves type and method identities against an immutable project symbol set. */
public final class SymbolResolver {

    public enum Access {
        PUBLIC,
        PROTECTED,
        PACKAGE,
        PRIVATE
    }

    public enum Evidence {
        EXACT,
        EXPLICIT_IMPORT,
        ENCLOSING_TYPE,
        SAME_PACKAGE,
        JAVA_LANG,
        WILDCARD_IMPORT,
        UNIQUE_PROJECT_MATCH,
        INHERITED
    }

    public sealed interface Resolution<T> {
        record Found<T>(T value, Evidence evidence) implements Resolution<T> {}
        record Ambiguous<T>(List<T> candidates, List<String> suggestions)
                implements Resolution<T> {
            public Ambiguous {
                candidates = List.copyOf(candidates);
                suggestions = List.copyOf(suggestions);
            }
        }
        record Unresolved<T>(String query, String reason, List<String> suggestions)
                implements Resolution<T> {
            public Unresolved {
                suggestions = List.copyOf(suggestions);
            }
        }
    }

    public record Context(String packageName, List<String> imports, TypeId enclosingType) {
        public Context {
            packageName = packageName == null ? "" : packageName;
            imports = List.copyOf(imports);
        }

        public static Context empty() {
            return new Context("", List.of(), null);
        }
    }

    public record TypeSymbol(
            TypeId id,
            List<String> imports,
            List<String> superClasses,
            List<String> interfaces,
            List<MethodSymbol> methods
    ) {
        public TypeSymbol {
            imports = List.copyOf(imports);
            superClasses = List.copyOf(superClasses);
            interfaces = List.copyOf(interfaces);
            methods = List.copyOf(methods);
        }

        public TypeSymbol(
                TypeId id,
                List<String> imports,
                List<String> superClasses,
                List<MethodSymbol> methods) {
            this(id, imports, superClasses, List.of(), methods);
        }

        public List<String> superTypes() {
            return java.util.stream.Stream.concat(
                    superClasses.stream(), interfaces.stream()).toList();
        }
    }

    public record MethodSymbol(
            MethodId id,
            List<String> parameterTypes,
            int startLine,
            int endLine,
            Access access) {
        public MethodSymbol {
            parameterTypes = List.copyOf(parameterTypes);
        }

        public MethodSymbol(
                MethodId id,
                List<String> parameterTypes,
                int startLine,
                int endLine) {
            this(id, parameterTypes, startLine, endLine, Access.PUBLIC);
        }

        public MethodSymbol(
                String name,
                List<String> parameterTypes,
                TypeId owner,
                int startLine,
                int endLine) {
            this(new MethodId(owner, name, parameterTypes),
                    parameterTypes, startLine, endLine, Access.PUBLIC);
        }

        public MethodSymbol(
                String name,
                List<String> parameterTypes,
                TypeId owner,
                int startLine,
                int endLine,
                Access access) {
            this(new MethodId(owner, name, parameterTypes),
                    parameterTypes, startLine, endLine, access);
        }

        public String name() {
            return id.name();
        }

        public TypeId owner() {
            return id.owner();
        }
    }

    private final List<TypeSymbol> types;

    public SymbolResolver(List<TypeSymbol> types) {
        this.types = List.copyOf(types);
    }

    public Resolution<TypeSymbol> resolveType(String query, Context context) {
        String normalizedQuery = SignatureUtils.eraseType(query);
        List<TypeSymbol> exact = types.stream()
                .filter(type -> type.id().matchesQualified(normalizedQuery))
                .toList();
        if (exact.size() == 1) {
            return new Resolution.Found<>(exact.getFirst(), Evidence.EXACT);
        }

        for (String imported : context.imports()) {
            if (imported.startsWith("static ") || imported.endsWith(".*")) continue;
            if (!imported.endsWith("." + normalizedQuery)) continue;
            List<TypeSymbol> importedMatches = types.stream()
                    .filter(type -> type.id().matchesQualified(imported))
                    .toList();
            if (importedMatches.size() == 1) {
                return new Resolution.Found<>(
                        importedMatches.getFirst(), Evidence.EXPLICIT_IMPORT);
            }
        }

        if (context.enclosingType() != null) {
            String nestedName = context.enclosingType().canonicalName() + "$" + normalizedQuery;
            List<TypeSymbol> nestedMatches = types.stream()
                    .filter(type -> type.id().matchesQualified(nestedName))
                    .toList();
            if (nestedMatches.size() == 1) {
                return new Resolution.Found<>(
                        nestedMatches.getFirst(), Evidence.ENCLOSING_TYPE);
            }
        }

        if (!context.packageName().isEmpty()) {
            String samePackageName = context.packageName() + "." + normalizedQuery;
            List<TypeSymbol> samePackageMatches = types.stream()
                    .filter(type -> type.id().matchesQualified(samePackageName))
                    .toList();
            if (samePackageMatches.size() == 1) {
                return new Resolution.Found<>(
                        samePackageMatches.getFirst(), Evidence.SAME_PACKAGE);
            }
        }

        List<TypeSymbol> wildcardMatches = context.imports().stream()
                .filter(imported -> imported.endsWith(".*"))
                .map(imported -> imported.substring(0, imported.length() - 2)
                        + "." + normalizedQuery)
                .flatMap(candidate -> types.stream()
                        .filter(type -> type.id().matchesQualified(candidate)))
                .distinct()
                .sorted(java.util.Comparator.comparing(type -> type.id().canonicalName()))
                .toList();
        if (wildcardMatches.size() == 1) {
            return new Resolution.Found<>(
                    wildcardMatches.getFirst(), Evidence.WILDCARD_IMPORT);
        }
        if (wildcardMatches.size() > 1) {
            List<String> suggestions = wildcardMatches.stream()
                    .map(type -> type.id().canonicalName())
                    .toList();
            return new Resolution.Ambiguous<>(wildcardMatches, suggestions);
        }

        List<TypeSymbol> projectMatches = types.stream()
                .filter(type -> type.id().simpleName().equals(normalizedQuery)
                        || type.id().binaryName().equals(normalizedQuery)
                        || type.id().relativeSourceName().equals(normalizedQuery))
                .sorted(java.util.Comparator.comparing(type -> type.id().canonicalName()))
                .toList();
        if (projectMatches.size() == 1) {
            return new Resolution.Found<>(
                    projectMatches.getFirst(), Evidence.UNIQUE_PROJECT_MATCH);
        }
        if (projectMatches.size() > 1) {
            List<String> suggestions = projectMatches.stream()
                    .map(type -> type.id().canonicalName())
                    .toList();
            return new Resolution.Ambiguous<>(projectMatches, suggestions);
        }

        return new Resolution.Unresolved<>(query, "No matching type", List.of());
    }

    public Resolution<MethodSymbol> resolveMethod(
            String ownerQuery,
            String methodName,
            List<String> parameterTypes,
            Context context) {
        Resolution<TypeSymbol> ownerResolution = resolveType(ownerQuery, context);
        if (ownerResolution instanceof Resolution.Found<TypeSymbol> foundOwner) {
            List<MethodSymbol> matches = bestMatches(foundOwner.value().methods().stream()
                    .filter(method -> method.name().equals(methodName))
                    .toList(), parameterTypes);
            if (matches.size() == 1) {
                return new Resolution.Found<>(matches.getFirst(), Evidence.EXACT);
            }
            if (matches.size() > 1) {
                List<String> suggestions = matches.stream()
                        .map(SymbolResolver::methodSuggestion)
                        .sorted()
                        .toList();
                return new Resolution.Ambiguous<>(matches, suggestions);
            }

            List<MethodSymbol> inherited = findInheritedMethods(
                    foundOwner.value(), methodName, parameterTypes,
                    new java.util.HashSet<>());
            if (inherited.size() == 1) {
                return new Resolution.Found<>(inherited.getFirst(), Evidence.INHERITED);
            }
            if (inherited.size() > 1) {
                List<String> suggestions = inherited.stream()
                        .map(SymbolResolver::methodSuggestion)
                        .sorted()
                        .toList();
                return new Resolution.Ambiguous<>(inherited, suggestions);
            }
        }
        return new Resolution.Unresolved<>(
                ownerQuery + "." + methodName, "No matching method", List.of());
    }

    /** Returns all project methods matching a name and optional parameter types. */
    public List<MethodSymbol> findMethods(
            String methodName, List<String> parameterTypes) {
        return types.stream()
                .flatMap(type -> type.methods().stream())
                .filter(method -> method.name().equals(methodName))
                .filter(method -> parametersMatch(method.parameterTypes(), parameterTypes))
                .sorted(java.util.Comparator.comparing(method -> method.id().canonicalName()))
                .toList();
    }

    private static boolean parametersMatch(
            List<String> actualTypes, List<String> expectedTypes) {
        return parameterMatchScore(actualTypes, expectedTypes) >= 0;
    }

    private static List<MethodSymbol> bestMatches(
            List<MethodSymbol> candidates, List<String> argumentTypes) {
        if (argumentTypes == null) return candidates;
        int bestScore = Integer.MAX_VALUE;
        List<MethodSymbol> matches = new java.util.ArrayList<>();
        for (MethodSymbol candidate : candidates) {
            int score = parameterMatchScore(candidate.parameterTypes(), argumentTypes);
            if (score < 0 || score > bestScore) continue;
            if (score < bestScore) {
                matches.clear();
                bestScore = score;
            }
            matches.add(candidate);
        }
        return List.copyOf(matches);
    }

    private static int parameterMatchScore(
            List<String> parameterTypes, List<String> argumentTypes) {
        if (argumentTypes == null) return 0;
        if (parameterTypes.size() != argumentTypes.size()) return -1;
        int total = 0;
        for (int i = 0; i < parameterTypes.size(); i++) {
            int score = SignatureUtils.invocationMatchScore(
                    parameterTypes.get(i), argumentTypes.get(i));
            if (score < 0) return -1;
            total += score;
        }
        return total;
    }

    private List<MethodSymbol> findInheritedMethods(
            TypeSymbol owner,
            String methodName,
            List<String> parameterTypes,
            java.util.Set<TypeId> visited) {
        if (!visited.add(owner.id())) return List.of();

        List<MethodSymbol> classMatches = new java.util.ArrayList<>();
        Context ownerContext = new Context(
                owner.id().packageName(), owner.imports(), owner.id());
        for (String superType : owner.superClasses()) {
            Resolution<TypeSymbol> resolution = resolveType(superType, ownerContext);
            if (!(resolution instanceof Resolution.Found<TypeSymbol> found)) continue;

            List<MethodSymbol> declared = bestMatches(found.value().methods().stream()
                    .filter(method -> method.name().equals(methodName))
                    .filter(method -> isInheritedBy(method, owner.id()))
                    .map(method -> substitute(method, superType, found.value()))
                    .toList(), parameterTypes);
            if (!declared.isEmpty()) {
                classMatches.addAll(declared);
            } else {
                classMatches.addAll(findInheritedMethods(
                        found.value(), methodName, parameterTypes, visited));
            }
        }
        if (!classMatches.isEmpty()) {
            return classMatches.stream().distinct().toList();
        }

        List<MethodSymbol> interfaceMatches = new java.util.ArrayList<>();
        for (String interfaceType : owner.interfaces()) {
            Resolution<TypeSymbol> resolution = resolveType(interfaceType, ownerContext);
            if (!(resolution instanceof Resolution.Found<TypeSymbol> found)) continue;
            List<MethodSymbol> declared = bestMatches(found.value().methods().stream()
                    .filter(method -> method.name().equals(methodName))
                    .filter(method -> isInheritedBy(method, owner.id()))
                    .map(method -> substitute(method, interfaceType, found.value()))
                    .toList(), parameterTypes);
            if (!declared.isEmpty()) {
                interfaceMatches.addAll(declared);
            } else {
                interfaceMatches.addAll(findInheritedMethods(
                        found.value(), methodName, parameterTypes, visited));
            }
        }
        return interfaceMatches.stream()
                .filter(candidate -> interfaceMatches.stream().noneMatch(other ->
                        !other.owner().equals(candidate.owner())
                                && isSubtype(other.owner(), candidate.owner(), new java.util.HashSet<>())))
                .distinct()
                .toList();
    }

    private static boolean isInheritedBy(MethodSymbol method, TypeId inheritingType) {
        return method.access() != Access.PRIVATE
                && (method.access() != Access.PACKAGE
                        || method.owner().packageName().equals(inheritingType.packageName()));
    }

    private MethodSymbol substitute(
            MethodSymbol method, String relation, TypeSymbol declaringType) {
        int open = relation.indexOf('<');
        int close = relation.lastIndexOf('>');
        if (open < 0 || close <= open) return method;
        List<String> arguments = SignatureUtils.parseParameterTypes(
                relation.substring(open + 1, close));
        List<String> variables = declaringType.methods().stream()
                .flatMap(candidate -> candidate.parameterTypes().stream())
                .map(SignatureUtils::eraseType)
                .filter(type -> type.matches("[A-Z]"))
                .distinct()
                .toList();
        if (variables.size() != arguments.size()) return method;
        List<String> substituted = method.parameterTypes().stream()
                .map(parameter -> {
                    String result = parameter;
                    for (int i = 0; i < variables.size(); i++) {
                        result = result.replaceAll(
                                "\\b" + java.util.regex.Pattern.quote(variables.get(i)) + "\\b",
                                java.util.regex.Matcher.quoteReplacement(arguments.get(i)));
                    }
                    return result;
                })
                .toList();
        return new MethodSymbol(
                method.name(), substituted, method.owner(),
                method.startLine(), method.endLine(), method.access());
    }

    private boolean isSubtype(TypeId possibleSubtype, TypeId possibleSupertype,
                              java.util.Set<TypeId> visited) {
        if (!visited.add(possibleSubtype)) return false;
        TypeSymbol subtype = types.stream()
                .filter(type -> type.id().equals(possibleSubtype))
                .findFirst()
                .orElse(null);
        if (subtype == null) return false;
        Context context = new Context(
                subtype.id().packageName(), subtype.imports(), subtype.id());
        for (String relation : subtype.superTypes()) {
            Resolution<TypeSymbol> resolved = resolveType(relation, context);
            if (resolved instanceof Resolution.Found<TypeSymbol> found
                    && (found.value().id().equals(possibleSupertype)
                            || isSubtype(found.value().id(), possibleSupertype, visited))) {
                return true;
            }
        }
        return false;
    }

    private static String methodSuggestion(MethodSymbol method) {
        return method.owner().canonicalName() + "." + method.name()
                + "(" + String.join(",", method.parameterTypes()) + ")";
    }
}
