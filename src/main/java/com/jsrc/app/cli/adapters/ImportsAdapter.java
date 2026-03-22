package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.ImportsCommand;
@Command(name = "imports", description = "Who imports this class")
public class ImportsAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to find importers for")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ImportsCommand(className); }
}
