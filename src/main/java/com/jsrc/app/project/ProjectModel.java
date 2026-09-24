package com.jsrc.app.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable project structure discovered without executing a build. */
public record ProjectModel(
        Path root,
        BuildSystem buildSystem,
        String javaVersion,
        List<ProjectModule> modules,
        List<ProjectDiagnostic> diagnostics) {

    public ProjectModel {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(buildSystem, "buildSystem");
        Objects.requireNonNull(javaVersion, "javaVersion");
        modules = List.copyOf(modules);
        diagnostics = List.copyOf(diagnostics);
    }

    public Optional<ProjectModule> module(String name) {
        return modules.stream().filter(module -> module.name().equals(name)).findFirst();
    }

    public List<Path> allSourceRoots() {
        return modules.stream()
                .flatMap(module -> java.util.stream.Stream.of(
                        module.mainSourceRoots(),
                        module.testSourceRoots(),
                        module.generatedSourceRoots()))
                .flatMap(List::stream)
                .distinct()
                .toList();
    }
}
