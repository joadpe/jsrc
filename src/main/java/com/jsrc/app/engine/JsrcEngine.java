package com.jsrc.app.engine;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.project.ProjectModel;
import com.jsrc.app.project.SourceSet;
import com.jsrc.app.util.ClassLookup;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.MethodTargetResolver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

/** In-process use cases. Sources are replaceable without invoking the CLI or filesystem. */
public final class JsrcEngine {
    public interface OverviewSource {
        List<ClassInfo> classes();
        int fileCount();
        Map<SourceSet, Long> sourceSetCounts();
        ProjectModel projectModel();
    }

    public interface ResolutionSource {
        List<ClassInfo> classes();
        Optional<MethodDetails> findMethod(String typeName, String methodName);
    }

    public record MethodDetails(String signature, String returnType) {}

    public interface CallersSource {
        CallGraph callGraph();
        Map<String, String> signatures();
        Map<String, String> classPackages();
        String qualify(String className);
        List<CallersResult.ReflectiveCaller> reflectiveCallers(String methodName);
    }

    public OverviewResult overview(OverviewSource source) {
        List<ClassInfo> types = source.classes();
        int classes = 0;
        int interfaces = 0;
        int methods = 0;
        var packages = new TreeSet<String>();
        for (var type : types) {
            if (type.isInterface()) interfaces++;
            else classes++;
            methods += type.methods().size();
            if (!type.packageName().isEmpty()) packages.add(type.packageName());
        }
        var topClasses = types.stream()
                .filter(type -> !type.isInterface())
                .sorted((left, right) -> Integer.compare(
                        right.methods().size(), left.methods().size()))
                .limit(10)
                .map(type -> new OverviewResult.TopClass(type.name(), type.methods().size()))
                .toList();
        return new OverviewResult(source.fileCount(), classes, interfaces, methods,
                List.copyOf(packages), topClasses,
                source.projectModel() == null ? Map.of() : source.sourceSetCounts(),
                source.projectModel());
    }

    public ResolutionResult resolve(ResolutionSource source, String expression) {
        String[] parts = expression.split("\\.");
        if (parts.length < 2) {
            return new ResolutionResult(ResolutionResult.Status.INVALID, expression,
                    null, null, null, null, null, null, null,
                    "Expected format: variable.method or Class.variable.method");
        }
        String contextClass = null;
        String variable;
        String method;
        if (parts.length >= 3 && Character.isUpperCase(parts[0].charAt(0))) {
            contextClass = parts[0];
            variable = parts[1];
            method = parts[2].replaceAll("\\(.*", "");
        } else {
            variable = parts[0];
            method = parts[1].replaceAll("\\(.*", "");
        }
        ClassInfo context = null;
        if (contextClass != null) {
            for (var type : source.classes()) {
                if (type.name().equals(contextClass)) {
                    context = type;
                    break;
                }
            }
            if (context == null) {
                return new ResolutionResult(ResolutionResult.Status.CONTEXT_NOT_FOUND,
                        expression, contextClass, variable, method, null, null, null, null,
                        "Context class not found: " + contextClass);
            }
        }
        String resolvedType = null;
        String resolvedVia = null;
        if (context != null) {
            for (var field : context.fields()) {
                if (field.name().equals(variable)) {
                    resolvedType = field.type();
                    resolvedVia = "field";
                    break;
                }
            }
        }
        if ("this".equals(variable) && context != null) {
            resolvedType = context.name();
            resolvedVia = "this";
        }
        if (resolvedType == null) {
            return new ResolutionResult(ResolutionResult.Status.UNRESOLVED, expression,
                    contextClass, variable, method, "unknown", null, null, null,
                    "Could not resolve type of '" + variable + "'");
        }
        var methodDetails = source.findMethod(resolvedType, method);
        return new ResolutionResult(ResolutionResult.Status.FOUND, expression,
                contextClass, variable, method, resolvedType, resolvedVia,
                methodDetails.map(MethodDetails::signature).orElse(null),
                methodDetails.map(MethodDetails::returnType).orElse(null), null);
    }

    public CallersResult callers(CallersSource source, String methodInput) {
        var ref = MethodResolver.parse(methodInput);
        var graph = source.callGraph();
        var resolved = MethodTargetResolver.resolve(ref, graph);
        if (resolved.isAmbiguous()) {
            return new CallersResult(CallersResult.Status.AMBIGUOUS,
                    ref.hasClassName() ? ref.className() + "." + ref.methodName()
                            : ref.methodName(),
                    MethodTargetResolver.buildCandidates(resolved.targets(),
                            source.signatures(), source.classPackages()), List.of());
        }
        var callers = new ArrayList<CallersResult.Caller>();
        for (var target : resolved.targets()) {
            for (var call : graph.getCallersOf(target)) {
                String callerClass = call.caller().className();
                String callerMethod = call.caller().methodName();
                callers.add(new CallersResult.DirectCaller(
                        source.qualify(callerClass), callerMethod,
                        source.signatures().get(callerClass + "." + callerMethod),
                        call.line(), call.invocationKind(),
                        call.resolutionLevel(), call.evidence()));
            }
        }
        callers.addAll(source.reflectiveCallers(ref.methodName()));
        return new CallersResult(callers.isEmpty() && resolved.targets().isEmpty()
                ? CallersResult.Status.NOT_FOUND : CallersResult.Status.FOUND,
                ref.methodName(), List.of(), callers);
    }

    /** Supplies source text and parsed classes without requiring filesystem access. */
    public interface SearchSource {
        List<String> paths();
        Optional<SearchDocument> document(String path);
    }

    public record SearchDocument(String path, List<String> lines, List<ClassInfo> classes) {
        public SearchDocument {
            lines = List.copyOf(lines);
            classes = List.copyOf(classes);
        }
    }

    public record SearchMatch(String file, int line, String context, String className,
                              String methodName, boolean inComment) {}

    public record SearchResult(List<SearchMatch> matches) {
        public SearchResult {
            matches = List.copyOf(matches);
        }
    }

    public SearchResult search(SearchSource source, String pattern) {
        String[] alternatives = pattern.contains("|") ? pattern.split("\\|") : new String[] {pattern};
        var matches = new ArrayList<SearchMatch>();
        for (String path : source.paths()) {
            var document = source.document(path);
            if (document.isEmpty()) continue;
            var lines = document.get().lines();
            boolean inBlockComment = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int patternIndex = firstMatch(line, alternatives);
                if (patternIndex >= 0) {
                    matches.add(searchMatch(document.get(), i + 1, line,
                            isPositionInComment(line, patternIndex, inBlockComment)));
                }
                inBlockComment = updateBlockCommentState(line, inBlockComment);
            }
        }
        return new SearchResult(matches);
    }

    private static SearchMatch searchMatch(SearchDocument document, int lineNumber,
                                            String line, boolean inComment) {
        String enclosingClass = "";
        String enclosingMethod = "";
        for (ClassInfo type : document.classes()) {
            if (lineNumber >= type.startLine() && lineNumber <= type.endLine()) {
                enclosingClass = type.name();
                for (var method : type.methods()) {
                    if (lineNumber >= method.startLine() && lineNumber <= method.endLine()) {
                        enclosingMethod = method.name();
                        break;
                    }
                }
                break;
            }
        }
        return new SearchMatch(document.path(), lineNumber, line.trim(),
                enclosingClass, enclosingMethod, inComment);
    }

    private static int firstMatch(String line, String[] alternatives) {
        int earliest = -1;
        for (String alternative : alternatives) {
            int index = line.indexOf(alternative.trim());
            if (index >= 0 && (earliest < 0 || index < earliest)) earliest = index;
        }
        return earliest;
    }

    private static boolean isPositionInComment(String line, int position, boolean inBlock) {
        boolean inString = false;
        boolean currentlyInBlock = inBlock;
        for (int i = 0; i < line.length() && i < position; i++) {
            char c = line.charAt(i);
            if (currentlyInBlock) {
                if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    currentlyInBlock = false;
                    i++;
                }
                continue;
            }
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') return true;
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                currentlyInBlock = true;
                i++;
            }
        }
        return currentlyInBlock;
    }

    private static boolean updateBlockCommentState(String line, boolean inBlock) {
        boolean inString = false;
        boolean currentlyInBlock = inBlock;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (currentlyInBlock) {
                if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    currentlyInBlock = false;
                    i++;
                }
                continue;
            }
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                currentlyInBlock = true;
                i++;
            }
        }
        return currentlyInBlock;
    }


    /** Graph and model data required for change-impact analysis. */
    public interface ImpactSource {
        CallGraph callGraph();
        List<ClassInfo> classes();
        String qualify(String className);
        long textUsages(String methodName);
    }

    public enum RiskLevel { NONE, LOW, MEDIUM, HIGH }

    public sealed interface ImpactResult {
        record Found(String target, int directCallers, List<String> affectedClasses,
                     RiskLevel riskLevel, List<String> affectedTests, int testCount)
                implements ImpactResult {
            public Found {
                affectedClasses = List.copyOf(affectedClasses);
                affectedTests = List.copyOf(affectedTests);
            }

            public int transitiveCallers() { return affectedClasses.size(); }
        }

        record Missing(String target, long textUsages, String suggestion)
                implements ImpactResult {}
    }

    public ImpactResult impact(ImpactSource source, String methodInput, boolean whatIf) {
        var ref = MethodResolver.parse(methodInput);
        CallGraph graph = source.callGraph();
        var resolved = MethodTargetResolver.resolve(ref, graph);
        if (resolved.targets().isEmpty()) {
            return new ImpactResult.Missing(methodInput, source.textUsages(ref.methodName()),
                    ClassLookup.findClosestClass(source.classes(),
                            ref.hasClassName() ? ref.className() : ref.methodName()));
        }

        Set<String> directCallerClasses = new LinkedHashSet<>();
        for (var target : resolved.targets()) {
            for (MethodCall call : graph.getCallersOf(target)) {
                String caller = call.caller().className();
                if (!"?".equals(caller)) directCallerClasses.add(source.qualify(caller));
            }
        }

        Set<String> affected = new LinkedHashSet<>(directCallerClasses);
        Set<MethodReference> visited = new HashSet<>(resolved.targets());
        Queue<MethodReference> queue = new ArrayDeque<>();
        for (var target : resolved.targets()) {
            for (MethodCall call : graph.getCallersOf(target)) {
                if (visited.add(call.caller())) queue.add(call.caller());
            }
        }
        int depth = 0;
        while (!queue.isEmpty() && depth < 30) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                MethodReference current = queue.remove();
                if (!"?".equals(current.className())) {
                    affected.add(source.qualify(current.className()));
                }
                for (MethodCall call : graph.getCallersOf(current)) {
                    if (visited.add(call.caller())) queue.add(call.caller());
                }
            }
            depth++;
        }

        int directCount = directCallerClasses.size();
        RiskLevel risk = directCount == 0 ? RiskLevel.NONE
                : directCount <= 3 ? RiskLevel.LOW
                : directCount <= 10 ? RiskLevel.MEDIUM : RiskLevel.HIGH;
        TestSelection tests = whatIf ? affectedTests(source.classes(), affected)
                : new TestSelection(List.of(), 0);
        return new ImpactResult.Found(methodInput, directCount,
                List.copyOf(affected), risk, tests.names(), tests.rawCount());
    }

    private record TestSelection(List<String> names, int rawCount) {}

    private static TestSelection affectedTests(List<ClassInfo> classes, Set<String> affected) {
        var tests = new ArrayList<String>();
        for (String affectedClass : affected) {
            String simple = affectedClass.contains(".")
                    ? affectedClass.substring(affectedClass.lastIndexOf('.') + 1)
                    : affectedClass;
            for (ClassInfo type : classes) {
                if (type.name().equals(simple + "Test") || type.name().equals(simple + "Tests")
                        || type.name().equals(simple + "IT")) {
                    tests.add(type.qualifiedName());
                }
            }
        }
        for (ClassInfo type : classes) {
            if (type.name().endsWith("Test") || type.name().endsWith("Tests")
                    || type.name().endsWith("IT")) {
                if (affected.contains(type.qualifiedName()) || affected.contains(type.name())) {
                    if (!tests.contains(type.qualifiedName())) tests.add(type.qualifiedName());
                }
            }
        }
        return new TestSelection(tests.stream().sorted().distinct().toList(), tests.size());
    }

}
