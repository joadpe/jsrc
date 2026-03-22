package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.CallersCommand;

@Command(name = "callers", description = "Find all methods that call a given method")
public class CallersSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<methodRef>",
                description = "Method reference (e.g. Class.method or methodName)")
    String methodRef;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new CallersCommand(methodRef);
    }
}
