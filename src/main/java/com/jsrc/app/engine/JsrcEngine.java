package com.jsrc.app.engine;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.project.ProjectModel;
import com.jsrc.app.project.SourceSet;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.MethodTargetResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
}
