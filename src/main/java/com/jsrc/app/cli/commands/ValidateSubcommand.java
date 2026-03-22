package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.architecture.ValidateCommand;
@Command(name = "validate", description = "Validate method exists with exact signature")
public class ValidateSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<methodRef>", description = "Method reference to validate")
    String methodRef;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ValidateCommand(methodRef); }
}
