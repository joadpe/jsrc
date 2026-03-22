package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.IndexCommand;
@Command(name = "index", description = "Build or refresh persistent codebase index")
public class IndexSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new IndexCommand(); }
}
