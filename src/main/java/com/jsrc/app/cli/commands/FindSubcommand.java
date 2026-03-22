package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.FindCommand;
@Command(name = "find", description = "Semantic search by keywords")
public class FindSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<keywords>", description = "Keywords to search")
    String keywords;
    @Override protected com.jsrc.app.command.Command createCommand() { return new FindCommand(keywords); }
}
