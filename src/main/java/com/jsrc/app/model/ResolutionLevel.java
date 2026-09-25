package com.jsrc.app.model;

/** Certainty of a statically resolved call graph edge. */
public enum ResolutionLevel {
    EXACT,
    INFERRED,
    UNRESOLVED
}
