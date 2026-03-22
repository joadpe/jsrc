package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.reverse.DiffCommand;
@Command(name = "diff", description = "Files changed since last index by content hash")
public class DiffAdapter extends PicocliAdapter {
    @Override protected String skipIndex() { return "--diff"; }
    @Override protected com.jsrc.app.command.Command createCommand() { return new DiffCommand(); }
}
