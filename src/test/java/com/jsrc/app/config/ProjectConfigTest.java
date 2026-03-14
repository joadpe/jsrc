package com.jsrc.app.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should parse sourceRoots and excludes")
    void shouldParseConfig() throws IOException {
        Files.writeString(tempDir.resolve(".jsrc.yaml"), """
                sourceRoots:
                  - src/main/java
                  - src/generated/java
                excludes:
                  - "**/test/**"
                  - "**/generated/**"
                javaVersion: "21"
                """);

        ProjectConfig config = ProjectConfig.load(tempDir);
        assertNotNull(config);
        assertEquals(2, config.sourceRoots().size());
        assertEquals("src/main/java", config.sourceRoots().get(0));
        assertEquals(2, config.excludes().size());
        assertTrue(config.excludes().contains("**/test/**"));
        assertEquals("21", config.javaVersion());
    }

    @Test
    @DisplayName("Should return null when no config file exists")
    void shouldReturnNullWhenMissing() {
        assertNull(ProjectConfig.load(tempDir));
    }

    @Test
    @DisplayName("Should handle empty config file")
    void shouldHandleEmptyConfig() throws IOException {
        Files.writeString(tempDir.resolve(".jsrc.yaml"), "");
        ProjectConfig config = ProjectConfig.load(tempDir);
        assertNotNull(config);
        assertTrue(config.sourceRoots().isEmpty());
        assertTrue(config.excludes().isEmpty());
    }

    @Test
    @DisplayName("Should skip comments")
    void shouldSkipComments() throws IOException {
        Files.writeString(tempDir.resolve(".jsrc.yaml"), """
                # This is a comment
                sourceRoots:
                  - src/main/java
                # Another comment
                javaVersion: "17"
                """);

        ProjectConfig config = ProjectConfig.load(tempDir);
        assertNotNull(config);
        assertEquals(1, config.sourceRoots().size());
        assertEquals("17", config.javaVersion());
    }

    @Test
    @DisplayName("Should load from specific path via --config")
    void shouldLoadFromSpecificPath() throws IOException {
        Path custom = tempDir.resolve("custom-config.yaml");
        Files.writeString(custom, """
                sourceRoots:
                  - lib/src
                """);

        ProjectConfig config = ProjectConfig.loadFrom(custom);
        assertNotNull(config);
        assertEquals("lib/src", config.sourceRoots().get(0));
    }
}
