package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.navigate.DepsCommand;

@Command(name = "deps", description = "Dependencies: imports, fields, constructor params")
public class DepsSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to analyze")
    String className;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new DepsCommand(className);
    }
}
