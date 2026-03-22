package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.callgraph.ImpactCommand;

@Command(name = "impact", description = "Change risk: callers + transitive callers + depth")
public class ImpactSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<methodRef>",
                description = "Method reference to assess impact for")
    String methodRef;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new ImpactCommand(methodRef);
    }
}
