package com.jsrc.app.project;

import java.util.Arrays;

/** Logical origin of a Java source file within a project model. */
public enum SourceSet {
    MAIN("main"),
    TEST("test"),
    TEST_FIXTURES("testFixtures"),
    GENERATED("generated"),
    UNKNOWN("unknown");

    private final String externalName;

    SourceSet(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    public boolean isTest() {
        return this == TEST || this == TEST_FIXTURES;
    }

    public static SourceSet fromExternalName(String value) {
        return Arrays.stream(values())
                .filter(sourceSet -> sourceSet.externalName.equalsIgnoreCase(value)
                        || sourceSet.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown source set: " + value
                                + ". Expected main, test, testFixtures, generated, or unknown"));
    }
}
