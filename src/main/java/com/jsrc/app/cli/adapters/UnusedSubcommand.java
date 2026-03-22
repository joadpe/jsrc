package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.search.UnusedCommand;
@Command(name = "unused", description = "Dead code: classes/methods never called")
public class UnusedSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new UnusedCommand(); }
}
