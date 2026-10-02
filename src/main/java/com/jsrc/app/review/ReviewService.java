package com.jsrc.app.review;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.model.ResolutionLevel;
import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.review.GitChangeReader.Change;
import com.jsrc.app.review.ReviewReport.FileReview;
import com.jsrc.app.review.ReviewReport.SymbolReview;
import com.jsrc.app.review.ReviewReport.TestCandidate;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Composes Git snapshots, parsed declarations and the canonical call graph. */
public final class ReviewService {
    private final Path root;
    private final CodeParser parser;
    private final CallGraph graph;
    private final List<Path> javaFiles;

    public ReviewService(Path root, CodeParser parser, CallGraph graph, List<Path> javaFiles) {
        this.root = root.toAbsolutePath().normalize();
        this.parser = parser;
        this.graph = graph;
        this.javaFiles = List.copyOf(javaFiles);
    }

    public ReviewReport review(String ref) {
        var snapshot = new GitChangeReader(root).read(ref);
        List<FileReview> files = new ArrayList<>();
        List<SymbolReview> symbols = new ArrayList<>();
        Map<String, TestCandidate> tests = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (Change change : snapshot.changes()) {
            files.add(new FileReview(change.path(), change.status(), change.oldPath(), change.javaSource()));
            if (!change.javaSource()) {
                unresolved.add(change.path() + ": non-Java change; semantic impact not analyzed");
                continue;
            }
            analyze(change, symbols, tests, unresolved);
        }
        symbols.sort(Comparator.comparingInt((SymbolReview s) -> severity(s.risk())).reversed()
                .thenComparing(SymbolReview::path).thenComparing(SymbolReview::symbol));
        List<TestCandidate> sortedTests = tests.values().stream()
                .sorted(Comparator.comparing(TestCandidate::confidence)
                        .thenComparing(TestCandidate::path)).toList();
        return new ReviewReport(ref == null || ref.isBlank() ? "HEAD" : ref, snapshot.oid(),
                files, symbols, sortedTests, List.copyOf(unresolved));
    }

    private void analyze(Change change, List<SymbolReview> symbols,
                         Map<String, TestCandidate> tests, Set<String> unresolved) {
        Path beforePath = root.resolve(change.oldPath() == null ? change.path() : change.oldPath());
        Path afterPath = root.resolve(change.path());
        Map<String, ClassInfo> before = classes(beforePath, change.before(), unresolved);
        Map<String, ClassInfo> after = classes(afterPath, change.after(), unresolved);
        if (before.isEmpty() && after.isEmpty()) {
            unresolved.add(change.path() + ": no Java declarations resolved");
            return;
        }
        int initial = symbols.size();
        Set<String> types = new LinkedHashSet<>(before.keySet());
        types.addAll(after.keySet());
        for (String type : types) {
            int typeInitial = symbols.size();
            ClassInfo oldType = before.get(type);
            ClassInfo newType = after.get(type);
            if (!Objects.equals(typeContract(oldType), typeContract(newType))) {
                String contract = oldType == null ? "added" : newType == null ? "removed" : "changed";
                boolean publicContract = (isPublic(oldType) || isPublic(newType))
                        && (!Objects.equals(typeHeader(oldType), typeHeader(newType))
                        || !Objects.equals(publicFields(oldType), publicFields(newType)));
                List<String> reasons = List.of(publicContract ? "public type contract " + contract
                        : "type declaration " + contract);
                symbols.add(new SymbolReview(change.path(), type, type,
                        status(oldType, newType), typeContract(oldType), typeContract(newType),
                        contract, publicContract ? "high" : "medium", reasons, List.of(), "exact"));
            }
            Map<String, MethodInfo> oldMethods = methods(oldType);
            Map<String, MethodInfo> newMethods = methods(newType);
            Set<String> methodKeys = new LinkedHashSet<>(oldMethods.keySet());
            methodKeys.addAll(newMethods.keySet());
            for (String key : methodKeys) {
                MethodInfo oldMethod = oldMethods.get(key);
                MethodInfo newMethod = newMethods.get(key);
                if (oldMethod != null && newMethod != null
                        && Objects.equals(oldMethod.content(), newMethod.content())
                        && Objects.equals(methodContract(oldMethod), methodContract(newMethod))) continue;
                MethodInfo method = newMethod != null ? newMethod : oldMethod;
                String symbol = type + "." + key;
                String contract = oldMethod == null ? "added" : newMethod == null ? "removed"
                        : Objects.equals(methodContract(oldMethod), methodContract(newMethod))
                        ? "unchanged" : "changed";
                boolean constructor = method.name().equals(type.substring(type.lastIndexOf('.') + 1));
                if (constructor) contract = "unknown";
                boolean publicContract = !constructor && (isPublic(oldMethod) || isPublic(newMethod));
                List<String> reasons = new ArrayList<>();
                List<String> impacted = new ArrayList<>();
                String confidence = "exact";
                if (constructor) {
                    unresolved.add(symbol + ": constructor visibility is not reliable in parser");
                    confidence = "unknown";
                }
                if (duplicateType(type)) {
                    unresolved.add(symbol + ": duplicate qualified type across modules; call graph identity ambiguous");
                    confidence = "unknown";
                } else if (newMethod == null) {
                    unresolved.add(symbol + ": removed method has no current call graph node");
                    confidence = "unknown";
                } else {
                    Impact impact = impact(type, method);
                    impacted.addAll(impact.callers());
                    if (impact.missing()) {
                        unresolved.add(symbol + ": no matching method in current call graph");
                        confidence = "unknown";
                    } else if (impact.uncertain()) {
                        unresolved.add(symbol + ": call graph contains inferred or unresolved edges");
                        confidence = "inferred";
                    }
                    for (String path : impact.testPaths()) {
                        tests.put(path, new TestCandidate(path, "calls " + symbol,
                                impact.uncertain() ? "inferred" : "exact"));
                    }
                }
                if (!impacted.isEmpty()) reasons.add(impacted.size() + " caller(s) in call graph");
                if (publicContract && !"unchanged".equals(contract)) reasons.add("public method contract " + contract);
                if (constructor) reasons.add("constructor visibility unresolved");
                if (reasons.isEmpty()) reasons.add("method body changed; no resolved callers");
                String risk = publicContract && !"unchanged".equals(contract) ? "high"
                        : confidence.equals("unknown") ? "unknown"
                        : impacted.size() > 5 ? "high" : impacted.isEmpty() ? "low" : "medium";
                symbols.add(new SymbolReview(change.path(), type, symbol, status(oldMethod, newMethod),
                        methodContract(oldMethod), methodContract(newMethod), contract,
                        risk, reasons, impacted, confidence));
            }
            if (symbols.size() > typeInitial) namedTests(change.path(), type, tests);
        }
        if (symbols.size() == initial && !Objects.equals(change.before(), change.after())) {
            unresolved.add(change.path() + ": change is outside resolved declarations or formatting-only");
        }
        if (symbols.size() == initial && change.status().equals("renamed")) {
            unresolved.add(change.path() + ": path moved; packaging impact not analyzed");
        }
    }

    private Map<String, ClassInfo> classes(Path path, String source, Set<String> unresolved) {
        if (source == null) return Map.of();
        try {
            Map<String, ClassInfo> types = new LinkedHashMap<>();
            for (ClassInfo info : parser.parseClasses(path, source)) {
                if (types.put(info.qualifiedName(), info) != null) {
                    unresolved.add(path + ": duplicate parsed type " + info.qualifiedName());
                }
            }
            return types;
        } catch (RuntimeException ex) {
            unresolved.add(path + ": parser failed: " + ex.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static Map<String, MethodInfo> methods(ClassInfo type) {
        if (type == null) return Map.of();
        Map<String, MethodInfo> result = new LinkedHashMap<>();
        for (MethodInfo method : type.methods()) {
            result.put(methodKey(method), method);
        }
        return result;
    }

    private static String methodKey(MethodInfo method) {
        return method.name() + "(" + String.join(", ", method.parameters().stream()
                .map(MethodInfo.ParameterInfo::type).toList()) + ")";
    }

    private static String methodContract(MethodInfo method) {
        if (method == null) return null;
        return String.join(" ", method.modifiers()) + " " + method.returnType() + " "
                + methodKey(method) + " throws " + method.thrownExceptions()
                + " typeParameters " + method.typeParameters()
                + " annotations " + method.annotations();
    }

    private static String typeContract(ClassInfo type) {
        if (type == null) return null;
        return typeHeader(type) + " fields " + type.fields();
    }

    private static String typeHeader(ClassInfo type) {
        if (type == null) return null;
        return String.join(" ", type.modifiers()) + " " + type.qualifiedName()
                + " extends " + type.superClass() + " implements " + type.interfaces()
                + " typeParameters " + type.typeParameters()
                + " annotations " + type.annotations();
    }

    private static List<com.jsrc.app.parser.model.FieldInfo> publicFields(ClassInfo type) {
        if (type == null) return List.of();
        return type.fields().stream().filter(field -> field.modifiers().contains("public")
                || field.modifiers().contains("protected")).toList();
    }

    private static boolean isPublic(ClassInfo type) {
        return type != null && type.modifiers().contains("public");
    }

    private static boolean isPublic(MethodInfo method) {
        return method != null && (method.modifiers().contains("public")
                || method.modifiers().contains("protected"));
    }

    private static String status(Object before, Object after) {
        return before == null ? "added" : after == null ? "removed" : "modified";
    }

    private boolean duplicateType(String qualified) {
        String relative = qualified.replace('.', '/') + ".java";
        long matches = javaFiles.stream().map(Path::toString)
                .filter(path -> path.endsWith(relative)).count();
        return matches > 1;
    }

    private Impact impact(String qualifiedType, MethodInfo method) {
        List<String> parameters = method.parameters().stream()
                .map(MethodInfo.ParameterInfo::type).toList();
        Set<MethodReference> roots = graph.findMethodsByName(method.name()).stream()
                .filter(ref -> ref.className().equals(qualifiedType)
                        && ref.parameterTypes().equals(parameters)).collect(java.util.stream.Collectors.toSet());
        if (roots.isEmpty()) return new Impact(List.of(), List.of(), false, true);
        Set<MethodReference> visited = new HashSet<>(roots);
        ArrayDeque<MethodReference> queue = new ArrayDeque<>(roots);
        Set<String> callers = new LinkedHashSet<>();
        Set<String> tests = new LinkedHashSet<>();
        boolean uncertain = false;
        int steps = 0;
        while (!queue.isEmpty() && steps++ < 1000) {
            MethodReference current = queue.removeFirst();
            for (MethodCall edge : graph.getCallersOf(current)) {
                if (edge.resolutionLevel() != ResolutionLevel.EXACT) uncertain = true;
                MethodReference caller = edge.caller();
                Path callerFile = caller.filePath() == null
                        ? uniqueFileForType(caller.className()) : caller.filePath();
                if (callerFile != null && isTest(callerFile)) {
                    tests.add(relative(callerFile));
                } else if (!"?".equals(caller.className())) {
                    callers.add(caller.displayName());
                    if (callerFile == null) uncertain = true;
                } else {
                    uncertain = true;
                }
                if (visited.add(caller)) queue.addLast(caller);
            }
        }
        if (!queue.isEmpty()) uncertain = true;
        return new Impact(callers.stream().sorted().toList(), tests.stream().sorted().toList(),
                uncertain, false);
    }

    private Path uniqueFileForType(String qualified) {
        if ("?".equals(qualified)) return null;
        String outer = qualified.split("\\$", 2)[0];
        String suffix = outer.replace('.', '/') + ".java";
        List<Path> matches = javaFiles.stream().filter(path ->
                path.toString().replace('\\', '/').endsWith("/" + suffix)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private void namedTests(String changedPath, String type, Map<String, TestCandidate> tests) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        String module = changedPath.contains("/src/main/")
                ? changedPath.substring(0, changedPath.indexOf("/src/main/")) : "";
        for (Path file : javaFiles) {
            String path = relative(file);
            if (!isTest(file) || (module.isEmpty() ? !path.startsWith("src/test/")
                    : !path.startsWith(module + "/"))) continue;
            String name = file.getFileName().toString();
            if (name.equals(simple + "Test.java") || name.equals(simple + "Tests.java")) {
                tests.putIfAbsent(path, new TestCandidate(path, "name matches " + type, "heuristic"));
            }
        }
    }

    private String relative(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString().replace('\\', '/')
                : path.toString().replace('\\', '/');
    }

    private static boolean isTest(Path path) {
        String value = path.toString().replace('\\', '/');
        return value.contains("/src/test/") || value.contains("/src/testFixtures/");
    }

    private static int severity(String risk) {
        return switch (risk) { case "high" -> 4; case "unknown" -> 3;
            case "medium" -> 2; case "low" -> 1; default -> 0; };
    }

    private record Impact(List<String> callers, List<String> testPaths,
                          boolean uncertain, boolean missing) {}
}
