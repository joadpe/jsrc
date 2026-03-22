package com.jsrc.app.command.callgraph;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.*;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.analysis.ClassResolver;
import com.jsrc.app.analysis.PatternDetector;
import com.jsrc.app.architecture.LayerResolver;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.util.ClassLookup;

/**
 * Traces the execution flow DOWNWARD from a method (happy path).
 * Opposite of call-chain which goes upward.
 * Uses CallGraph.getCalleesOf() recursively.
 */
public class FlowCommand implements Command {

    private final String target;
    private final int maxDepth;

    public FlowCommand(String target, int maxDepth) {
        this.target = target;
        this.maxDepth = maxDepth;
    }

    @Override
    public int execute(CommandContext ctx) {
        final String className;
        final String methodName;
        if (target.contains(".")) {
            int dot = target.lastIndexOf('.');
            className = target.substring(0, dot);
            methodName = target.substring(dot + 1);
        } else {
            // Treat as class — use first public method or main
            className = target;
            methodName = null;
        }

        var allClasses = ctx.getAllClasses();
        ClassInfo ci = ClassLookup.resolveOrExit(allClasses, className);
        if (ci == null) return 0;

        CallGraph graph = ctx.callGraph();

        // Resolve method
        String resolvedMethod = methodName;
        if (resolvedMethod == null) {
            // Find first public non-constructor method, prefer main
            resolvedMethod = ci.methods().stream()
                    .filter(m -> !m.name().equals(ci.name())) // skip constructors
                    .map(m -> m.name())
                    .findFirst().orElse(null);
            if (resolvedMethod == null) return 0;
        }

        // Build layer resolver if config available
        LayerResolver layerResolver = null;
        if (ctx.config() != null && !ctx.config().architecture().layers().isEmpty()) {
            layerResolver = new LayerResolver(ctx.config().architecture().layers());
        }

        // Trace flow
        List<Map<String, Object>> flowSteps = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> layers = new LinkedHashSet<>();
        List<String> boundaries = new ArrayList<>();
        int[] dbQueries = {0};

        traceFlow(ci.name(), resolvedMethod, graph, ctx, allClasses, layerResolver,
                flowSteps, visited, layers, boundaries, dbQueries, 0);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entry", ci.name() + "." + resolvedMethod);
        result.put("totalDepth", flowSteps.size());
        result.put("flow", flowSteps);
        result.put("layers", List.copyOf(layers));
        if (!boundaries.isEmpty()) result.put("crossesBoundaries", boundaries);
        result.put("dbQueries", dbQueries[0]);

        ctx.formatter().printResult(result);
        return flowSteps.size();
    }

    private void traceFlow(String className, String methodName, CallGraph graph,
                            CommandContext ctx, List<ClassInfo> allClasses,
                            LayerResolver layerResolver,
                            List<Map<String, Object>> flowSteps, Set<String> visited,
                            Set<String> layers, List<String> boundaries,
                            int[] dbQueries, int depth) {
        if (depth > maxDepth) return;
        String key = className + "." + methodName;
        if (visited.contains(key)) return;
        visited.add(key);

        // Build step
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", flowSteps.size() + 1);
        step.put("method", key);

        // Resolve layer
        String layer = null;
        if (layerResolver != null) {
            ClassInfo ci = allClasses.stream()
                    .filter(c -> c.name().equals(className) || c.qualifiedName().equals(className))
                    .findFirst().orElse(null);
            if (ci != null) {
                layer = layerResolver.resolve(ci).orElse(null);
            }
        }
        if (layer == null) {
            // Infer from naming
            if (className.endsWith("Controller")) layer = "controller";
            else if (className.endsWith("Service")) layer = "service";
            else if (className.endsWith("Dao") || className.endsWith("DAO") || className.endsWith("Repository")) layer = "dao";
            else if (className.endsWith("Mapper")) layer = "mapper";
        }
        if (layer != null) {
            step.put("layer", layer);
            String prevLayer = layers.isEmpty() ? null : layers.stream().reduce((a, b) -> b).orElse(null);
            if (prevLayer != null && !prevLayer.equals(layer)) {
                boundaries.add(prevLayer + "→" + layer);
            }
            layers.add(layer);
        }

        // Check for DB access
        if (ClassResolver.isDaoClass(className, ctx)) {
            step.put("dbAccess", true);
            dbQueries[0]++;
        }

        flowSteps.add(step);

        // Follow callees
        Set<MethodReference> refs = graph.findMethodsByName(methodName);
        for (MethodReference ref : refs) {
            if (ref.className().equals(className)) {
                Set<MethodCall> callees = graph.getCalleesOf(ref);
                // Sort by line number for natural flow
                var sorted = new ArrayList<>(callees);
                sorted.sort(Comparator.comparingInt(MethodCall::line));

                for (MethodCall call : sorted) {
                    String calleeClass = call.callee().className();
                    String calleeMethod = call.callee().methodName();
                    // Skip self-calls and common framework methods
                    if (!calleeClass.equals(className) || !calleeMethod.equals(methodName)) {
                        traceFlow(calleeClass, calleeMethod, graph, ctx, allClasses,
                                layerResolver, flowSteps, visited, layers, boundaries, dbQueries, depth + 1);
                    }
                }
                break;
            }
        }
    }
}
