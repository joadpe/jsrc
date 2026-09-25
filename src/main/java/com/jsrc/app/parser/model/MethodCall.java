package com.jsrc.app.parser.model;

import com.jsrc.app.model.InvocationKind;
import com.jsrc.app.model.ResolutionLevel;

import java.util.List;

/**
 * Represents a concrete method invocation found in source code.
 *
 * @param caller the method that contains the call
 * @param callee the method being called
 * @param line   1-based line number of the invocation
 */
public record MethodCall(
        MethodReference caller,
        MethodReference callee,
        int line,
        InvocationKind invocationKind,
        ResolutionLevel resolutionLevel,
        List<String> evidence
) {
    public MethodCall {
        invocationKind = invocationKind == null ? InvocationKind.UNKNOWN : invocationKind;
        resolutionLevel = resolutionLevel == null
                ? ResolutionLevel.UNRESOLVED
                : resolutionLevel;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public MethodCall(MethodReference caller, MethodReference callee, int line) {
        this(caller, callee, line,
                InvocationKind.UNKNOWN, ResolutionLevel.UNRESOLVED, List.of());
    }

    @Override
    public String toString() {
        return caller.displayName() + " -> " + callee.displayName() + " [line " + line + "]";
    }
}
