package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.navigate.SummaryCommand;

@Command(name = "summary", description = "Class metadata + method signatures")
public class SummarySubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to summarize")
    String className;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new SummaryCommand(className);
    }
}
