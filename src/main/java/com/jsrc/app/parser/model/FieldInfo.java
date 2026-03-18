package com.jsrc.app.parser.model;

/**
 * Represents a field declaration in a class.
 *
 * @param name field name
 * @param type field type (simple name, generics stripped)
 */
public record FieldInfo(String name, String type) {}
