package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.FlowCommand;

@Command(name = "flow", description = "Trace execution flow downward (happy path)")
public class FlowSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<target>",
                description = "Class.method to trace from")
    String target;

    @Option(names = "--depth", description = "Max depth (default: 10)", defaultValue = "10")
    int depth;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new FlowCommand(target, depth);
    }
}
