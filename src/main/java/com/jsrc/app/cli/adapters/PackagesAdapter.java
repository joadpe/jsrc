package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.PackagesCommand;
@Command(name = "packages", description = "Package stats import counts circular deps")
public class PackagesAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new PackagesCommand(); }
}
