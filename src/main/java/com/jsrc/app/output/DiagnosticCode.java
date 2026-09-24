package com.jsrc.app.output;

/** Stable machine-readable diagnostic codes exposed by JSON protocol v1. */
public enum DiagnosticCode {
    INVALID_ARGUMENT,
    NOT_FOUND,
    INDEX_MISSING,
    INDEX_CORRUPT,
    BUDGET_DENIED,
    OUTPUT_TRUNCATED,
    PARSE_PARTIAL,
    UNRESOLVED_SYMBOL,
    INTERNAL_ERROR
}
