package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.HeapAnalyzeCommand;

@Command(name = "heap-analyze", description = "Live memory analysis of a running JVM")
public class HeapAnalyzeAdapter extends PicocliAdapter {

    @Option(names = "--pid", description = "Target JVM PID", required = true)
    Long pid;

    @Option(names = "--heap-info", description = "Show heap size, used, free")
    boolean heapInfo;

    @Option(names = "--histogram", description = "Class histogram (top N classes by instance count)")
    boolean histogram;

    @Option(names = "--compare", description = "Take 2 snapshots and detect growing classes (leak suspects)")
    boolean compare;

    @Option(names = "--top", description = "Number of top classes to show", defaultValue = "20")
    int topN;

    @Option(names = "--interval", description = "Seconds between snapshots for --compare", defaultValue = "30")
    int intervalSeconds;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new HeapAnalyzeCommand(pid, heapInfo, histogram, compare, topN, intervalSeconds);
    }

    @Override
    protected String skipIndex() {
        return "heap-analyze";
    }
}
