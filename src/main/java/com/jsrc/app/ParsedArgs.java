package com.jsrc.app;

import java.util.List;
import java.util.Set;

/**
 * Immutable result of CLI argument parsing.
 * Produced by {@link CliBootstrap}, consumed by {@link App}.
 */
public record ParsedArgs(
        boolean jsonOutput,
        boolean mdOutput,
        boolean signatureOnly,
        boolean showMetrics,
        Set<String> fields,
        String configPath,
        String rootPath,
        String command,
        List<String> remainingArgs
) {
    /**
     * Compact constructor: ensures remainingArgs is immutable.
     */
    public ParsedArgs {
        remainingArgs = remainingArgs != null ? List.copyOf(remainingArgs) : List.of();
    }
}
