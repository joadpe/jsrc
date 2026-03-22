package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.callgraph.CallChainCommand;

@Command(name = "call-chain", description = "Full call chains from roots to target")
public class CallChainSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<methodRef>",
                description = "Method reference to trace")
    String methodRef;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new CallChainCommand(methodRef, "./call-chains");
    }
}
