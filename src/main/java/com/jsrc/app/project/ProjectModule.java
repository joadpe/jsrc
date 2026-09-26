package com.jsrc.app.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable description of one source module. */
public record ProjectModule(
        String name,
        Path path,
        List<Path> mainSourceRoots,
        List<Path> testSourceRoots,
        List<Path> generatedSourceRoots,
        List<Path> excludedRoots,
        List<String> internalDependencies,
        String javaVersion) {

    public ProjectModule(String name, Path path, List<Path> mainSourceRoots,
                         List<Path> testSourceRoots, List<Path> generatedSourceRoots,
                         List<Path> excludedRoots, List<String> internalDependencies) {
        this(name, path, mainSourceRoots, testSourceRoots, generatedSourceRoots,
                excludedRoots, internalDependencies, "unknown");
    }

    public ProjectModule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(javaVersion, "javaVersion");
        mainSourceRoots = List.copyOf(mainSourceRoots);
        testSourceRoots = List.copyOf(testSourceRoots);
        generatedSourceRoots = List.copyOf(generatedSourceRoots);
        excludedRoots = List.copyOf(excludedRoots);
        internalDependencies = List.copyOf(internalDependencies);
    }

    /** Classifies a file using this module's modeled source roots. */
    public SourceSet sourceSet(Path file) {
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (contains(generatedSourceRoots, normalizedFile)) {
            return SourceSet.GENERATED;
        }
        for (Path root : testSourceRoots) {
            if (normalizedFile.startsWith(root.toAbsolutePath().normalize())) {
                return isTestFixturesRoot(root)
                        ? SourceSet.TEST_FIXTURES
                        : SourceSet.TEST;
            }
        }
        if (contains(mainSourceRoots, normalizedFile)) {
            return SourceSet.MAIN;
        }
        return SourceSet.UNKNOWN;
    }

    private static boolean contains(List<Path> roots, Path file) {
        return roots.stream()
                .map(root -> root.toAbsolutePath().normalize())
                .anyMatch(file::startsWith);
    }

    private static boolean isTestFixturesRoot(Path root) {
        for (Path segment : root) {
            if ("testFixtures".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
