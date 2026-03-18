package com.jsrc.app.exception;

import com.jsrc.app.ExitCode;

/**
 * Thrown when a queried class, method, or resource is not found.
 * Maps to exit code {@link ExitCode#NOT_FOUND} (1).
 */
public class NotFoundException extends JsrcException {

    public NotFoundException(String message) {
        super(ExitCode.NOT_FOUND, message);
    }
}
