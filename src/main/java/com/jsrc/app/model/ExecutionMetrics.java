package com.jsrc.app.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Performance metrics for a command execution.
 *
 * @param commandName   the command that was executed
 * @param elapsedMs     total execution time in milliseconds
 * @param filesScanned  number of source files processed
 * @param resultsFound  number of results/matches found
 */
public record ExecutionMetrics(
        String commandName,
        long elapsedMs,
        int filesScanned,
        int resultsFound
) {
    /**
     * Converts to a map for JSON serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("command", commandName);
        map.put("elapsedMs", elapsedMs);
        map.put("filesScanned", filesScanned);
        map.put("resultsFound", resultsFound);
        return map;
    }

    /**
     * Human-readable summary.
     */
    @Override
    public String toString() {
        return String.format("[%s] %dms | %d files | %d results",
                commandName, elapsedMs, filesScanned, resultsFound);
    }
}
