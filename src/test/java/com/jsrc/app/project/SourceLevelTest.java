package com.jsrc.app.project;

import com.jsrc.app.config.ArchitectureConfig;
import com.jsrc.app.config.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLevelTest {

    @TempDir
    Path projectRoot;

    @Test
    void readsLegacyOneDotSpellingOfJavaVersionOverride() {
        assertEquals(Optional.of(new SourceLevel(8, "config")), resolveOverride("1.8"));
        assertEquals(Optional.of(new SourceLevel(10, "config")), resolveOverride("1.10"));
        assertEquals(Optional.of(new SourceLevel(8, "config")), resolveOverride("8"));
    }

    @Test
    void keepsLegacySpellingOfUnsupportedLevelAsThatLevel() {
        // The compatibility scanner reports Java 7 as SOURCE_LEVEL_UNSUPPORTED.
        assertEquals(Optional.of(new SourceLevel(7, "config")), resolveOverride("1.7"));
    }

    @Test
    void doesNotReadLongerVersionFromItsPrefix() {
        assertTrue(resolveOverride("1.8.0").isEmpty());
        assertTrue(resolveOverride("1.").isEmpty());
    }

    @Test
    void readsLegacyOneDotSpellingOfModuleOverrideInsteadOfBuildLevel() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'legacy'");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects {
                    apply plugin: 'java'
                    sourceCompatibility = JavaVersion.VERSION_17
                }
                """);
        Path source = Files.createDirectories(projectRoot.resolve("legacy/src/main/java"))
                .resolve("Legacy.java");
        Files.writeString(source, "class Legacy {}");
        Files.writeString(projectRoot.resolve(".jsrc.yaml"), """
                moduleJavaVersions:
                  legacy: 1.8
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);
        ProjectConfig config = ProjectConfig.load(projectRoot).orElseThrow();

        assertEquals(Optional.of(new SourceLevel(8, "config:module:legacy")),
                SourceLevel.resolve(source, model, config));
    }

    private Optional<SourceLevel> resolveOverride(String javaVersion) {
        var config = new ProjectConfig(
                List.of(), List.of(), javaVersion, ArchitectureConfig.empty());
        return SourceLevel.resolve(projectRoot.resolve("Example.java"), null, config);
    }
}
