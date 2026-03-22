package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.search.SearchCommand;
@Command(name = "search", description = "Text search (supports OR: TODO|FIXME)")
public class SearchAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<pattern>", description = "Search pattern")
    String pattern;
    @Override protected com.jsrc.app.command.Command createCommand() { return new SearchCommand(pattern); }
}
