package com.jsrc.app.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class MinimalJsonSchemaValidator {

    private MinimalJsonSchemaValidator() {}

    static List<String> validate(Map<String, Object> schema, Object value) {
        List<String> errors = new ArrayList<>();
        validate(schema, value, "$", errors);
        return List.copyOf(errors);
    }

    @SuppressWarnings("unchecked")
    private static void validate(
            Map<String, Object> schema, Object value, String path, List<String> errors) {
        Object type = schema.get("type");
        if (type instanceof String expected && !hasType(expected, value)) {
            errors.add(path + " must be " + expected);
            return;
        }
        if (schema.containsKey("const") && !equalValues(schema.get("const"), value)) {
            errors.add(path + " must equal " + schema.get("const"));
        }
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value)) {
            errors.add(path + " must be one of " + allowed);
        }
        if (value instanceof Map<?, ?> object) {
            if (schema.get("required") instanceof List<?> required) {
                for (Object key : required) {
                    if (!object.containsKey(key)) {
                        errors.add(path + " missing " + key);
                    }
                }
            }
            if (schema.get("properties") instanceof Map<?, ?> properties) {
                properties.forEach((key, propertySchema) -> {
                    if (object.containsKey(key) && propertySchema instanceof Map<?, ?> nested) {
                        validate((Map<String, Object>) nested, object.get(key),
                                path + "." + key, errors);
                    }
                });
            }
        }
        if (value instanceof List<?> values && schema.get("items") instanceof Map<?, ?> itemSchema) {
            for (int i = 0; i < values.size(); i++) {
                validate((Map<String, Object>) itemSchema, values.get(i),
                        path + "[" + i + "]", errors);
            }
        }
    }

    private static boolean hasType(String expected, Object value) {
        return switch (expected) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long;
            case "number" -> value instanceof Number;
            case "null" -> value == null;
            default -> true;
        };
    }

    private static boolean equalValues(Object expected, Object actual) {
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            return Double.compare(expectedNumber.doubleValue(), actualNumber.doubleValue()) == 0;
        }
        return expected.equals(actual);
    }
}
