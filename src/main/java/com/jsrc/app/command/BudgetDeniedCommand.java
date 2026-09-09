package com.jsrc.app.command;

import com.jsrc.app.ExitCode;
import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;

/**
 * Command that immediately returns a budget denial error.
 * Used when budget policy denies a command execution.
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
        System.err.println(com.jsrc.app.output.JsonWriter.toJson(error));
        return ExitCode.BAD_USAGE;
    }
}
