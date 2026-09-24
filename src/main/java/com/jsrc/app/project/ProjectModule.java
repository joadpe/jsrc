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
        List<String> internalDependencies) {

    public ProjectModule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        mainSourceRoots = List.copyOf(mainSourceRoots);
        testSourceRoots = List.copyOf(testSourceRoots);
        generatedSourceRoots = List.copyOf(generatedSourceRoots);
        excludedRoots = List.copyOf(excludedRoots);
        internalDependencies = List.copyOf(internalDependencies);
    }
}
