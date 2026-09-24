package com.jsrc.app.project;

import com.jsrc.app.codebase.CodeBaseLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Discovers Java files from an offline project model. */
public final class ProjectFileDiscovery {

    private final CodeBaseLoader loader;

    public ProjectFileDiscovery() {
        this(new CodeBaseLoader());
    }

    ProjectFileDiscovery(CodeBaseLoader loader) {
        this.loader = loader;
    }

    public List<Path> discover(ProjectModel model) {
        Set<Path> files = new LinkedHashSet<>();
        List<Path> existingRoots = model.allSourceRoots().stream()
                .filter(Files::isDirectory)
                .toList();
        for (Path sourceRoot : existingRoots) {
            files.addAll(loader.loadFilesFrom(sourceRoot.toString(), "java"));
        }
        if (existingRoots.isEmpty() && model.buildSystem() == BuildSystem.UNKNOWN) {
            files.addAll(loader.loadFilesFrom(model.root().toString(), "java"));
        }
        return files.stream().map(Path::normalize).sorted().toList();
    }
}
