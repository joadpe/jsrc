package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.reverse.ChangedCommand;
@Command(name = "changed", description = "Java files changed in git vs HEAD")
public class ChangedAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new ChangedCommand(); }
}
