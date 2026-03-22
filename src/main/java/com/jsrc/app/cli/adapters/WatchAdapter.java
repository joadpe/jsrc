package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.meta.WatchCommand;
@Command(name = "watch", description = "Daemon mode send queries via stdin")
public class WatchAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new WatchCommand(); }
}
