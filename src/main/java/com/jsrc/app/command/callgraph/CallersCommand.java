package com.jsrc.app.command.callgraph;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.command.CommandEngineSource;
import com.jsrc.app.engine.CallersResult;
import com.jsrc.app.engine.JsrcEngine;
import com.jsrc.app.model.CommandHint;
import com.jsrc.app.model.HintContext;

import java.util.ArrayList;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        CallersResult result = new JsrcEngine().callers(
                new CommandEngineSource(ctx), methodInput);
        String methodName = result.method();
        if (result.status() == CallersResult.Status.AMBIGUOUS) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ambiguous", true);
            output.put("method", methodName);
            output.put("candidates", result.candidates());
            output.put("suggestions", result.candidates());
            output.put("message", "Multiple methods found. Use Class.method(Type1,Type2) to disambiguate.");
            ctx.formatter().printResult(output);
            return Math.max(1, result.candidates().size());
        }

        List<Map<String, Object>> callers = new ArrayList<>();
        for (var caller : result.callers()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("class", caller.className());
            entry.put("method", caller.method());
            switch (caller) {
                case CallersResult.DirectCaller direct -> {
                    if (direct.signature() != null) {
                        entry.put("signature", direct.signature());
                    }
                    entry.put("line", direct.line());
                    entry.put("type", "direct");
                    entry.put("dispatch", direct.dispatch().name()
                            .toLowerCase(java.util.Locale.ROOT));
                    entry.put("resolution", direct.resolution().name()
                            .toLowerCase(java.util.Locale.ROOT));
                    if (!direct.evidence().isEmpty()) {
                        entry.put("evidence", direct.evidence());
                    }
                }
                case CallersResult.ReflectiveCaller reflective -> {
                    entry.put("line", reflective.line());
                    entry.put("type", "reflective");
                    entry.put("targetClass", reflective.targetClass());
                }
            }
            callers.add(entry);
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
            ctx.formatter().printResultWithHints(mermaid, buildHints(callers));
        } else if (!ctx.fullOutput() && callers.size() > 0) {
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("method", methodInput);
            compact.put("total", callers.size());
            compact.put("callers", callers.stream()
                    .map(e -> Objects.toString(e.get("class"), "?") + "." + Objects.toString(e.get("method"), "?"))
                    .distinct()
                    .toList());
            ctx.formatter().printResultWithHints(compact, buildHints(callers));
        } else {
            ctx.formatter().printRefs(callers, "Callers", methodName);
        }
        return callers.size();
    }

    private List<CommandHint> buildHints(List<Map<String, Object>> callers) {
        String firstCaller = callers.isEmpty() ? "CLASS.METHOD"
                : Objects.toString(callers.getFirst().get("class"), "?") + "."
                + Objects.toString(callers.getFirst().get("method"), "?");
        return java.util.List.of(
            new CommandHint("read " + firstCaller, "Read the calling method"),
            new CommandHint("impact " + methodInput, "Full change risk assessment"),
            new CommandHint("call-chain " + methodInput, "Trace full call chain to roots"),
            new CommandHint("breaking-changes " + methodInput, "Impact of changing this class")
        );
    }
}
