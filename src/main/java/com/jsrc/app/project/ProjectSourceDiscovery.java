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
        return discover(root, config, java.util.Set.of(), false);
    }

    /** Resolves sources and applies source-set selection before analysis or indexing. */
    public Result discover(
            Path root,
            ProjectConfig config,
            java.util.Set<SourceSet> includedSourceSets,
            boolean excludeTests) {
        ProjectModel model = modelDetector.detect(root);
        List<Path> files = new ArrayList<>(fileDiscovery.discover(model));
        var sourceSets = new java.util.LinkedHashMap<Path, SourceSet>();
        files.forEach(file -> sourceSets.put(file.normalize(), model.sourceSet(file)));
        if (config != null) {
            List<Path> configuredFiles = addConfiguredRoots(
                    model.root(), config.sourceRoots(), files);
            configuredFiles.forEach(file -> sourceSets.compute(
                    file.normalize(),
                    (path, current) -> current == null || current == SourceSet.UNKNOWN
                            ? SourceSet.MAIN
                            : current));
            files = filterExcludes(files, config.excludes());
        }
        List<DiscoveredSource> sources = files.stream()
                .distinct()
                .sorted()
                .map(file -> new DiscoveredSource(
                        file, sourceSets.getOrDefault(file.normalize(), SourceSet.UNKNOWN)))
                .filter(source -> includedSourceSets.isEmpty()
                        || includedSourceSets.contains(source.sourceSet()))
                .filter(source -> !excludeTests || !source.sourceSet().isTest())
                .toList();
        return new Result(model, sources);
    }

    private List<Path> addConfiguredRoots(
            Path root, List<String> configuredRoots, List<Path> files) {
        var configuredFiles = new ArrayList<Path>();
        for (String configuredRoot : configuredRoots) {
            Path sourceRoot = Path.of(configuredRoot);
            if (!sourceRoot.isAbsolute()) {
                sourceRoot = root.resolve(sourceRoot);
            }
            if (Files.isDirectory(sourceRoot)) {
                List<Path> discovered = loader.loadFilesFrom(sourceRoot.toString(), "java");
                files.addAll(discovered);
                configuredFiles.addAll(discovered);
            }
        }
        return configuredFiles;
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
    public record Result(ProjectModel model, List<DiscoveredSource> sources) {
        public Result {
            sources = List.copyOf(sources);
        }

        public List<Path> files() {
            return sources.stream().map(DiscoveredSource::path).toList();
        }

        public java.util.Map<Path, SourceSet> sourceSets() {
            return sources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    DiscoveredSource::path, DiscoveredSource::sourceSet));
        }
    }
}
