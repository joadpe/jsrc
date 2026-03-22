package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.PackagesCommand;
@Command(name = "packages", description = "Package stats import counts circular deps")
public class PackagesSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new PackagesCommand(); }
}
