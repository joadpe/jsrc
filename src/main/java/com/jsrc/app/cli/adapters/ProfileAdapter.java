package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.ProfileCommand;

/**
 * Subcommand: jsrc profile &lt;file.jfr&gt;
 * Profiles a JFR recording and outputs structured analysis.
 */
@Command(name = "profile", description = "Profile a JFR recording file")
public class ProfileAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<jfr-file>",
                description = "Path to the .jfr recording file")
    String jfrFile;

    @Option(names = "--top", paramLabel = "<N>",
            description = "Top N methods to show", defaultValue = "20")
    int topN;

    @Option(names = "--correlate", description = "Correlate with jsrc index")
    boolean correlate;

    @Option(names = "--gc", description = "Include GC analysis")
    boolean gc;

    @Option(names = "--io", description = "Include I/O analysis")
    boolean io;

    @Option(names = "--allocations", description = "Include allocation analysis")
    boolean allocations;

    @Option(names = "--contention", description = "Include thread contention analysis")
    boolean contention;

    @Option(names = "--exceptions", description = "Include exception hotspots")
    boolean exceptions;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new ProfileCommand(jfrFile, topN, correlate, gc, io, allocations, contention, exceptions);
    }
}
