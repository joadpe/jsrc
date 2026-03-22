package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.meta.BatchCommand;
@Command(name = "batch", description = "Execute multiple queries from stdin")
public class BatchSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new BatchCommand(); }
}
