package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.meta.MetricsCommand;
@Command(name = "stats", description = "Metrics for a class")
public class StatsAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to get stats for")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new MetricsCommand(className); }
}
