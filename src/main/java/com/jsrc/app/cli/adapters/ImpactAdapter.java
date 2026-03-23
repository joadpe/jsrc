package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.callgraph.ImpactCommand;

@Command(name = "impact", description = "Change risk: callers + transitive callers + depth")
public class ImpactAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<methodRef>",
                description = "Method reference to assess impact for")
    String methodRef;

    @Option(names = "--what-if", description = "Simulate which tests would fail if method changes")
    boolean whatIf;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new ImpactCommand(methodRef, whatIf);
    }
}
