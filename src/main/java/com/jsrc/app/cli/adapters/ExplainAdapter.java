package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.ExplainCommand;
@Command(name = "explain", description = "Detailed explanation of a class")
public class ExplainAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to explain")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ExplainCommand(className); }
}
