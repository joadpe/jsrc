package com.jsrc.app.project;

import java.nio.file.Path;
import java.util.Objects;

/** A source compatibility failure that must not be presented as an exact result. */
public record SourceDiagnostic(String code, Path file, String message) {
    public SourceDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(message, "message");
    }
}
