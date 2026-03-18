package com.jsrc.app.exception;

/**
 * Base exception for all jsrc errors.
 * Carries an exit code that {@code App.main()} maps to {@code System.exit()}.
 */
public class JsrcException extends RuntimeException {

    private final int exitCode;

    public JsrcException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public JsrcException(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    /**
     * Exit code to return to the OS.
     *
     * @see com.jsrc.app.ExitCode
     */
    public int exitCode() {
        return exitCode;
    }
}
