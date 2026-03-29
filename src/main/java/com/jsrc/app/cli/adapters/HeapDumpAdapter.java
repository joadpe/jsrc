package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.meta.HeapDumpCommand;

@Command(name = "heap-dump", description = "Generate heap dump from a running JVM")
public class HeapDumpAdapter extends PicocliAdapter {

    @Option(names = "--pid", description = "Target JVM PID")
    Long pid;

    @Option(names = "--output", description = "Output .hprof file path")
    String output;

    @Option(names = "--live", description = "Only live objects (triggers GC first)")
    boolean live;

    @Option(names = "--list", description = "List running JVMs")
    boolean list;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new HeapDumpCommand(pid, output, live, list);
    }

    @Override
    protected String skipIndex() {
        return "heap-dump";
    }
}
