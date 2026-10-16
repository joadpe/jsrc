package com.jsrc.app.command;

import com.jsrc.app.ExitCode;
import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;

/**
 * Command that immediately returns a budget denial error.
 * Used when budget policy denies a command execution.
 * When run with a context, the error carries that context's {@code _budget}
 * metadata, such as {@code degradedFrom} for a degraded command.
 */
public class BudgetDeniedCommand implements Command {
    
    private final String commandName;
    private final BudgetProfile profile;
    private final String suggestion;

    public BudgetDeniedCommand(String commandName, BudgetProfile profile, String suggestion) {
        this.commandName = commandName;
        this.profile = profile;
        this.suggestion = suggestion;
    }

    @Override
    public int execute(CommandContext ctx) {
        var error = BudgetContext.createDenialError(commandName, profile, suggestion);
        var budgetMeta = ctx != null && ctx.budgetContext() != null
                ? ctx.budgetContext().buildMetadata()
                : null;
        if (budgetMeta != null) {
            var withMeta = new java.util.LinkedHashMap<String, Object>();
            withMeta.put("_budget", budgetMeta);
            withMeta.putAll(error);
            error = withMeta;
        }
        System.err.println(com.jsrc.app.output.JsonWriter.toJson(error));
        return ExitCode.BAD_USAGE;
    }
}
