package com.jsrc.app.index;

/**
 * Compact code smell stored in the index.
 * Lightweight version of {@link com.jsrc.app.parser.model.CodeSmell}.
 *
 * @param ruleId    rule identifier (e.g. METHOD_TOO_LONG)
 * @param severity  W=warning, I=info, E=error
 * @param line      line number
 * @param method    enclosing method name (empty if class-level)
 * @param className enclosing class name
 * @param message   short description
 */
public record CachedSmell(
        String ruleId,
        String severity,
        int line,
        String method,
        String className,
        String message
) {}
