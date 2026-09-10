package com.jsrc.app.command.meta;

import com.jsrc.app.ExitCode;
import com.jsrc.app.cli.BudgetPolicy;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.Set;

/**
 * Describe command - lists available jsrc commands using CommandRegistry as source of truth.
 * Filters by budget profile visibility using BudgetPolicy.budgetSurface().
 */
public class DescribeCommand implements Command {

    private final BudgetProfile profile;
    private final String specificCommand;
    private final boolean fullCatalog;

    public DescribeCommand(BudgetProfile profile) {
        this(profile, null, false);
    }

    public DescribeCommand(BudgetProfile profile, String specificCommand) {
        this(profile, specificCommand, false);
    }
    
    public DescribeCommand(BudgetProfile profile, String specificCommand, boolean fullCatalog) {
        this.profile = profile;
        this.specificCommand = specificCommand;
        this.fullCatalog = fullCatalog;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (specificCommand != null && !specificCommand.isEmpty()) {
            // Detail lookup for a specific command - always allowed for introspection
            return CommandRegistry.describeCommand(specificCommand, ctx.formatter() instanceof com.jsrc.app.output.JsonFormatter) 
                ? ExitCode.OK : ExitCode.NOT_FOUND;
        }
        
        // Filter CommandRegistry commands by budget visibility
        String[] allCommands = CommandRegistry.knownCommandNames();
        java.util.List<String> visibleCommands;
        
        if (fullCatalog || profile == BudgetProfile.STANDARD) {
            // Full catalog: all commands
            visibleCommands = java.util.Arrays.asList(allCommands);
        } else {
            // Filtered by budget surface
            Set<String> surface = BudgetPolicy.budgetSurface(profile);
            visibleCommands = java.util.Arrays.stream(allCommands)
                .filter(surface::contains)
                .sorted()
                .toList();
        }
        
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("budget", profile.profileName());
        result.put("commands", visibleCommands);
        result.put("totalCommands", visibleCommands.size());
        if (fullCatalog && profile != BudgetProfile.STANDARD) {
            result.put("fullCatalog", true);
        }
        
        ctx.formatter().printResult(result);
        // Return positive count of visible commands (pattern from #18)
        return visibleCommands.size();
    }
}
