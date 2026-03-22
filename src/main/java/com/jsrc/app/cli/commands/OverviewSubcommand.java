package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.OverviewCommand;

/**
 * Subcommand: jsrc overview
 * Shows codebase stats: files, classes, interfaces, methods, packages.
 */
@Command(
    name = "overview",
    description = "Codebase overview: files, classes, methods, packages"
)
public class OverviewSubcommand extends JsrcSubcommand {

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new OverviewCommand();
    }
}
