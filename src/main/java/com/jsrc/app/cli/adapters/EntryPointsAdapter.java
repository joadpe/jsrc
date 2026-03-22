package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.architecture.EntryPointsCommand;
@Command(name = "entry-points", description = "Main methods and entry points")
public class EntryPointsAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<filter>", description = "Optional filter", defaultValue = "")
    String filter;
    @Override protected com.jsrc.app.command.Command createCommand() {
        return new EntryPointsCommand(filter.isEmpty() ? null : filter);
    }
}
