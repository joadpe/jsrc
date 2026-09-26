package com.jsrc.app.output;

import com.jsrc.app.exception.BadUsageException;
import com.jsrc.app.exception.JsrcException;
import com.jsrc.app.exception.NotFoundException;
import com.jsrc.app.exception.ParseFailedException;

/** Maps internal exception types to stable public diagnostic codes. */
public final class ExceptionDiagnosticMapper {

    private ExceptionDiagnosticMapper() {}

    public static DiagnosticCode codeFor(JsrcException exception) {
        if (exception instanceof com.jsrc.app.exception.IndexSourceLevelMismatchException) {
            return DiagnosticCode.INDEX_SOURCE_LEVEL_MISMATCH;
        }
        if (exception instanceof BadUsageException) {
            return DiagnosticCode.INVALID_ARGUMENT;
        }
        if (exception instanceof NotFoundException) {
            return DiagnosticCode.NOT_FOUND;
        }
        if (exception instanceof ParseFailedException) {
            return DiagnosticCode.PARSE_PARTIAL;
        }
        return DiagnosticCode.INTERNAL_ERROR;
    }
}
