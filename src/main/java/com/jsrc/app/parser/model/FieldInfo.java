package com.jsrc.app.parser.model;

/**
 * Represents a field declaration in a class.
 *
 * @param name field name
 * @param type field type (simple name, generics stripped)
 */
import java.util.List;

public record FieldInfo(String name, String type, List<String> modifiers) {
    /** Backward-compatible constructor without modifiers. */
    public FieldInfo(String name, String type) {
        this(name, type, List.of());
    }

    public boolean isStatic() { return modifiers.contains("static"); }
    public boolean isFinal() { return modifiers.contains("final"); }
    public boolean isVolatile() { return modifiers.contains("volatile"); }
    public boolean isMutableStatic() { return isStatic() && !isFinal(); }
}
