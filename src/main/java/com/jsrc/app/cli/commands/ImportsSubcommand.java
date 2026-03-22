package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.ImportsCommand;
@Command(name = "imports", description = "Who imports this class")
public class ImportsSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to find importers for")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ImportsCommand(className); }
}
