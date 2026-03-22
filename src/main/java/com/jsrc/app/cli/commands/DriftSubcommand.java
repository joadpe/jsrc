package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.reverse.DriftCommand;
@Command(name = "drift", description = "Architecture check + changed file detection")
public class DriftSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new DriftCommand(); }
}
