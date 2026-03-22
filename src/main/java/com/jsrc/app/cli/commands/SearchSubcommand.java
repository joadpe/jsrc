package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.search.SearchCommand;
@Command(name = "search", description = "Text search (supports OR: TODO|FIXME)")
public class SearchSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<pattern>", description = "Search pattern")
    String pattern;
    @Override protected com.jsrc.app.command.Command createCommand() { return new SearchCommand(pattern); }
}
