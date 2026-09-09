package com.jsrc.app.command.meta;

import com.jsrc.app.ExitCode;
import com.jsrc.app.cli.BudgetPolicy;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describe command - lists available jsrc commands, budget-aware.
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
        if (specificCommand != null) {
            describeSpecificCommand(ctx);
        } else {
            describeAllCommands(ctx);
        }
        return ExitCode.OK;
    }

    private void describeAllCommands(CommandContext ctx) {
        var commands = getCommandsForProfile(profile);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("budget", profile.profileName());
        result.put("commands", commands);
        result.put("totalCommands", commands.size());
        
        ctx.formatter().printResult(result);
    }

    private void describeSpecificCommand(CommandContext ctx) {
        var commandInfo = getCommandInfo(specificCommand);
        if (commandInfo == null) {
            System.err.println("Unknown command: " + specificCommand);
            return;
        }
        
        boolean visible = BudgetPolicy.isVisibleCommand(specificCommand, profile);
        commandInfo.put("visibleUnderBudget", visible);
        commandInfo.put("budget", profile.profileName());
        
        ctx.formatter().printResult(commandInfo);
    }

    private List<Map<String, Object>> getCommandsForProfile(BudgetProfile profile) {
        var allCommands = getAllCommandDefinitions();
        
        return allCommands.stream()
            .filter(cmd -> BudgetPolicy.isVisibleCommand((String) cmd.get("name"), profile))
            .toList();
    }

    private List<Map<String, Object>> getAllCommandDefinitions() {
        return List.of(
            cmd("index", "Build/refresh persistent index", "meta"),
            cmd("overview", "Codebase statistics", "navigation"),
            cmd("mini", "Ultra-compact class summary", "navigation"),
            cmd("summary", "Class metadata + method signatures", "navigation"),
            cmd("read", "Read source code", "navigation"),
            cmd("hierarchy", "Inheritance tree", "navigation"),
            cmd("deps", "Class dependencies", "navigation"),
            cmd("classes", "List all classes", "navigation"),
            cmd("scope", "Find relevant classes by keywords", "search"),
            cmd("search", "Text search", "search"),
            cmd("find", "Semantic search", "search"),
            cmd("callers", "Who calls this method", "call-graph"),
            cmd("callees", "What this method calls", "call-graph"),
            cmd("related", "Related classes by coupling", "navigation"),
            cmd("smells", "Code smells detection", "analysis"),
            cmd("lint", "Pre-compile checks", "analysis"),
            cmd("validate", "Verify method exists", "validation"),
            cmd("type-check", "Return type verification", "validation"),
            cmd("describe", "List available commands", "meta"),
            cmd("skill", "Compact skill guide", "meta")
        );
    }

    private Map<String, Object> cmd(String name, String description, String category) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("description", description);
        map.put("category", category);
        return map;
    }

    private Map<String, Object> getCommandInfo(String cmdName) {
        return getAllCommandDefinitions().stream()
            .filter(cmd -> cmdName.equals(cmd.get("name")))
            .findFirst()
            .orElse(null);
    }
}
