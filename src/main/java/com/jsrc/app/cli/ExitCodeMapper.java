package com.jsrc.app.cli;

import com.jsrc.app.ExitCode;

/**
 * Shared exit code mapping helper for consistent exit code translation
 * across WatchCommand and PicocliAdapter.
 * 
 * This ensures both daemon mode and CLI one-shot mode apply the same
 * exit code mapping rules (single enforcement point).
 */
public final class ExitCodeMapper {

    private ExitCodeMapper() {}

    /**
     * Maps a command execution result to a process exit code.
     * 
     * Mapping rules:
     * - result > 0 → ExitCode.OK (0) - success with results
     * - result == 0 → ExitCode.NOT_FOUND (1) - success but no results
     * - result < 0 → pass through (e.g. BAD_USAGE, IO_ERROR)
     * 
     * @param result the command execution result
     * @return the mapped exit code
     */
    public static int mapToExitCode(int result) {
        // Negative results are error codes that pass through unchanged
        if (result < 0) {
            return result;
        }
        
        // Standard success/not-found logic
        return result > 0 ? ExitCode.OK : ExitCode.NOT_FOUND;
    }
}
