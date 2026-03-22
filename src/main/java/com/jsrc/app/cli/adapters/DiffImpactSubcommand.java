package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.callgraph.DiffImpactCommand;
@Command(name = "diff-impact", description = "Impact analysis of changed files")
public class DiffImpactSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<filter>", description = "Optional filter", defaultValue = "")
    String filter;
    @Override protected com.jsrc.app.command.Command createCommand() {
        return new DiffImpactCommand(filter.isEmpty() ? null : filter);
    }
}
