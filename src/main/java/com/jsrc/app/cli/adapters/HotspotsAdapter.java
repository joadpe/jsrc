package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.HotspotsCommand;
@Command(name = "hotspots", description = "Top classes by callers + imports + test coverage")
public class HotspotsAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new HotspotsCommand(); }
}
