package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.architecture.ChecklistCommand;
@Command(name = "checklist", description = "Review checklist for a class")
public class ChecklistSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to check")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ChecklistCommand(className, null); }
}
