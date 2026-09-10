package com.jsrc.app.command.meta;

import com.jsrc.app.ExitCode;
import com.jsrc.app.cli.BudgetPolicy;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.*;

/**
 * Skill command - generates compact documentation for agents based on budget profile.
 * Uses BudgetPolicy.budgetSurface() as single source of truth.
 */
public class SkillCommand implements Command {

    private final BudgetProfile profile;

    public SkillCommand(BudgetProfile profile) {
        this.profile = profile;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (ctx.formatter() instanceof com.jsrc.app.output.JsonFormatter) {
            // JSON output: structured skill guide
            var result = buildJsonSkill(profile);
            ctx.formatter().printResult(result);
            // Return count of commands in guide (positive count pattern from #18)
            @SuppressWarnings("unchecked")
            var commands = (List<?>) result.get("commands");
            return commands != null ? commands.size() : 0;
        } else {
            // Markdown output: compact skill guide
            System.out.println(generateSkillMarkdown(profile));
            // Return surface size for markdown mode
            Set<String> surface = BudgetPolicy.budgetSurface(profile);
            if (surface == null) {
                // STANDARD profile - use all commands count
                return CommandRegistry.knownCommandNames().length;
            }
            return surface.size();
        }
    }

    private Map<String, Object> buildJsonSkill(BudgetProfile profile) {
        Set<String> surface = BudgetPolicy.budgetSurface(profile);
        if (surface == null) {
            // Standard profile - use all commands
            surface = new LinkedHashSet<>(Arrays.asList(CommandRegistry.knownCommandNames()));
        }
        
        List<Map<String, Object>> commands = new ArrayList<>();
        for (String cmdName : surface) {
            commands.add(Map.of(
                "name", cmdName,
                "summary", getCommandSummary(cmdName),
                "example", "jsrc " + cmdName + " --budget " + profile.profileName() + " --json"
            ));
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("budget", profile.profileName());
        result.put("commands", commands);
        result.put("rules", getRulesForProfile(profile));
        result.put("playbook", getPlaybookForProfile(profile));
        
        return result;
    }

    private String getCommandSummary(String cmdName) {
        return switch (cmdName) {
            case "index" -> "Build/refresh persistent index";
            case "overview" -> "Codebase stats: files, classes, methods";
            case "mini" -> "Ultra-compact class summary";
            case "read" -> "Read source code of class or method";
            case "scope" -> "Find relevant classes by keywords";
            case "callers" -> "Who calls this method";
            case "validate" -> "Verify method exists (anti-hallucination)";
            case "classes" -> "List all classes";
            case "describe" -> "List available commands";
            case "skill" -> "This agent guide";
            case "summary" -> "Class metadata + method signatures";
            case "hierarchy" -> "Class inheritance tree";
            case "deps" -> "Class dependencies";
            case "callees" -> "What this method calls";
            case "related" -> "Related classes by coupling";
            case "search" -> "Text search with context";
            case "find" -> "Semantic search by keywords";
            case "smells" -> "Detect code smells";
            case "lint" -> "Pre-compile checks";
            case "type-check" -> "Verify method return type";
            case "impact" -> "Change impact analysis";
            case "checklist" -> "Step-by-step change guide";
            default -> "Command: " + cmdName;
        };
    }

    private List<String> getRulesForProfile(BudgetProfile profile) {
        return switch (profile) {
            case TINY -> List.of(
                "ALWAYS use --json (enforced automatically)",
                "NEVER use summary (use mini instead)",
                "NEVER use context, call-chain, or dump (denied)",
                "Read methods, not classes: read Class.method not read Class",
                "All list outputs limited to 10 items"
            );
            case SMALL -> List.of(
                "ALWAYS use --json",
                "All list outputs limited to 30 items",
                "Use mini for quick summaries, summary for details",
                "Heavy commands denied (context, call-chain, dump, tour, map)"
            );
            case STANDARD -> List.of(
                "No budget restrictions",
                "Use --json for machine-readable output",
                "Run index first on large codebases"
            );
        };
    }

    private List<Map<String, Object>> getPlaybookForProfile(BudgetProfile profile) {
        if (profile == BudgetProfile.STANDARD) {
            return List.of(Map.of("task", "standard", "steps", List.of("See SKILL.md for full documentation")));
        }
        
        return List.of(
            Map.of(
                "task", "fix-bug",
                "steps", List.of(
                    "jsrc read Class.method --json (read failing method)",
                    "jsrc mini Class --json (understand the class)",
                    "jsrc validate Class.fix --json (verify fix before writing)"
                )
            ),
            Map.of(
                "task", "find-code",
                "steps", List.of(
                    "jsrc scope \"keywords\" --json (find WHERE feature lives)",
                    "jsrc mini TopMatch --json (understand the class)",
                    "jsrc read Class.method --json (see the pattern)"
                )
            ),
            Map.of(
                "task", "verify-before-write",
                "steps", List.of(
                    "jsrc validate Class.method --json (does it exist?)",
                    "jsrc type-check Class.method --json (return type correct?)"
                )
            )
        );
    }

    private String generateSkillMarkdown(BudgetProfile profile) {
        return switch (profile) {
            case TINY -> generateTinySkill();
            case SMALL -> generateSmallSkill();
            case STANDARD -> generateStandardSkill();
        };
    }

    private String generateTinySkill() {
        Set<String> commands = BudgetPolicy.budgetSurface(BudgetProfile.TINY);
        StringBuilder sb = new StringBuilder();
        sb.append("# jsrc — Java Navigator (TINY)\n\n");
        sb.append("## Commands (").append(commands.size()).append(")\n\n");
        
        for (String cmd : commands) {
            sb.append("- `jsrc ").append(cmd).append(" --json` — ")
              .append(getCommandSummary(cmd)).append("\n");
        }
        
        sb.append("\n## Rules\n");
        for (String rule : getRulesForProfile(BudgetProfile.TINY)) {
            sb.append("- ").append(rule).append("\n");
        }
        
        sb.append("\n## Quick Start\n");
        sb.append("```bash\n");
        sb.append("jsrc overview --json          # understand codebase\n");
        sb.append("jsrc scope \"keywords\" --json  # find code\n");
        sb.append("jsrc mini Class --json        # quick summary\n");
        sb.append("jsrc read Class.method --json # read method\n");
        sb.append("```\n");
        
        return sb.toString();
    }

    private String generateSmallSkill() {
        Set<String> commands = BudgetPolicy.budgetSurface(BudgetProfile.SMALL);
        StringBuilder sb = new StringBuilder();
        sb.append("# jsrc — Java Navigator (SMALL)\n\n");
        sb.append("## Commands (").append(commands.size()).append(")\n\n");
        
        for (String cmd : commands) {
            sb.append("- `jsrc ").append(cmd).append(" --json` — ")
              .append(getCommandSummary(cmd)).append("\n");
        }
        
        sb.append("\n## Rules\n");
        for (String rule : getRulesForProfile(BudgetProfile.SMALL)) {
            sb.append("- ").append(rule).append("\n");
        }
        
        sb.append("\n## Denied: context, call-chain, dump, tour, map\n");
        
        return sb.toString();
    }

    private String generateStandardSkill() {
        return """
# jsrc — Java Navigator (STANDARD)

Full documentation in SKILL.md.
No budget restrictions apply.

Use `jsrc skill --budget tiny` or `jsrc skill --budget small` for constrained guides.
""";
    }
}
