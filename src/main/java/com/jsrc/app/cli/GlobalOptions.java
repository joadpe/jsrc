package com.jsrc.app.cli;

import picocli.CommandLine.Option;

import java.util.Set;

/**
 * Global options shared across all jsrc subcommands.
 * Used as a mixin in JsrcCommand.
 */
public class GlobalOptions {

    @Option(names = "--json", description = "Machine-readable JSON output")
    boolean jsonOutput;

    @Option(names = "--md", description = "Markdown output (for context command)")
    boolean mdOutput;

    @Option(names = "--full", description = "Verbose output with full details")
    boolean fullOutput;

    @Option(names = "--metrics", description = "Append execution metrics to stderr")
    boolean showMetrics;

    @Option(names = "--signature-only", description = "Compact method output (signature only)")
    boolean signatureOnly;

    @Option(names = "--no-test", description = "Exclude test classes from results")
    boolean noTest;

    @Option(names = "--fields", description = "Limit JSON to specific fields (comma-separated)",
            split = ",")
    Set<String> fields;

    @Option(names = "--config", description = "Path to custom config file")
    String configPath;

    @Option(names = "--out", description = "Output directory")
    String outDir;

    public boolean jsonOutput() { return jsonOutput; }
    public boolean mdOutput() { return mdOutput; }
    public boolean fullOutput() { return fullOutput; }
    public boolean showMetrics() { return showMetrics; }
    public boolean signatureOnly() { return signatureOnly; }
    public boolean noTest() { return noTest; }
    public Set<String> fields() { return fields; }
    public String configPath() { return configPath; }
    public String outDir() { return outDir; }
}
