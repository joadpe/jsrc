package com.jsrc.app.command;

/**
 * A CLI command that can be executed with a shared context.
 */
public interface Command {

    /**
     * Executes the command.
     *
     * @param ctx shared context (files, config, formatter, index, parser)
     * @return number of results found (0 = not found)
     */
    int execute(CommandContext ctx);

    /**
     * Returns contextual hints for next commands after execution.
     * Override in commands that want to guide agent navigation.
     *
     * @param ctx shared context
     * @return list of suggested next commands (empty by default)
     */
    default java.util.List<com.jsrc.app.model.CommandHint> hints(CommandContext ctx) {
        return java.util.List.of();
    }
}
