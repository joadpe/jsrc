package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.reverse.HistoryCommand;
@Command(name = "history", description = "Change history for a class")
public class HistoryAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to get history for")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new HistoryCommand(className); }
}
