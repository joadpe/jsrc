package com.jsrc.app.command;

import com.jsrc.app.ExitCode;
import com.jsrc.app.architecture.RuleEngine;

public class CheckCommand implements Command {
    private final String ruleId;

    public CheckCommand(String ruleId) {
        this.ruleId = ruleId;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (ctx.config() == null || ctx.config().architecture().rules().isEmpty()) {
            System.err.println("Error: No architecture rules defined in .jsrc.yaml");
            System.exit(ExitCode.BAD_USAGE);
        }
        var allClasses = ctx.getAllClasses();
        var engine = new RuleEngine(ctx.config().architecture());
        var violations = ruleId != null
                ? engine.evaluateRule(ruleId, allClasses, ctx.javaFiles())
                : engine.evaluate(allClasses, ctx.javaFiles());
        ctx.formatter().printViolations(violations);
        return violations.size();
    }
}
