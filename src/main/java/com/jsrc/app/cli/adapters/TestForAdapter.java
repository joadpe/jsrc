package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.callgraph.TestForCommand;

@Command(name = "test-for", description = "Find tests that cover a method")
public class TestForAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<methodRef>",
                description = "Method reference to find tests for")
    String methodRef;

    @Option(names = "--depth", description = "Search depth (default: 1, 'full' for transitive)",
            defaultValue = "1")
    String depth;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        int maxDepth = "full".equalsIgnoreCase(depth) ? Integer.MAX_VALUE : Integer.parseInt(depth);
        return new TestForCommand(methodRef, maxDepth);
    }
}
