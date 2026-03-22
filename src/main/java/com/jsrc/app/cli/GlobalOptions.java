package com.jsrc.app.cli;

import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.util.Set;

/**
 * Global options shared across all jsrc subcommands.
 * Used as a mixin in JsrcCommand.
 *
 * <p>All options use {@code scope = ScopeType.INHERIT} so they can be
 * placed before OR after the subcommand name:
 * {@code jsrc --json overview} and {@code jsrc overview --json} both work.</p>
 */
public class GlobalOptions {

    @Option(names = "--json", description = "Machine-readable JSON output",
            scope = ScopeType.INHERIT)
    boolean jsonOutput;

    @Option(names = "--md", description = "Markdown output (for context command)",
            scope = ScopeType.INHERIT)
    boolean mdOutput;

    @Option(names = "--full", description = "Verbose output with full details",
            scope = ScopeType.INHERIT)
    boolean fullOutput;

    @Option(names = "--metrics", description = "Append execution metrics to stderr",
            scope = ScopeType.INHERIT)
    boolean showMetrics;

    @Option(names = "--signature-only", description = "Compact method output (signature only)",
            scope = ScopeType.INHERIT)
    boolean signatureOnly;

    @Option(names = "--no-test", description = "Exclude test classes from results",
            scope = ScopeType.INHERIT)
    boolean noTest;

    @Option(names = "--fields", description = "Limit JSON to specific fields (comma-separated)",
            split = ",", scope = ScopeType.INHERIT)
    Set<String> fields;

    @Option(names = "--config", description = "Path to custom config file",
            scope = ScopeType.INHERIT)
    String configPath;

    @Option(names = "--out", description = "Output directory",
            scope = ScopeType.INHERIT)
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
