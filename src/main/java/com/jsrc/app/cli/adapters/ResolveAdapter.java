package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.ResolveCommand;
@Command(name = "resolve", description = "Resolve a simple name to fully qualified")
public class ResolveAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<name>", description = "Name to resolve")
    String name;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ResolveCommand(name); }
}
