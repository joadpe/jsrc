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
    private final com.jsrc.app.cli.CommandCatalog catalog;

    public DescribeCommand(BudgetProfile profile) {
        this(profile, null, false, com.jsrc.app.cli.DefaultCommandRegistry.create());
    }

    public DescribeCommand(BudgetProfile profile, com.jsrc.app.cli.CommandCatalog catalog) {
        this(profile, null, false, catalog);
    }

    public DescribeCommand(BudgetProfile profile, String specificCommand) {
        this(profile, specificCommand, false, com.jsrc.app.cli.DefaultCommandRegistry.create());
    }
    
    public DescribeCommand(BudgetProfile profile, String specificCommand, boolean fullCatalog) {
        this(profile, specificCommand, fullCatalog, com.jsrc.app.cli.DefaultCommandRegistry.create());
    }

    public DescribeCommand(BudgetProfile profile, String specificCommand, boolean fullCatalog,
            com.jsrc.app.cli.CommandCatalog catalog) {
        this.profile = profile;
        this.specificCommand = specificCommand;
        this.fullCatalog = fullCatalog;
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public int execute(CommandContext ctx) {
        if (specificCommand != null && !specificCommand.isEmpty()) {
            // Detail lookup for a specific command - always allowed for introspection
            return CommandRegistry.describeCommand(specificCommand, ctx.formatter() instanceof com.jsrc.app.output.JsonFormatter) 
                ? ExitCode.OK : ExitCode.NOT_FOUND;
        }
        
        // Filter CommandRegistry commands by budget visibility
        java.util.List<String> visibleCommands;
        
        if (fullCatalog || profile == BudgetProfile.STANDARD) {
            // Full catalog: all commands
            visibleCommands = catalog.commands().stream()
                    .map(com.jsrc.app.cli.CommandDescriptor::name)
                    .toList();
        } else {
            visibleCommands = catalog.visibleCommands(profile).stream()
                    .map(com.jsrc.app.cli.CommandDescriptor::name)
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
