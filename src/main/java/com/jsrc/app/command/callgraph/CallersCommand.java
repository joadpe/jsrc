package com.jsrc.app.command.callgraph;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.model.CommandHint;
import com.jsrc.app.model.HintContext;

import java.util.ArrayList;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jsrc.app.architecture.InvokerResolver;
import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.MethodTargetResolver;

public class CallersCommand implements Command {
    private final String methodInput;
    private final boolean mermaidGraph;

    public CallersCommand(String methodInput) {
        this(methodInput, false);
    }

    public CallersCommand(String methodInput, boolean graph) {
        this.methodInput = methodInput;
        this.mermaidGraph = graph;
    }

    @Override
    public int execute(CommandContext ctx) {
        var ref = MethodResolver.parse(methodInput);
        String methodName = ref.methodName();

        CallGraph graph = ctx.callGraph();

        var resolved = MethodTargetResolver.resolve(ref, graph);
        var signatures = MethodTargetResolver.buildSignatureMap(ctx.indexed());
        var packages = MethodTargetResolver.buildClassPackageMap(ctx.indexed());
        var methodPackages = MethodTargetResolver.buildMethodPackageMap(ctx.indexed());

        if (resolved.isAmbiguous()) {
            var candidates = MethodTargetResolver.buildCandidates(resolved.targets(), signatures, packages);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ambiguous", true);
            result.put("method", ref.hasClassName()
                    ? ref.className() + "." + ref.methodName() : ref.methodName());
            result.put("candidates", candidates);
            result.put("message", "Multiple methods found. Use Class.method(Type1,Type2) to disambiguate.");
            ctx.formatter().printResult(result);
            return 0;
        }

        var targets = resolved.targets();

        List<Map<String, Object>> callers = new ArrayList<>();
        for (var target : targets) {
            for (var call : graph.getCallersOf(target)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                String callerClass = ctx.qualify(call.caller().className());
                String callerMethod = call.caller().methodName();
                entry.put("class", callerClass);
                entry.put("method", callerMethod);
                // Include caller signature from index when available
                String sigKey = call.caller().className() + "." + callerMethod;
                String sig = signatures.get(sigKey);
                if (sig != null) entry.put("signature", sig);

                entry.put("line", call.line());
                entry.put("type", "direct");
                callers.add(entry);
            }
        }

        // Add reflective callers — skip if index already has them
        if (ctx.config() != null && !ctx.config().architecture().invokers().isEmpty()
                && !(ctx.indexed() != null && ctx.indexed().hasCallEdges())) {
            var resolver = new InvokerResolver(ctx.config().architecture().invokers());
            for (var rc : resolver.resolve(ctx.javaFiles())) {
                if (rc.targetMethod().equals(methodName)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("class", rc.callerClass());
                    entry.put("method", rc.callerMethod());
                    entry.put("line", rc.line());
                    entry.put("type", "reflective");
                    entry.put("targetClass", rc.targetClass());
                    callers.add(entry);
                }
            }
        }

        if (mermaidGraph) {
            // Mermaid flowchart of callers
            var mermaid = new LinkedHashMap<String, Object>();
            mermaid.put("method", methodInput);
            mermaid.put("total", callers.size());
            var sb = new StringBuilder("graph LR\n");
            String targetNode = methodInput.replace(".", "_");
            sb.append("    ").append(targetNode).append("[\"").append(methodInput).append("\"]\n");
            callers.stream()
                    .map(e -> Objects.toString(e.get("class"), "") + "." + Objects.toString(e.get("method"), ""))
                    .distinct()
                    .forEach(caller -> {
                        String node = caller.replace(".", "_");
                        sb.append("    ").append(node).append("[\"").append(caller).append("\"]");
                        sb.append(" --> ").append(targetNode).append("\n");
                    });
            mermaid.put("mermaid", sb.toString());
            mermaid.put("callers", callers.stream()
                    .map(e -> Objects.toString(e.get("class"), "?") + "." + Objects.toString(e.get("method"), "?"))
                    .distinct().toList());
            ctx.formatter().printResultWithHints(mermaid, buildHints());
        } else if (!ctx.fullOutput() && callers.size() > 0) {
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("method", methodInput);
            compact.put("total", callers.size());
            compact.put("callers", callers.stream()
                    .map(e -> Objects.toString(e.get("class"), "?") + "." + Objects.toString(e.get("method"), "?"))
                    .distinct()
                    .toList());
            ctx.formatter().printResultWithHints(compact, buildHints());
        } else {
            ctx.formatter().printRefs(callers, "Callers", methodName);
        }
        return callers.size();
    }

    private List<CommandHint> buildHints() {
        return java.util.List.of(
            new CommandHint("read CALLER_CLASS.CALLER_METHOD", "Read the calling method"),
            new CommandHint("impact " + methodInput, "Full change risk assessment"),
            new CommandHint("call-chain " + methodInput, "Trace full call chain to roots"),
            new CommandHint("breaking-changes " + methodInput, "Impact of changing this class")
        );
    }
}
