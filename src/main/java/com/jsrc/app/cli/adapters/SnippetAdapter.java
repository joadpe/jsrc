package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.SnippetCommand;
@Command(name = "snippet", description = "Code template service controller repo")
public class SnippetAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<type>", description = "Template type")
    String type;
    @Override protected com.jsrc.app.command.Command createCommand() { return new SnippetCommand(type); }
}
