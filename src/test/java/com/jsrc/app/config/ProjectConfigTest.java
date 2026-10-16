package com.jsrc.app.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        var config = ProjectConfig.load(tempDir);
        assertTrue(config.isPresent());
        assertEquals(2, config.get().sourceRoots().size());
        assertEquals("src/main/java", config.get().sourceRoots().get(0));
        assertEquals(2, config.get().excludes().size());
        assertTrue(config.get().excludes().contains("**/test/**"));
        assertEquals("21", config.get().javaVersion());
    }

    @Test
    @DisplayName("Should return empty when no config file exists")
    void shouldReturnEmptyWhenMissing() {
        assertTrue(ProjectConfig.load(tempDir).isEmpty());
    }

    @Test
    @DisplayName("Should handle empty config file")
    void shouldHandleEmptyConfig() throws IOException {
        Files.writeString(tempDir.resolve(".jsrc.yaml"), "");
        var config = ProjectConfig.load(tempDir);
        assertTrue(config.isPresent());
        assertTrue(config.get().sourceRoots().isEmpty());
        assertTrue(config.get().excludes().isEmpty());
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

        var config = ProjectConfig.load(tempDir);
        assertTrue(config.isPresent());
        assertEquals(1, config.get().sourceRoots().size());
        assertEquals("17", config.get().javaVersion());
    }

    @Test
    @DisplayName("Should read a config saved as Windows-1252")
    void shouldReadWindows1252Config() throws IOException {
        Path configFile = tempDir.resolve(".jsrc.yaml");
        Files.write(configFile, """
                # configuración
                sourceRoots:
                  - Catálogo/src
                javaVersion: "8"
                """.getBytes(Charset.forName("windows-1252")));

        var config = ProjectConfig.load(tempDir);
        assertTrue(config.isPresent());
        assertEquals("8", config.get().javaVersion());
        assertEquals(List.of("Catálogo/src"), config.get().sourceRoots());
        assertEquals(config, ProjectConfig.loadFrom(configFile));
    }

    @Test
    @DisplayName("Should read a UTF-16 config that starts with a byte order mark")
    void shouldReadUtf16ConfigWithByteOrderMark() throws IOException {
        // Redirection in Windows PowerShell 5 ("> .jsrc.yaml") writes UTF-16LE with a BOM.
        for (Charset charset : List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            Files.write(tempDir.resolve(".jsrc.yaml"),
                    "\uFEFF# configuración\r\njavaVersion: \"8\"\r\n".getBytes(charset));

            var config = ProjectConfig.load(tempDir);
            assertTrue(config.isPresent(), charset.name());
            assertEquals("8", config.get().javaVersion(), charset.name());
        }
    }

    @Test
    @DisplayName("Should load from specific path via --config")
    void shouldLoadFromSpecificPath() throws IOException {
        Path custom = tempDir.resolve("custom-config.yaml");
        Files.writeString(custom, """
                sourceRoots:
                  - lib/src
                """);

        var config = ProjectConfig.loadFrom(custom);
        assertTrue(config.isPresent());
        assertEquals("lib/src", config.get().sourceRoots().get(0));
    }
}
