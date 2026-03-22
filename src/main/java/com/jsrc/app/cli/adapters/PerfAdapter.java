package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.PerfCommand;

@Command(name = "perf", description = "Detect performance bottlenecks (loops with linear scan, I/O, allocations)")
public class PerfAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<target>",
                description = "Class or Class.method to analyze")
    String target;

    @Option(names = "--depth", description = "Analysis depth (default: 1, 'full' for all branches)",
            defaultValue = "1")
    String depth;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        int maxDepth = "full".equalsIgnoreCase(depth) ? Integer.MAX_VALUE : Integer.parseInt(depth);
        return new PerfCommand(target, maxDepth);
    }
}
