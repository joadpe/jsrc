package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.StyleCommand;
@Command(name = "style", description = "Code style conventions")
public class StyleAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new StyleCommand(); }
}
