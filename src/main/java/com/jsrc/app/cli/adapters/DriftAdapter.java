package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.reverse.DriftCommand;
@Command(name = "drift", description = "Architecture check + changed file detection")
public class DriftAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new DriftCommand(); }
}
