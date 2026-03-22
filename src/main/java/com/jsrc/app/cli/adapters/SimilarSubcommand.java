package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.navigate.SimilarCommand;
@Command(name = "similar", description = "Find similar classes")
public class SimilarSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to find similar to")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new SimilarCommand(className); }
}
