package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.meta.BatchCommand;
@Command(name = "batch", description = "Execute multiple queries from stdin")
public class BatchAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new BatchCommand(); }
}
