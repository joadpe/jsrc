package com.jsrc.app.command.architecture;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.jsrc.app.index.IndexedClass;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.MethodTargetResolver;
import com.jsrc.app.util.SignatureUtils;

/**
 * Validates whether a method reference exists in the codebase.
 * Anti-hallucination tool for AI agents: verify before generating calls.
 * <p>
 * If the method doesn't exist, suggests the closest match using:
 * <ul>
 *   <li>Levenshtein distance on method name</li>
 *   <li>Prefix/substring matching</li>
 *   <li>Same method name in different class</li>
 *   <li>Overloads with different param count</li>
 * </ul>
 */
public class ValidateCommand implements Command {

    private final String methodInput;

    public ValidateCommand(String methodInput) {
        this.methodInput = methodInput;
    }

    @Override
    public int execute(CommandContext ctx) {
        // Batch mode: "Class.method1;Class.method2" validates multiple refs
        if (methodInput.contains(";")) {
            return executeBatch(ctx);
        }
        var ref = MethodResolver.parse(methodInput);
        String methodName = ref.methodName();
        var graph = ctx.callGraph();

        // Find all methods with this name
        Set<MethodReference> byName = graph.findMethodsByName(methodName);

        // Also search index for richer info (signatures)
        Map<String, String> signatures = indexSignatures(ctx);

        // Filter by class if specified
        if (ref.hasClassName()) {
            var classMatch = inClass(new DeclaredClasses(ctx), byName, ref.className());

            if (!classMatch.isEmpty()) {
                // Filter by param count if specified
                if (ref.hasParamTypes()) {
                    var paramMatch = withArity(classMatch, ref.paramTypes().size());
                    if (!paramMatch.isEmpty()) {
                        return reportValidOrAmbiguous(ctx, ref, paramMatch, signatures);
                    }
                    // Call graph parameterCount may be wrong — fall back to index signatures
                    if (ctx.indexed() != null) {
                        var indexMatch = matchByIndexSignature(ctx, ref);
                        if (indexMatch != null) {
                            return reportValidFromIndex(ctx, indexMatch);
                        }
                    }
                    // Wrong param count — suggest overloads
                    return reportInvalid(ctx, ref, "Parameter count mismatch",
                            displayNames(knownArity(classMatch)));
                }
                return reportValidOrAmbiguous(ctx, ref, classMatch, signatures);
            }

            // Method not found in call graph for this class — try index
            if (ctx.indexed() != null) {
                var indexMatch = matchByIndexSignature(ctx, ref);
                if (indexMatch != null) {
                    return reportValidFromIndex(ctx, indexMatch);
                }
            }

            // Method not in this class — fuzzy search
            return reportInvalid(ctx, ref, "Method not found in " + ref.className(),
                    findClosest(ref, graph.getAllMethods()));
        }

        // No class specified — also try index
        if (byName.isEmpty() && ctx.indexed() != null) {
            var indexMatch = matchByIndexSignature(ctx, ref);
            if (indexMatch != null) {
                return reportValidFromIndex(ctx, indexMatch);
            }
        }
        if (byName.size() == 1) {
            return reportValid(ctx, byName.iterator().next(), signatures);
        }
        if (byName.size() > 1) {
            // Ambiguous
            return reportAmbiguous(ctx, ref, byName,
                    "Method exists in multiple classes. Use Class.method to disambiguate.");
        }

        // Not found — fuzzy search across all methods
        return reportInvalid(ctx, ref, "Method not found",
                findClosest(ref, graph.getAllMethods()));
    }

    /**
     * True when the requested name designates {@code className}. A request that starts with a
     * package is fully qualified and names that class alone (api.User, not com.acme.api.User);
     * any other request is a simple or nested name (Repo, Outer.Inner or Outer$Inner) of a class
     * in any package. Unresolved call-graph owners ("?", "?field:...") are not classes and never
     * match.
     */
    private static boolean namesClass(String className, String requested) {
        if (className.startsWith("?")) return false;
        String known = sourceName(className);
        String wanted = sourceName(requested);
        return known.equals(wanted) || (!startsWithPackage(wanted) && known.endsWith("." + wanted));
    }

    /** Class name with nested classes written like source code: app.Outer.Inner. */
    private static String sourceName(String className) {
        return className.replace('$', '.');
    }

    /** True for names that start with a package (lower-case first segment), unlike Repo or Outer.Inner. */
    private static boolean startsWithPackage(String name) {
        return name.indexOf('.') > 0 && Character.isLowerCase(name.charAt(0));
    }

    /**
     * Keeps the methods of every class the requested name designates. Names without a package,
     * as recorded for call sites the graph could not resolve (Repo next to app.Repo), are dropped
     * when a longer match ends with them, unless a class without a package is declared with that
     * name.
     */
    private static Set<MethodReference> inClass(DeclaredClasses declared,
                                                Set<MethodReference> methods, String requested) {
        Set<MethodReference> matching = methods.stream()
                .filter(m -> namesClass(m.className(), requested))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> owners = owners(matching);
        Set<String> unresolved = owners.stream()
                .filter(owner -> !startsWithPackage(owner)
                        && owners.stream().anyMatch(other -> other.endsWith("." + owner))
                        && !declared.contains(owner))
                .collect(Collectors.toSet());
        matching.removeIf(m -> unresolved.contains(sourceName(m.className())));
        return matching;
    }

    /**
     * Source names of the declared classes, read from the index or parsed from the files on first
     * use, so both modes give the same answer. A class without a package may be declared in a file
     * named after another class.
     */
    private static final class DeclaredClasses {
        private final CommandContext ctx;
        private Set<String> names;

        DeclaredClasses(CommandContext ctx) {
            this.ctx = ctx;
        }

        boolean contains(String sourceName) {
            if (names == null) {
                Stream<String> qualifiedNames = ctx.indexed() != null
                        ? ctx.indexed().getEntries().stream()
                                .flatMap(entry -> entry.classes().stream())
                                .map(IndexedClass::qualifiedName)
                        : ctx.javaFiles().stream()
                                .flatMap(file -> ctx.parser().parseClasses(file).stream())
                                .map(ClassInfo::qualifiedName);
                names = qualifiedNames.map(ValidateCommand::sourceName)
                        .collect(Collectors.toSet());
            }
            return names.contains(sourceName);
        }
    }

    private static Set<String> owners(Set<MethodReference> methods) {
        return methods.stream().map(m -> sourceName(m.className())).collect(Collectors.toSet());
    }

    /**
     * Drops call sites of unknown arity (e.g. method references) of classes with known overloads,
     * so they cannot stand in for a parameter count that no overload has.
     */
    private static Set<MethodReference> knownArity(Set<MethodReference> methods) {
        Set<String> known = methods.stream()
                .filter(m -> m.parameterCount() >= 0)
                .map(m -> sourceName(m.className()))
                .collect(Collectors.toSet());
        return methods.stream()
                .filter(m -> m.parameterCount() >= 0 || !known.contains(sourceName(m.className())))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<MethodReference> withArity(Set<MethodReference> methods, int count) {
        return knownArity(methods).stream()
                .filter(m -> m.parameterCount() < 0 || m.parameterCount() == count)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** A simple class name can designate several classes, e.g. one per package: list them, never pick one. */
    private int reportValidOrAmbiguous(CommandContext ctx, MethodResolver.MethodRef ref,
                                       Set<MethodReference> matches,
                                       Map<String, String> signatures) {
        if (owners(matches).size() > 1) {
            return reportAmbiguous(ctx, ref, matches,
                    "Class name matches several classes. Use the qualified class name to disambiguate.");
        }
        return reportValid(ctx, representative(matches, signatures), signatures);
    }

    /**
     * The match to report: the first indexed declaration in source order when there is one, so
     * the signature is its own and the choice among overloads is stable.
     */
    private static MethodReference representative(Set<MethodReference> matches,
                                                  Map<String, String> signatures) {
        Map<String, MethodReference> byKey = new LinkedHashMap<>();
        for (MethodReference match : matches) {
            byKey.putIfAbsent(signatureKey(match), match);
        }
        for (String key : signatures.keySet()) {
            MethodReference declared = byKey.get(key);
            if (declared != null) return declared;
        }
        return matches.iterator().next();
    }

    /** Index signatures keyed like call-graph references, plus one per parameter count. */
    private static Map<String, String> indexSignatures(CommandContext ctx) {
        Map<String, String> signatures = new LinkedHashMap<>();
        if (ctx.indexed() == null) return signatures;
        for (var entry : ctx.indexed().getEntries()) {
            for (var ic : entry.classes()) {
                for (var im : ic.methods()) {
                    String key = ic.qualifiedName() + "." + im.name();
                    List<String> types = SignatureUtils.extractParameterTypes(im.signature());
                    signatures.put(key + types, im.signature());
                    signatures.putIfAbsent(key + "/" + types.size(), im.signature());
                }
            }
        }
        return signatures;
    }

    private static String signatureKey(MethodReference method) {
        return method.className() + "." + method.methodName() + method.parameterTypes();
    }

    /** Signature of the method, or of an overload with its parameter count; never another arity. */
    private static String signatureOf(MethodReference method, Map<String, String> signatures) {
        String exact = signatures.get(signatureKey(method));
        if (exact != null || method.parameterCount() < 0) return exact;
        return signatures.get(method.className() + "." + method.methodName()
                + "/" + method.parameterCount());
    }

    private int reportValid(CommandContext ctx, MethodReference match,
                             Map<String, String> signatures) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("class", ctx.qualify(match.className()));
        result.put("method", match.methodName());
        String sig = signatureOf(match, signatures);
        if (sig != null) result.put("signature", sig);
        ctx.formatter().printResult(result);
        return 1;
    }

    private int reportInvalid(CommandContext ctx, MethodResolver.MethodRef ref,
                               String reason, List<String> closest) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", false);
        result.put("reason", reason);
        result.put("target", ref.hasClassName()
                ? ref.className() + "." + ref.methodName() : ref.methodName());
        if (!closest.isEmpty()) {
            result.put("closest", closest);
        }
        ctx.formatter().printResult(result);
        return 0;
    }

    private int reportAmbiguous(CommandContext ctx, MethodResolver.MethodRef ref,
                                 Set<MethodReference> matches, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("ambiguous", true);
        result.put("method", ref.methodName());
        result.put("candidates", displayNames(matches));
        result.put("message", message);
        ctx.formatter().printResult(result);
        return 1;
    }

    /** Sorted Class.method(Type, ...) displays of call-graph methods. */
    private static List<String> displayNames(Set<MethodReference> methods) {
        return methods.stream()
                .map(m -> MethodTargetResolver.displayName(m, Map.of()))
                .sorted().distinct().toList();
    }

    private List<String> findClosest(MethodResolver.MethodRef ref,
                                      Set<MethodReference> allMethods) {
        String target = ref.methodName();
        String targetClass = ref.hasClassName() ? ref.className() : null;
        List<ScoredMatch> scored = new ArrayList<>();

        for (MethodReference m : allMethods) {
            // Unresolved owners ("?", "?field:...") cannot be suggested as references
            if (m.methodName().equals("<init>") || m.className().startsWith("?")) continue;

            int score = 0;
            String name = m.methodName();

            // Exact name in different class
            if (name.equals(target) && targetClass != null && !m.className().equals(targetClass)) {
                score = 100;
            }
            // Levenshtein distance
            int dist = levenshtein(target.toLowerCase(), name.toLowerCase());
            if (dist <= 3 && dist > 0) {
                score = Math.max(score, 80 - dist * 10);
            }
            // Prefix match
            if (name.toLowerCase().startsWith(target.toLowerCase()) || target.toLowerCase().startsWith(name.toLowerCase())) {
                score = Math.max(score, 60);
            }
            // Substring match
            if (name.toLowerCase().contains(target.toLowerCase()) || target.toLowerCase().contains(name.toLowerCase())) {
                score = Math.max(score, 40);
            }

            if (score > 0) {
                scored.add(new ScoredMatch(MethodTargetResolver.displayName(m, Map.of()), score));
            }
        }

        return scored.stream()
                .sorted((a, b) -> Integer.compare(b.score, a.score))
                .map(s -> s.display)
                .distinct()
                .limit(5)
                .toList();
    }

    /** An indexed declaration found by {@link #matchByIndexSignature}. */
    private record IndexMatch(String className, String methodName, String signature) {}

    /**
     * Matches a method reference against index signatures (real parsed signatures).
     * Returns the first matching declaration or null if not found.
     */
    private IndexMatch matchByIndexSignature(CommandContext ctx, MethodResolver.MethodRef ref) {
        if (ctx.indexed() == null) return null;
        List<IndexedClass> classes = ctx.indexed().getEntries().stream()
                .flatMap(entry -> entry.classes().stream())
                .filter(ic -> !ref.hasClassName() || namesClass(ic.qualifiedName(), ref.className()))
                .toList();
        for (var ic : classes) {
            for (var im : ic.methods()) {
                if (!im.name().equals(ref.methodName()) || im.signature() == null) continue;
                if (!ref.hasParamTypes()
                        || countParamsInSignature(im.signature()) == ref.paramTypes().size()) {
                    return new IndexMatch(ic.qualifiedName(), im.name(), im.signature());
                }
            }
        }
        return null;
    }

    private int reportValidFromIndex(CommandContext ctx, IndexMatch match) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("class", match.className());
        result.put("method", match.methodName());
        result.put("signature", match.signature());
        ctx.formatter().printResult(result);
        return 1;
    }

    private static int countParamsInSignature(String signature) {
        int parenOpen = signature.indexOf('(');
        int parenClose = signature.lastIndexOf(')');
        if (parenOpen < 0 || parenClose < 0 || parenClose <= parenOpen + 1) return 0;
        String params = signature.substring(parenOpen + 1, parenClose).trim();
        if (params.isEmpty()) return 0;
        // Count commas outside of generics
        int count = 1;
        int depth = 0;
        for (char c : params.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    private record ScoredMatch(String display, int score) {}

    static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(dp[i - 1][j] + 1,
                        Math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost));
            }
        }
        return dp[a.length()][b.length()];
    }

    /**
     * Batch validation: validates multiple method refs separated by semicolons.
     * Returns a JSON object with per-entry results.
     */
    private int executeBatch(CommandContext ctx) {
        String[] refs = methodInput.split(";");
        List<Map<String, Object>> results = new ArrayList<>();
        int validCount = 0;

        var graph = ctx.callGraph();
        Map<String, String> signatures = indexSignatures(ctx);
        var declared = new DeclaredClasses(ctx);

        for (String rawRef : refs) {
            String trimmed = rawRef.trim();
            if (trimmed.isEmpty()) continue;

            var ref = MethodResolver.parse(trimmed);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ref", trimmed);

            Set<MethodReference> byName = graph.findMethodsByName(ref.methodName());
            if (ref.hasClassName()) {
                byName = inClass(declared, byName, ref.className());
                if (ref.hasParamTypes()) {
                    byName = withArity(byName, ref.paramTypes().size());
                }
            }

            if (ref.hasClassName() && owners(byName).size() > 1) {
                entry.put("valid", true);
                entry.put("ambiguous", true);
                entry.put("candidates", displayNames(byName));
                validCount++;
            } else if (!byName.isEmpty()) {
                var match = representative(byName, signatures);
                entry.put("valid", true);
                entry.put("class", match.className());
                entry.put("method", match.methodName());
                String sig = signatureOf(match, signatures);
                if (sig != null) entry.put("signature", sig);
                validCount++;
            } else {
                var indexMatch = matchByIndexSignature(ctx, ref);
                if (indexMatch != null) {
                    entry.put("valid", true);
                    entry.put("class", indexMatch.className());
                    entry.put("method", indexMatch.methodName());
                    entry.put("signature", indexMatch.signature());
                    validCount++;
                } else {
                    entry.put("valid", false);
                    var closest = findClosest(ref, graph.getAllMethods());
                    if (!closest.isEmpty()) {
                        entry.put("suggestions", closest.subList(0, Math.min(3, closest.size())));
                    }
                }
            }
            results.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", results.size());
        result.put("valid", validCount);
        result.put("invalid", results.size() - validCount);
        result.put("results", results);
        ctx.formatter().printResult(result);
        return validCount;
    }
}
