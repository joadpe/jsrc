package com.jsrc.app.command.callgraph;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.command.CommandEngineSource;
import com.jsrc.app.engine.JsrcEngine;
import com.jsrc.app.model.CommandHint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** CLI adapter for change-impact analysis. */
public class ImpactCommand implements Command {
    private final String methodInput;
    private final boolean whatIf;

    public ImpactCommand(String methodInput) {
        this(methodInput, false);
    }

    public ImpactCommand(String methodInput, boolean whatIf) {
        this.methodInput = methodInput;
        this.whatIf = whatIf;
    }

    @Override
    public int execute(CommandContext ctx) {
        var result = new JsrcEngine().impact(
                new CommandEngineSource(ctx), methodInput, whatIf);
        if (result instanceof JsrcEngine.ImpactResult.Missing missing) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("target", methodInput);
            if (missing.textUsages() > 0) {
                output.put("error", "Method not in call graph (class may be external)");
                output.put("textUsages", missing.textUsages());
                output.put("hint", "Use --search '"
                        + com.jsrc.app.util.MethodResolver.parse(methodInput).methodName()
                        + "' for detailed locations");
            } else {
                output.put("error", "Method not found");
            }
            if (missing.suggestion() != null) output.put("suggestion", missing.suggestion());
            ctx.formatter().printResult(output);
            return 0;
        }

        var found = (JsrcEngine.ImpactResult.Found) result;
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("target", found.target());
        output.put("directCallers", found.directCallers());
        output.put("transitiveCallers", found.transitiveCallers());
        output.put("affectedClasses", found.affectedClasses().stream().sorted().toList());
        output.put("riskLevel", found.riskLevel().name().toLowerCase(Locale.ROOT));
        if (whatIf) {
            output.put("affectedTests", found.affectedTests());
            output.put("testCount", found.testCount());
        }

        if (ctx.mdOutput()) {
            String markdown = toMarkdown(found);
            com.jsrc.app.output.MarkdownWriter.output(markdown, ctx.outDir(),
                    "impact-" + methodInput.replace(".", "-"));
            return found.transitiveCallers();
        }

        ctx.formatter().printResultWithHints(output, buildHints());
        return found.transitiveCallers();
    }

    private List<CommandHint> buildHints() {
        return List.of(
                new CommandHint("test-for " + methodInput, "Check test coverage"),
                new CommandHint("breaking-changes " + methodInput, "Full breaking change analysis"),
                new CommandHint("read " + methodInput, "Read the method source"));
    }

    private static String toMarkdown(JsrcEngine.ImpactResult.Found found) {
        String badge = switch (found.riskLevel()) {
            case HIGH -> "🔴 HIGH";
            case MEDIUM -> "🟡 MEDIUM";
            case LOW -> "🟢 LOW";
            case NONE -> "⚪ NONE";
        };
        var sb = new StringBuilder();
        sb.append("# Impact Analysis: `").append(found.target()).append("`\n\n");
        sb.append("**Risk Level:** ").append(badge).append("\n\n");
        sb.append("| Metric | Value |\n|--------|-------|\n");
        sb.append("| Direct callers | ").append(found.directCallers()).append(" |\n");
        sb.append("| Transitive callers | ").append(found.transitiveCallers()).append(" |\n\n");
        if (!found.affectedClasses().isEmpty()) {
            sb.append("## Affected Classes\n\n");
            for (String type : found.affectedClasses()) sb.append("- `").append(type).append("`\n");
        }
        return sb.toString();
    }
}
