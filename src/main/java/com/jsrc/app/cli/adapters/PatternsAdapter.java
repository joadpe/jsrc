package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.PatternsCommand;
@Command(name = "patterns", description = "Naming patterns and layer conventions")
public class PatternsAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new PatternsCommand(); }
}
