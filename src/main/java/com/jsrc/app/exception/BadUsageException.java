package com.jsrc.app.exception;

import com.jsrc.app.ExitCode;

/**
 * Thrown when the user provides invalid arguments or unknown commands.
 * Always maps to exit code {@link ExitCode#BAD_USAGE} (2).
 */
public class BadUsageException extends JsrcException {

    public BadUsageException(String message) {
        super(ExitCode.BAD_USAGE, message);
    }
}
