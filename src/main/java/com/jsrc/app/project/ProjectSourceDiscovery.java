package com.jsrc.app.project;

import com.jsrc.app.codebase.CodeBaseLoader;
import com.jsrc.app.config.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Resolves the project model and canonical Java source files for command execution. */
public final class ProjectSourceDiscovery {

    private final ProjectModelDetector modelDetector;
    private final ProjectFileDiscovery fileDiscovery;
    private final CodeBaseLoader loader;

    public ProjectSourceDiscovery() {
        this(new ProjectModelDetector(), new ProjectFileDiscovery(), new CodeBaseLoader());
    }

    ProjectSourceDiscovery(
            ProjectModelDetector modelDetector,
            ProjectFileDiscovery fileDiscovery,
            CodeBaseLoader loader) {
        this.modelDetector = modelDetector;
        this.fileDiscovery = fileDiscovery;
        this.loader = loader;
    }

    /** Resolves the build model and applies configured source roots and exclusions. */
    public Result discover(Path root, ProjectConfig config) {
        ProjectModel model = modelDetector.detect(root);
        List<Path> files = new ArrayList<>(fileDiscovery.discover(model));
        if (config != null) {
            addConfiguredRoots(model.root(), config.sourceRoots(), files);
            files = filterExcludes(files, config.excludes());
        }
        return new Result(model, files.stream().distinct().sorted().toList());
    }

    private void addConfiguredRoots(Path root, List<String> configuredRoots, List<Path> files) {
        for (String configuredRoot : configuredRoots) {
            Path sourceRoot = Path.of(configuredRoot);
            if (!sourceRoot.isAbsolute()) {
                sourceRoot = root.resolve(sourceRoot);
            }
            if (Files.isDirectory(sourceRoot)) {
                files.addAll(loader.loadFilesFrom(sourceRoot.toString(), "java"));
            }
        }
    }

    private static List<Path> filterExcludes(List<Path> files, List<String> excludes) {
        if (excludes.isEmpty()) {
            return files;
        }
        return files.stream()
                .filter(file -> excludes.stream().noneMatch(exclude -> {
                    String pattern = exclude.replace("**", ".*").replace("*", "[^/]*");
                    return file.toString().matches(".*" + pattern + ".*");
                }))
                .toList();
    }

    /** Canonical model and source files produced by one discovery pass. */
    public record Result(ProjectModel model, List<Path> files) {}
}
