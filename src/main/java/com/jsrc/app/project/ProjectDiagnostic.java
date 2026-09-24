package com.jsrc.app.project;

import java.util.Objects;

/** Structured diagnostic produced while interpreting build metadata. */
public record ProjectDiagnostic(String code, String message) {
    public ProjectDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
