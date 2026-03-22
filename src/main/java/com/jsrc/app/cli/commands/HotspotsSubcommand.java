package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.HotspotsCommand;
@Command(name = "hotspots", description = "Top classes by callers + imports + test coverage")
public class HotspotsSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new HotspotsCommand(); }
}
