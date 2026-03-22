package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.reverse.ContextCommand;
@Command(name = "context", description = "Full context: summary + deps + hierarchy + call graph + smells + source")
public class ContextAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to get context for")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() {
        return new ContextCommand(className, parent.globalOptions().mdOutput());
    }
}
