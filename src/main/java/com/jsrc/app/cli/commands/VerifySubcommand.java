package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.VerifyCommand;
@Command(name = "verify", description = "Compare implementation against Markdown spec")
public class VerifySubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to verify")
    String className;
    @Option(names = "--spec", required = true, description = "Path to spec Markdown file")
    String specPath;
    @Override protected com.jsrc.app.command.Command createCommand() { return new VerifyCommand(className, specPath); }
}
