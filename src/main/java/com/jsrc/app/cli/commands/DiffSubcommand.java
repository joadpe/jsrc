package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.DiffCommand;
@Command(name = "diff", description = "Files changed since last index by content hash")
public class DiffSubcommand extends JsrcSubcommand {
    @Override protected String skipIndex() { return "--diff"; }
    @Override protected com.jsrc.app.command.Command createCommand() { return new DiffCommand(); }
}
