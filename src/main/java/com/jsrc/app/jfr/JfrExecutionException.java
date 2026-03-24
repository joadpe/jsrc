package com.jsrc.app.jfr;

/**
 * Thrown when a JFR tool subprocess fails.
 */
public class JfrExecutionException extends RuntimeException {

    public JfrExecutionException(String action, Throwable cause) {
        super("JFR error while " + action + ": " + cause.getMessage(), cause);
    }
}
