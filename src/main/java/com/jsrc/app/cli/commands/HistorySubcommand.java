package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.HistoryCommand;
@Command(name = "history", description = "Change history for a class")
public class HistorySubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to get history for")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new HistoryCommand(className); }
}
