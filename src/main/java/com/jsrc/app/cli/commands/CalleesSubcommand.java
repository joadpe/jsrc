package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.CalleesCommand;

@Command(name = "callees", description = "Find all methods called by a given method")
public class CalleesSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<methodRef>",
                description = "Method reference (e.g. Class.method)")
    String methodRef;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new CalleesCommand(methodRef);
    }
}
