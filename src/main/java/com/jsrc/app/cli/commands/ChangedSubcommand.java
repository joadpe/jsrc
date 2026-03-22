package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.ChangedCommand;
@Command(name = "changed", description = "Java files changed in git vs HEAD")
public class ChangedSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new ChangedCommand(); }
}
