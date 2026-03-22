package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.reverse.ContextCommand;
@Command(name = "context", description = "Full context: summary + deps + hierarchy + call graph + smells + source")
public class ContextSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to get context for")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() {
        return new ContextCommand(className, parent.globalOptions().mdOutput());
    }
}
