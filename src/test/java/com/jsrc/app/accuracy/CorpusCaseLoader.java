package com.jsrc.app.accuracy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

final class CorpusCaseLoader {

    private static final String MANIFEST = "case.properties";

    private CorpusCaseLoader() {}

    static CorpusCase load(Path caseRoot) throws IOException {
        Objects.requireNonNull(caseRoot, "caseRoot must not be null");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(caseRoot.resolve(MANIFEST))) {
            properties.load(input);
        }

        String id = required(properties, "id");
        int sourceVersion = Integer.parseInt(required(properties, "sourceVersion"));
        SemanticCapability capability = SemanticCapability.valueOf(
                required(properties, "capability"));
        return new CorpusCase(
                id,
                sourceVersion,
                capability,
                caseRoot,
                values(properties, "expected.symbols"),
                values(properties, "expected.edges"),
                values(properties, "forbidden.edges"));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return value.trim();
    }

    private static Set<String> values(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(value.split(";"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
