package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.DepsCommand;

@Command(name = "deps", description = "Dependencies: imports, fields, constructor params")
public class DepsAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to analyze")
    String className;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new DepsCommand(className);
    }
}
