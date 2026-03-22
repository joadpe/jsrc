package com.jsrc.app.command.reverse;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.architecture.RuleEngine;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;

public class DriftCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        Map<String, Object> report = new LinkedHashMap<>();

        // Architecture check
        if (ctx.config() != null && !ctx.config().architecture().rules().isEmpty()) {
            var allClasses = ctx.getAllClasses();
            var engine = new RuleEngine(ctx.config().architecture());
            var violations = engine.evaluate(allClasses, ctx.javaFiles());
            report.put("architectureViolations", violations.size());
            if (!violations.isEmpty()) {
                report.put("violations", violations.stream().map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ruleId", v.ruleId());
                    m.put("class", v.className());
                    m.put("message", v.message());
                    return m;
                }).toList());
            }
        } else {
            report.put("architectureViolations", 0);
        }

        // Changed files via DiffCommand logic
        var diffCmd = new DiffCommand();
        // We can't easily reuse DiffCommand output, so just report count
        report.put("totalIssues", report.get("architectureViolations"));

        ctx.formatter().printResult(report);
        return ((Number) report.get("architectureViolations")).intValue();
    }
}
