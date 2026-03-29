package com.jsrc.app.command.architecture;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.architecture.RuleEngine;
import com.jsrc.app.architecture.Violation;
import com.jsrc.app.model.CommandHint;
import com.jsrc.app.parser.model.ClassInfo;

public class CheckCommand implements Command {
    private final String ruleId;

    public CheckCommand(String ruleId) {
        this.ruleId = ruleId;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        List<Violation> violations = new ArrayList<>();

        // User-defined rules from .jsrc.yaml
        if (ctx.config() != null && !ctx.config().architecture().rules().isEmpty()) {
            var engine = new RuleEngine(ctx.config().architecture());
            violations.addAll(ruleId != null
                    ? engine.evaluateRule(ruleId, allClasses, ctx.javaFiles())
                    : engine.evaluate(allClasses, ctx.javaFiles()));
        }

        // Built-in rules (always active unless specific ruleId requested)
        if (ruleId == null || "internal-import".equals(ruleId)) {
            violations.addAll(checkInternalImports(ctx));
        }

        if (violations.isEmpty() && (ctx.config() == null || ctx.config().architecture().rules().isEmpty())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("violations", 0);
            result.put("hint", "Add architecture rules to .jsrc.yaml for custom checks. Built-in: internal-import.");

            var hints = java.util.List.of(
                new CommandHint("read VIOLATION_CLASS", "Read the violating class"),
                new CommandHint("layer LAYER", "List classes in a layer")
            );

            ctx.formatter().printResultWithHints(result, hints);
            return 0;
        }

        ctx.formatter().printViolations(violations);
        return violations.size();
    }

    /** Built-in rule: detect imports from *.internal.* packages. */
    private List<Violation> checkInternalImports(CommandContext ctx) {
        List<Violation> violations = new ArrayList<>();
        if (ctx.indexed() == null) return violations;

        for (var entry : ctx.indexed().getEntries()) {
            for (var ic : entry.classes()) {
                for (String imp : ic.imports()) {
                    if (imp.contains(".internal.")) {
                        violations.add(new Violation(
                                "internal-import", ic.qualifiedName(),
                                "Imports internal package: " + imp, entry.path(), 0));
                    }
                }
            }
        }
        return violations;
    }
}
