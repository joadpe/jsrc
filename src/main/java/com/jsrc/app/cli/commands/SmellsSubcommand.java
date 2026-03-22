package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.SmellsCommand;
@Command(name = "smells", description = "Code smell detection (9 rules)")
public class SmellsSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to analyze", defaultValue = "")
    String className;
    @Option(names = "--all", description = "Analyze entire codebase")
    boolean all;
    @Override protected com.jsrc.app.command.Command createCommand() {
        String arg = all ? "--all" : (className.isEmpty() ? null : className);
        return new SmellsCommand(arg);
    }
}
