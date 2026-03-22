package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.BreakingChangesCommand;
@Command(name = "breaking-changes", description = "Impact of breaking changes to a class")
public class BreakingChangesSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to check")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new BreakingChangesCommand(className); }
}
