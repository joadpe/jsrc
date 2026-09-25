package com.jsrc.app.model;

/** JVM-level invocation category represented by a call graph edge. */
public enum InvocationKind {
    STATIC,
    SPECIAL,
    VIRTUAL,
    INTERFACE,
    CONSTRUCTOR,
    METHOD_REFERENCE,
    REFLECTIVE,
    UNKNOWN
}
