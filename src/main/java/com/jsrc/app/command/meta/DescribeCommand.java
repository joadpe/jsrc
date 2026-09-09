package com.jsrc.app.command.meta;

import com.jsrc.app.ExitCode;
import com.jsrc.app.cli.BudgetPolicy;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

/**
 * Describe command - lists available jsrc commands using CommandRegistry as source of truth.
 * Filters by budget profile visibility.
 */
public class DescribeCommand implements Command {

    private final BudgetProfile profile;
    private final String specificCommand;

    public DescribeCommand(BudgetProfile profile) {
        this(profile, null);
    }

    public DescribeCommand(BudgetProfile profile, String specificCommand) {
        this.profile = profile;
        this.specificCommand = specificCommand;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (specificCommand != null && !specificCommand.isEmpty()) {
            return CommandRegistry.describeCommand(specificCommand, ctx.formatter() instanceof com.jsrc.app.output.JsonFormatter) 
                ? ExitCode.OK : ExitCode.NOT_FOUND;
        }
        
        // Filter CommandRegistry commands by budget visibility
        String[] allCommands = CommandRegistry.knownCommandNames();
        java.util.List<String> visibleCommands = java.util.Arrays.stream(allCommands)
            .filter(cmd -> BudgetPolicy.isVisibleCommand(cmd, profile))
            .toList();
        
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("budget", profile.profileName());
        result.put("commands", visibleCommands);
        result.put("totalCommands", visibleCommands.size());
        
        ctx.formatter().printResult(result);
        return ExitCode.OK;
    }
}
