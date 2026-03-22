package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.search.FindCommand;
@Command(name = "find", description = "Semantic search by keywords")
public class FindAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<keywords>", description = "Keywords to search")
    String keywords;
    @Override protected com.jsrc.app.command.Command createCommand() { return new FindCommand(keywords); }
}
