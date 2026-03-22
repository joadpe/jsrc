package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.architecture.TypeCheckCommand;
@Command(name = "type-check", description = "Type check a class")
public class TypeCheckSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to type-check")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new TypeCheckCommand(className); }
}
