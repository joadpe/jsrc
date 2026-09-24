package com.jsrc.app.output;

import java.util.Locale;

/** Supported JSON output protocol versions. */
public enum JsonProtocol {
    LEGACY,
    V1;

    /** Resolves a command-line protocol selector. */
    public static JsonProtocol parse(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "legacy" -> LEGACY;
            case "1", "latest" -> V1;
            default -> throw new IllegalArgumentException(
                    "Invalid JSON protocol: " + value + ". Valid values: legacy, 1, latest");
        };
    }
}
