package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.WatchCommand;
@Command(name = "watch", description = "Daemon mode send queries via stdin")
public class WatchSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new WatchCommand(); }
}
