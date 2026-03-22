package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.meta.MetricsCommand;
@Command(name = "stats", description = "Metrics for a class")
public class StatsSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to get stats for")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new MetricsCommand(className); }
}
