package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.callgraph.CallersCommand;

@Command(name = "callers", description = "Find all methods that call a given method")
public class CallersAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<methodRef>",
                description = "Method reference (e.g. Class.method or methodName)")
    String methodRef;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new CallersCommand(methodRef);
    }
}
