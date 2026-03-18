package com.jsrc.app.exception;

import java.nio.file.Path;

import com.jsrc.app.ExitCode;

/**
 * Thrown when a Java source file cannot be parsed.
 * Always maps to exit code {@link ExitCode#IO_ERROR} (3).
 */
public class ParseFailedException extends JsrcException {

    private final Path file;

    public ParseFailedException(String message, Path file) {
        super(ExitCode.IO_ERROR, message);
        this.file = file;
    }

    /**
     * The file that failed to parse.
     */
    public Path file() {
        return file;
    }
}
