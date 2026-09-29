package com.jsrc.app.engine;

/** Receiver-type resolution data; presentation and exit codes belong to adapters. */
public record ResolutionResult(
        Status status,
        String expression,
        String contextClass,
        String variable,
        String method,
        String resolvedType,
        String resolvedVia,
        String signature,
        String returnType,
        String error) {
    public enum Status { FOUND, UNRESOLVED, INVALID, CONTEXT_NOT_FOUND }
}
