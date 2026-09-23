package com.jsrc.app.accuracy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

record AccuracyBaseline(int schemaVersion, Map<String, String> values) {

    AccuracyBaseline {
        values = Map.copyOf(values);
    }

    static AccuracyBaseline load(Path baselineFile) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(baselineFile)) {
            properties.load(input);
        }
        int schemaVersion = Integer.parseInt(required(properties, "schemaVersion"));
        Map<String, String> values = properties.stringPropertyNames().stream()
                .filter(key -> !key.equals("schemaVersion"))
                .collect(Collectors.toUnmodifiableMap(key -> key, properties::getProperty));
        return new AccuracyBaseline(schemaVersion, values);
    }

    AccuracyThresholds thresholdsFor(SemanticCapability capability) {
        String prefix = capability.name() + ".";
        return new AccuracyThresholds(
                threshold(prefix + "symbolPrecision"),
                threshold(prefix + "symbolRecall"),
                threshold(prefix + "edgePrecision"),
                threshold(prefix + "edgeRecall"),
                count(prefix + "maxSymbolFalsePositives"),
                count(prefix + "maxSymbolFalseNegatives"),
                count(prefix + "maxEdgeFalsePositives"),
                count(prefix + "maxEdgeFalseNegatives"));
    }

    private double threshold(String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required threshold: " + key);
        }
        return Double.parseDouble(value);
    }

    private int count(String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required threshold: " + key);
        }
        return Integer.parseInt(value);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return value.trim();
    }
}
