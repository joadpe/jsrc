package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.MapCommand;
@Command(name = "map", description = "Visual codebase map")
public class MapAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new MapCommand(); }
}
