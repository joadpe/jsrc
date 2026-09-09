package com.jsrc.app.command.meta;

import com.jsrc.app.ExitCode;
import com.jsrc.app.cli.BudgetPolicy;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

/**
 * Skill command - generates compact documentation for agents based on budget profile.
 */
public class SkillCommand implements Command {

    private final BudgetProfile profile;

    public SkillCommand(BudgetProfile profile) {
        this.profile = profile;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (ctx.formatter() instanceof com.jsrc.app.output.JsonFormatter) {
            // JSON output: list of available commands for this budget
            var commands = getAllCommandsForProfile(profile);
            ctx.formatter().printResult(java.util.Map.of(
                "budget", profile.profileName(),
                "commands", commands
            ));
        } else {
            // Markdown output: compact skill guide
            System.out.println(generateSkillMarkdown(profile));
        }
        return ExitCode.OK;
    }

    private java.util.List<String> getAllCommandsForProfile(BudgetProfile profile) {
        String[] allCommands = CommandRegistry.knownCommandNames();
        return java.util.Arrays.stream(allCommands)
            .filter(cmd -> BudgetPolicy.isVisibleCommand(cmd, profile))
            .toList();
    }

    private String generateSkillMarkdown(BudgetProfile profile) {
        return switch (profile) {
            case TINY -> generateTinySkill();
            case SMALL -> generateSmallSkill();
            case STANDARD -> generateStandardSkill();
        };
    }

    private String generateTinySkill() {
        return """
# jsrc — Java Source Navigator (TINY Budget)

## Core Commands (~10 commands, optimized for 4K context)

**Navigate:**
- `jsrc index` — Build/refresh index (required first)
- `jsrc overview --json` — Codebase stats
- `jsrc mini <Class> --json` — Ultra-compact class summary
- `jsrc read <Class.method> --json` — Read specific method source
- `jsrc scope "keywords" --json` — Find relevant classes by keywords
- `jsrc callers <method> --json` — Who calls this method

**Validate:**
- `jsrc validate <Class.method> --json` — Verify method exists
- `jsrc classes --json` — List all classes (limit: 10)

**Meta:**
- `jsrc describe --json` — List available commands
- `jsrc skill --json` — This guide

## Rules for TINY budget:
1. ALWAYS use `--json` (enforced automatically)
2. NEVER use `summary` (use `mini` instead)
3. NEVER use `context`, `call-chain`, or `dump` (denied)
4. Read methods, not classes: `read Class.method` not `read Class`
5. All list outputs limited to 10 items

## Example workflow:
```bash
jsrc overview --json          # understand codebase
jsrc scope "order service" --json  # find relevant code
jsrc mini OrderService --json # quick summary
jsrc read OrderService.create --json  # read specific method
```

Size: ~1.8KB
""";
    }

    private String generateSmallSkill() {
        return """
# jsrc — Java Source Navigator (SMALL Budget)

## Core Commands (~20 commands, optimized for 8K context)

**Navigate:**
- `jsrc overview --json` — Codebase statistics
- `jsrc mini <Class> --json` — Compact class summary
- `jsrc summary <Class> --json` — Class metadata + method signatures
- `jsrc read <Class.method> --json` — Read method source
- `jsrc hierarchy <Class> --json` — Inheritance tree
- `jsrc deps <Class> --json` — Dependencies

**Call Graph:**
- `jsrc callers <method> --json` — Who calls this
- `jsrc callees <method> --json` — What this calls
- `jsrc related <Class> --json` — Related classes by coupling

**Search:**
- `jsrc scope "keywords" --json` — Find relevant classes
- `jsrc classes --json` — All classes (limit: 30)
- `jsrc search <pattern> --json` — Text search
- `jsrc find <keywords> --json` — Semantic search

**Analysis:**
- `jsrc smells <Class> --json` — Code smells
- `jsrc lint <Class> --json` — Pre-compile checks
- `jsrc validate <Method> --json` — Verify method exists
- `jsrc type-check <Method> --json` — Return type check

**Meta:**
- `jsrc describe --json` — Command list
- `jsrc skill --json` — This guide
- `jsrc index` — Build/refresh index

## Denied commands:
- `context`, `call-chain`, `dump`, `tour`, `map` (too large)

## Rules:
1. ALWAYS use `--json`
2. All list outputs limited to 30 items
3. Use `mini` for quick summaries, `summary` for details
4. Read methods when possible, not whole classes

Size: ~2.9KB
""";
    }

    private String generateStandardSkill() {
        return """
# jsrc — Java Source Navigator (STANDARD Budget)

Full documentation available in SKILL.md.
No budget restrictions apply.

Use `cat SKILL.md` or `jsrc --help` for complete documentation.
""";
    }
}
