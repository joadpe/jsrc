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
}
