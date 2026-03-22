package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.OverviewCommand;

/**
 * Subcommand: jsrc overview
 * Shows codebase stats: files, classes, interfaces, methods, packages.
 */
@Command(
    name = "overview",
    description = "Codebase overview: files, classes, methods, packages"
)
public class OverviewAdapter extends PicocliAdapter {

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new OverviewCommand();
    }
}
