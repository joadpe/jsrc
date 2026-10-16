package com.jsrc.app.index;

import com.jsrc.app.parser.model.CodeSmell;

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
) {

    /** Converts a detected smell to its cached form with a one-letter severity code. */
    public static CachedSmell from(CodeSmell smell) {
        return new CachedSmell(
                smell.ruleId(), severityCode(smell.severity()), smell.line(),
                smell.methodName(), smell.className(), smell.message());
    }

    /**
     * Decodes the stored severity. Older indexes also contain full enum names
     * ("WARNING") written by index auto-refresh, so both forms are accepted.
     */
    public CodeSmell.Severity severityLevel() {
        return switch (severity) {
            case "E", "ERROR" -> CodeSmell.Severity.ERROR;
            case "W", "WARNING" -> CodeSmell.Severity.WARNING;
            default -> CodeSmell.Severity.INFO;
        };
    }

    private static String severityCode(CodeSmell.Severity severity) {
        return switch (severity) {
            case INFO -> "I";
            case WARNING -> "W";
            case ERROR -> "E";
        };
    }
}
