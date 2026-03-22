package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.quality.ApiCommand;

@Command(name = "api", description = "List public API: classes + methods grouped by package")
public class ApiAdapter extends PicocliAdapter {

    @Option(names = "--module", description = "Filter by package name")
    String module;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new ApiCommand(module);
    }
}
