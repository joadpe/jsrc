package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.EntryPointsCommand;
@Command(name = "entry-points", description = "Main methods and entry points")
public class EntryPointsSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<filter>", description = "Optional filter", defaultValue = "")
    String filter;
    @Override protected com.jsrc.app.command.Command createCommand() {
        return new EntryPointsCommand(filter.isEmpty() ? null : filter);
    }
}
