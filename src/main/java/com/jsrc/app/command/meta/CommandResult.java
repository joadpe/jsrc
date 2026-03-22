package com.jsrc.app.command.meta;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

/**
 * Result of a command execution.
 *
 * @param resultCount number of results found
 * @param output      captured stdout output (for batch/watch)
 */
public record CommandResult(int resultCount, String output) {
    public static CommandResult of(int count) {
        return new CommandResult(count, null);
    }
}
