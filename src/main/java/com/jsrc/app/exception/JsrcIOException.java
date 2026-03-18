package com.jsrc.app.exception;

import com.jsrc.app.ExitCode;

/**
 * Thrown on I/O errors (file not found, disk full, permission denied).
 * Always maps to exit code {@link ExitCode#IO_ERROR} (3).
 */
public class JsrcIOException extends JsrcException {

    public JsrcIOException(String message) {
        super(ExitCode.IO_ERROR, message);
    }

    public JsrcIOException(String message, Throwable cause) {
        super(ExitCode.IO_ERROR, message, cause);
    }
}
