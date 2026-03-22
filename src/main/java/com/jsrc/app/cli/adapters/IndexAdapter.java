package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.meta.IndexCommand;
@Command(name = "index", description = "Build or refresh persistent codebase index")
public class IndexAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new IndexCommand(); }
}
