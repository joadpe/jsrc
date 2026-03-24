package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.meta.RecordCommand;

/**
 * Subcommand: jsrc record
 * Records JFR data from a running JVM using jcmd.
 */
@Command(name = "record", description = "Record JFR data from a running JVM")
public class RecordAdapter extends PicocliAdapter {

    @Option(names = "--pid", paramLabel = "<pid>",
            description = "Target JVM PID")
    Long pid;

    @Option(names = "--duration", paramLabel = "<duration>",
            description = "Recording duration (e.g. 30s, 1m)", defaultValue = "30s")
    String duration;

    @Option(names = "--output", paramLabel = "<file>",
            description = "Output .jfr file path")
    String output;

    @Option(names = "--settings", paramLabel = "<name>",
            description = "JFR settings: default or profile", defaultValue = "profile")
    String settings;

    @Option(names = "--stop", description = "Stop an active recording")
    boolean stop;

    @Option(names = "--list", description = "List visible JVM processes")
    boolean list;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new RecordCommand(pid, duration, output, settings, stop, list);
    }

    @Override
    protected String skipIndex() {
        return "record"; // record doesn't need the codebase index
    }
}
