package com.jsrc.app.cli;

import picocli.CommandLine.ParentCommand;

import com.jsrc.app.ExitCode;
import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.exception.JsrcException;
import com.jsrc.app.model.ExecutionMetrics;
import com.jsrc.app.util.StopWatch;

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
            // B1: Budget enforcement gate - check DENY actions before execution
            BudgetContext budgetCtx = parent.buildBudgetContext();
            BudgetRule rule = parent.commandCatalog().budgetRule(commandName(), budgetCtx.profile());
            BudgetPolicy.Action action = rule.action();
            
            if (action == BudgetPolicy.Action.DENY) {
                // Command is denied by budget policy - return structured error
                String suggestion = rule.alternative().orElse(
                        "Try jsrc describe --json to see available commands");
                if (parent.versionedJsonEnabled()) {
                    new com.jsrc.app.output.VersionedJsonPrintStream(
                            System.err, commandName(), budgetCtx)
                            .printError(com.jsrc.app.output.DiagnosticCode.BUDGET_DENIED,
                                    commandName() + " exceeds "
                                            + budgetCtx.profile().profileName() + " budget");
                    return ExitCode.BAD_USAGE;
                }
                var deniedCmd = new com.jsrc.app.command.BudgetDeniedCommand(
                        commandName(), budgetCtx.profile(), suggestion);
                return deniedCmd.execute(null);
            }
            
            var timer = StopWatch.start();
            CommandContext ctx = parent.buildContext(skipIndex(), commandName());
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

            int exitCode = ExitCodeMapper.mapToExitCode(result);
            if (!parent.versionedJsonEnabled()
                    && exitCode == ExitCode.OK
                    && ctx.sourceDiagnostics().stream().anyMatch(diagnostic ->
                            !"SOURCE_LEVEL_UNKNOWN".equals(diagnostic.code()))) {
                return ExitCode.IO_ERROR;
            }
            return exitCode;
        } catch (JsrcException e) {
            if (parent.versionedJsonEnabled()) {
                new com.jsrc.app.output.VersionedJsonPrintStream(
                        System.err, commandName(), parent.buildBudgetContext())
                        .printError(com.jsrc.app.output.ExceptionDiagnosticMapper.codeFor(e),
                                e.getMessage());
                return e.exitCode();
            }
            System.err.println("Error: " + e.getMessage());
            return e.exitCode();
        }
    }
    
}
