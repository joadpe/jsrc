package com.jsrc.app.exception;

/** Raised when a frozen index was built with different source-language settings. */
public final class IndexSourceLevelMismatchException extends JsrcIOException {
    public IndexSourceLevelMismatchException(String message) {
        super(message);
    }
}
