package com.jsrc.app.command;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.architecture.InvokerResolver;
import com.jsrc.app.engine.CallersResult;
import com.jsrc.app.engine.JsrcEngine;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.project.ProjectModel;
import com.jsrc.app.project.SourceSet;
import com.jsrc.app.util.MethodTargetResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Adapts a CLI execution context to the in-process engine ports. */
public final class CommandEngineSource implements JsrcEngine.OverviewSource,
        JsrcEngine.ResolutionSource, JsrcEngine.CallersSource,
        JsrcEngine.SearchSource, JsrcEngine.ImpactSource {
    private final CommandContext context;
    private List<ClassInfo> classes;
    private Map<String, String> signatures;
    private Map<String, String> classPackages;

    public CommandEngineSource(CommandContext context) {
        this.context = context;
    }

    @Override
    public List<ClassInfo> classes() {
        if (classes == null) classes = context.getAllClasses();
        return classes;
    }

    @Override
    public int fileCount() {
        return context.indexed() != null
                ? context.indexed().fileCount() : context.javaFiles().size();
    }

    @Override
    public Map<SourceSet, Long> sourceSetCounts() {
        var counts = new LinkedHashMap<SourceSet, Long>();
        for (var sourceSet : SourceSet.values()) {
            counts.put(sourceSet, context.javaFiles().stream()
                    .filter(file -> context.sourceSet(file) == sourceSet).count());
        }
        return counts;
    }

    @Override
    public ProjectModel projectModel() { return context.projectModel(); }

    @Override
    public Optional<JsrcEngine.MethodDetails> findMethod(
            String typeName, String methodName) {
        if (context.indexed() == null) return Optional.empty();
        JsrcEngine.MethodDetails found = null;
        for (var entry : context.indexed().getEntries()) {
            for (var type : entry.classes()) {
                if (type.name().equals(typeName)) {
                    for (var method : type.methods()) {
                        if (method.name().equals(methodName)) {
                            found = new JsrcEngine.MethodDetails(
                                    method.signature(), method.returnType());
                            break;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public CallGraph callGraph() { return context.callGraph(); }

    @Override
    public Map<String, String> signatures() {
        if (signatures == null) {
            signatures = MethodTargetResolver.buildSignatureMap(context.indexed());
        }
        return signatures;
    }

    @Override
    public Map<String, String> classPackages() {
        if (classPackages == null) {
            classPackages = MethodTargetResolver.buildClassPackageMap(context.indexed());
        }
        return classPackages;
    }

    @Override
    public String qualify(String className) { return context.qualify(className); }

    @Override
    public List<CallersResult.ReflectiveCaller> reflectiveCallers(String methodName) {
        if (context.config() == null || context.config().architecture().invokers().isEmpty()
                || context.indexed() != null && context.indexed().hasCallEdges()) {
            return List.of();
        }
        var resolver = new InvokerResolver(context.config().architecture().invokers());
        var callers = new ArrayList<CallersResult.ReflectiveCaller>();
        for (var call : resolver.resolve(context.javaFiles())) {
            if (call.targetMethod().equals(methodName)) {
                callers.add(new CallersResult.ReflectiveCaller(
                        call.callerClass(), call.callerMethod(), call.line(),
                        call.targetClass()));
            }
        }
        return callers;
    }

    @Override
    public List<String> paths() {
        return context.javaFiles().stream().map(Path::toString).toList();
    }

    @Override
    public Optional<JsrcEngine.SearchDocument> document(String path) {
        Path file = Path.of(path);
        try {
            List<String> lines = Files.readAllLines(file);
            var parsedClasses = context.indexed() != null
                    ? context.indexed().findClassesInFile(path)
                    : context.parser().parseClasses(file);
            return Optional.of(new JsrcEngine.SearchDocument(path, lines, parsedClasses));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public long textUsages(String methodName) {
        long count = 0;
        for (Path file : context.javaFiles()) {
            try {
                for (String line : Files.readAllLines(file)) {
                    if (line.contains(methodName)) count++;
                }
            } catch (Exception e) {
                // Preserve the CLI's best-effort fallback for unreadable files.
            }
        }
        return count;
    }

}
