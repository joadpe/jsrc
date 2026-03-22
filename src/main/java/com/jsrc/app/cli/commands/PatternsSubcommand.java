package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.PatternsCommand;
@Command(name = "patterns", description = "Naming patterns and layer conventions")
public class PatternsSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new PatternsCommand(); }
}
