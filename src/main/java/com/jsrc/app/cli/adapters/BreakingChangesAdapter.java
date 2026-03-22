package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.callgraph.BreakingChangesCommand;
@Command(name = "breaking-changes", description = "Impact of breaking changes to a class")
public class BreakingChangesAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to check")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new BreakingChangesCommand(className); }
}
