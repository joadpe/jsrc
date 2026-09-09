package com.jsrc.app.cli;

import picocli.CommandLine.ParentCommand;

import com.jsrc.app.ExitCode;
import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.exception.JsrcException;
import com.jsrc.app.model.ExecutionMetrics;
import com.jsrc.app.util.StopWatch;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Base class for all jsrc picocli subcommands.
 * Provides access to the parent {@link JsrcCommand} and its global options,
 * and handles the common pattern of building context, executing, and metrics.
 *
 * <p>Coexistence plan: During migration, App.java (legacy --flag dispatch) and
 * JsrcCommand (picocli subcommand dispatch) coexist. App.main() remains the
 * entry point. Once all commands are migrated, App.main() delegates to
 * JsrcCommand and the legacy dispatch is removed.</p>
 */
@picocli.CommandLine.Command
public abstract class PicocliAdapter implements Callable<Integer> {

    @ParentCommand
    protected JsrcCommand parent;

    /**
     * Creates the Command implementation to execute.
     * Subclasses return the appropriate Command with its arguments.
     */
    protected abstract Command createCommand();

    /**
     * Returns the command name for metrics. Derived from the @Command annotation.
     */
    protected String commandName() {
        var annotation = getClass().getAnnotation(picocli.CommandLine.Command.class);
        return annotation != null ? annotation.name() : getClass().getSimpleName();
    }

    /**
     * Override to specify a command name that should skip index loading.
     * Return null (default) to load the index normally.
     */
    protected String skipIndex() {
        return null;
    }

    @Override
    public Integer call() {
        try {
            String cmdName = commandName();
            
            // Budget policy enforcement
            BudgetContext budgetCtx = parent.buildBudgetContext();
            BudgetProfile profile = budgetCtx.profile();
            
            // Check if command is denied under budget
            BudgetPolicy.Action action = BudgetPolicy.getAction(cmdName, profile);
            if (action == BudgetPolicy.Action.DENY) {
                String suggestion = getSuggestionForDeniedCommand(cmdName);
                Map<String, Object> error = BudgetContext.createDenialError(cmdName, profile, suggestion);
                System.err.println(com.jsrc.app.output.JsonWriter.toJson(error));
                return ExitCode.BAD_USAGE;
            }
            
            // Mark degradation if applicable
            if (action == BudgetPolicy.Action.DEGRADE) {
                budgetCtx.setDegradedFrom(cmdName);
            }

            var timer = StopWatch.start();
            CommandContext ctx = parent.buildContext(skipIndex());
            Command cmd = createCommand();

            if (cmd == null) {
                System.err.println("Error: missing required argument");
                return ExitCode.BAD_USAGE;
            }

            int result = cmd.execute(ctx);

            if (parent.globalOptions().showMetrics()) {
                long elapsed = timer.elapsedMs();
                int fileCount = ctx.indexed() != null ? ctx.indexed().fileCount() : ctx.javaFiles().size();
                var metrics = new ExecutionMetrics(commandName(), elapsed, fileCount, result);
                System.err.println(metrics);
            }

            return result > 0 ? ExitCode.OK : ExitCode.NOT_FOUND;
        } catch (JsrcException e) {
            System.err.println("Error: " + e.getMessage());
            return e.exitCode();
        }
    }
    
    /**
     * Returns a suggested alternative command for denied commands.
     */
    private String getSuggestionForDeniedCommand(String cmdName) {
        return switch (cmdName) {
            case "context" -> "jsrc mini <ClassName> --json";
            case "call-chain" -> "jsrc callers <method> --json";
            case "dump" -> "jsrc overview --json";
            case "tour" -> "jsrc scope <keywords> --json";
            case "map" -> "jsrc overview --json";
            default -> null;
        };
    }
}
