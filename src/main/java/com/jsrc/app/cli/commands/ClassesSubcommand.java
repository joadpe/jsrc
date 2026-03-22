package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.navigate.ClassesCommand;

/**
 * Subcommand: jsrc classes [filter]
 * Lists all classes/interfaces/enums/records, ranked by callers.
 */
@Command(
    name = "classes",
    description = "List all classes/interfaces/enums/records (ranked by callers)"
)
public class ClassesSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<filter>",
                description = "Optional package filter", defaultValue = "")
    String filter;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        String arg = filter.isEmpty() ? null : filter;
        return new ClassesCommand(arg);
    }
}
