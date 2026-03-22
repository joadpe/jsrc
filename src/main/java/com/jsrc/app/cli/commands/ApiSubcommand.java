package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.quality.ApiCommand;

@Command(name = "api", description = "List public API: classes + methods grouped by package")
public class ApiSubcommand extends JsrcSubcommand {

    @Option(names = "--module", description = "Filter by package name")
    String module;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new ApiCommand(module);
    }
}
