package com.jsrc.app.command.callgraph;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.model.CommandHint;
import com.jsrc.app.model.HintContext;

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
        final com.jsrc.app.util.MethodResolver.MethodRef requested;
        if (target.contains(".")) {
            requested = com.jsrc.app.util.MethodResolver.parse(target);
            className = requested.className();
        } else {
            // Treat as class — use first public method or main
            className = target;
            requested = null;
        }

        var allClasses = ctx.getAllClasses();
        ClassInfo ci = ClassLookup.resolveOrExit(allClasses, className);
        if (ci == null) return 0;

        CallGraph graph = ctx.callGraph();

        com.jsrc.app.util.MethodResolver.MethodRef effectiveTarget = requested;
        if (effectiveTarget == null) {
            // Find first public non-constructor method, prefer main
            var selected = ci.methods().stream()
                    .filter(m -> !m.name().equals(ci.name())) // skip constructors
                    .findFirst().orElse(null);
            if (selected == null) return 0;
            effectiveTarget = new com.jsrc.app.util.MethodResolver.MethodRef(
                    ci.qualifiedName(),
                    selected.name(),
                    selected.parameters().stream().map(parameter -> parameter.type()).toList());
        }

        var resolved = com.jsrc.app.util.MethodTargetResolver.resolve(
                effectiveTarget, graph);
        if (resolved.isAmbiguous()) {
            var signatures = com.jsrc.app.util.MethodTargetResolver
                    .buildSignatureMap(ctx.indexed());
            var packages = com.jsrc.app.util.MethodTargetResolver
                    .buildClassPackageMap(ctx.indexed());
            var candidates = com.jsrc.app.util.MethodTargetResolver.buildCandidates(
                    resolved.targets(), signatures, packages);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ambiguous", true);
            result.put("method", target);
            result.put("candidates", candidates);
            result.put("suggestions", candidates);
            result.put("message",
                    "Multiple methods found. Use Class.method(Type1,Type2) to disambiguate.");
            ctx.formatter().printResult(result);
            return Math.max(1, candidates.size());
        }
        if (!resolved.isResolved()) return 0;
        MethodReference entryMethod = resolved.targets().iterator().next();

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

        traceFlow(entryMethod, null, graph, ctx, allClasses, layerResolver,
                flowSteps, visited, layers, boundaries, dbQueries, 0);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entry", entryMethod.className() + "." + entryMethod.methodName());
        result.put("totalDepth", flowSteps.size());
        result.put("flow", flowSteps);
        result.put("layers", List.copyOf(layers));
        if (!boundaries.isEmpty()) result.put("crossesBoundaries", boundaries);
        result.put("dbQueries", dbQueries[0]);

        ctx.formatter().printResultWithHints(result, buildHints(flowSteps));
        return flowSteps.size();
    }

    private List<CommandHint> buildHints(List<Map<String, Object>> flowSteps) {
        String firstMethod = flowSteps.isEmpty() ? "CLASS.METHOD" 
            : Objects.toString(flowSteps.getFirst().get("method"), "CLASS.METHOD");
        return java.util.List.of(
            new CommandHint("read " + firstMethod, "Read a method in the flow"),
            new CommandHint("callers " + target, "Who triggers this flow?"),
            new CommandHint("callees " + target, "What does this method call?")
        );
    }

    private void traceFlow(MethodReference method, MethodCall incomingCall,
                            CallGraph graph,
                            CommandContext ctx, List<ClassInfo> allClasses,
                            LayerResolver layerResolver,
                            List<Map<String, Object>> flowSteps, Set<String> visited,
                            Set<String> layers, List<String> boundaries,
                            int[] dbQueries, int depth) {
        if (depth > maxDepth) return;
        String className = method.className();
        String methodName = method.methodName();
        String visitKey = className + "." + methodName
                + "(" + String.join(",", method.parameterTypes()) + ")";
        if (!visited.add(visitKey)) return;
        String displayName = className + "." + methodName;

        // Build step
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", flowSteps.size() + 1);
        step.put("method", displayName);
        if (incomingCall != null) {
            step.put("dispatch", incomingCall.invocationKind().name().toLowerCase(Locale.ROOT));
            step.put("resolution", incomingCall.resolutionLevel().name().toLowerCase(Locale.ROOT));
            if (!incomingCall.evidence().isEmpty()) {
                step.put("evidence", incomingCall.evidence());
            }
        }

        // Resolve layer
        String layer = null;
        if (layerResolver != null) {
            ClassInfo ci = allClasses.stream()
                    .filter(c -> com.jsrc.app.model.TypeId.namesMatch(
                            c.qualifiedName(), className))
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
        Set<MethodCall> callees = graph.getCalleesOf(method);
        var sorted = new ArrayList<>(callees);
        sorted.sort(Comparator.comparingInt(MethodCall::line));
        for (MethodCall call : sorted) {
            if (!call.callee().equals(method)) {
                traceFlow(call.callee(), call, graph, ctx, allClasses,
                        layerResolver, flowSteps, visited, layers, boundaries,
                        dbQueries, depth + 1);
            }
        }
    }
}
