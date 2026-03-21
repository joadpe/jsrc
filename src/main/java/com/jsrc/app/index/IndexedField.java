package com.jsrc.app.index;

/**
 * Compact field metadata stored in the index.
 *
 * @param name field name
 * @param type field type (simple name, generics stripped)
 */
import java.util.List;

public record IndexedField(String name, String type, List<String> modifiers) {
    /** Backward-compatible constructor without modifiers. */
    public IndexedField(String name, String type) {
        this(name, type, List.of());
    }
}
