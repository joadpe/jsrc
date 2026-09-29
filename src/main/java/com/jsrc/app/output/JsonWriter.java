package com.jsrc.app.output;

/** Backward-compatible JSON output facade. */
public final class JsonWriter {
    private JsonWriter() {}

    public static String toJson(Object value) {
        return com.jsrc.app.json.JsonWriter.toJson(value);
    }
}
