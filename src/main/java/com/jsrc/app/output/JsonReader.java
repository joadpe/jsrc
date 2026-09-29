package com.jsrc.app.output;

/** Backward-compatible JSON input facade. */
public final class JsonReader {
    private JsonReader() {}

    public static Object parse(String json) {
        return com.jsrc.app.json.JsonReader.parse(json);
    }
}
