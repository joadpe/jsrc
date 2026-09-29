package com.jsrc.app.index;

import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.project.ProjectSourceDiscovery;
import com.jsrc.app.project.SourceCompatibilityScanner;
import com.jsrc.app.project.SourceLevel;
import com.jsrc.app.project.SourceSet;
import com.jsrc.app.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** The source and build configuration observed before an index build. */
public record SourceSnapshot(
        Path root, Path configPath, String configHash, ProjectConfig config,
        Set<Path> discovered, Map<Path, SourceSet> sourceSets,
        Map<Path, SourceLevel> sourceLevels, Set<Path> accepted,
        Map<Path, String> acceptedHashes) {

    public static SourceSnapshot capture(Path root, Path configPath, ProjectConfig config,
                                         ProjectSourceDiscovery.Result sources,
                                         SourceCompatibilityScanner.Result compatibility)
            throws IOException {
        Set<Path> accepted = normalized(compatibility.files());
        Map<Path, String> acceptedHashes = compatibility.acceptedHashes().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().toAbsolutePath().normalize(),
                        Map.Entry::getValue));
        if (!accepted.equals(acceptedHashes.keySet())) {
            throw new IOException("Compatibility scan lacks hashes for accepted sources");
        }
        Path canonicalRoot = root.toAbsolutePath().normalize();
        Path actualConfig = configPath == null
                ? canonicalRoot.resolve(".jsrc.yaml") : configPath.toAbsolutePath().normalize();
        return new SourceSnapshot(canonicalRoot, actualConfig, hashConfig(actualConfig), config,
                normalized(sources.allFiles()), sources.allSourceSets().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                entry -> entry.getKey().toAbsolutePath().normalize(),
                                Map.Entry::getValue)),
                SourceLevel.resolveFiles(sources.allFiles(), sources.model(), config)
                        .entrySet().stream().collect(Collectors.toUnmodifiableMap(
                                entry -> entry.getKey().toAbsolutePath().normalize(),
                                Map.Entry::getValue)),
                accepted, acceptedHashes);
    }

    /** Fails closed when sources or their effective build model changed during construction. */
    public void verify(List<IndexEntry> entries) throws IOException {
        IndexSnapshotStore.verifySources(root, entries);
        for (IndexEntry entry : entries) {
            Path file = root.resolve(entry.path()).toAbsolutePath().normalize();
            if (!entry.contentHash().equals(acceptedHashes.get(file))) {
                throw new IOException("Source changed after compatibility scan: "
                        + entry.path() + "; retry the command");
            }
        }
        if (!configHash.equals(hashConfig(configPath))) {
            throw new IOException("Project config changed while indexing; retry the command");
        }
        ProjectConfig currentConfig = Files.exists(configPath)
                ? ProjectConfig.loadFrom(configPath).orElse(null) : null;
        if (!Objects.equals(config, currentConfig)) {
            throw new IOException("Project config changed while indexing; retry the command");
        }
        var current = new ProjectSourceDiscovery().discover(root, currentConfig);
        if (!discovered.equals(normalized(current.allFiles()))
                || !sourceSets.equals(normalizedSourceSets(current.allSourceSets()))
                || !sourceLevels.equals(normalizedLevels(current, currentConfig))) {
            throw new IOException("Source set or build model changed while indexing; retry the command");
        }
        // Accepted sources were checked when captured; their bytes are verified above.
        // Recheck rejected sources because a changed file could now be accepted.
        List<Path> rejected = discovered.stream()
                .filter(path -> !accepted.contains(path)).toList();
        IndexPhaseMetrics.countPhase("source_snapshot.compatibility_rescanned_files",
                rejected.size());
        if (!rejected.isEmpty() && !new SourceCompatibilityScanner()
                .scan(rejected, current.model(), currentConfig).files().isEmpty()) {
            throw new IOException("Source compatibility changed while indexing; retry the command");
        }
        Set<Path> entryPaths = entries.stream()
                .map(entry -> root.resolve(entry.path()).toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
        if (!accepted.equals(entryPaths)) {
            throw new IOException("Index omits or adds sources; retry the command");
        }
        for (IndexEntry entry : entries) {
            Path file = root.resolve(entry.path()).toAbsolutePath().normalize();
            SourceLevel level = sourceLevels.get(file);
            if (entry.sourceSet() != sourceSets.get(file)
                    || entry.sourceVersion() != (level == null ? 0 : level.version())) {
                throw new IOException("Source metadata changed while indexing: "
                        + entry.path() + " (source set " + entry.sourceSet() + " vs "
                        + sourceSets.get(file) + ", Java " + entry.sourceVersion()
                        + " vs " + (level == null ? 0 : level.version()) + ")");
            }
        }
    }

    private static Set<Path> normalized(List<Path> paths) {
        return paths.stream().map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<Path, SourceSet> normalizedSourceSets(Map<Path, SourceSet> sets) {
        return sets.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry -> entry.getKey().toAbsolutePath().normalize(), Map.Entry::getValue));
    }

    private static Map<Path, SourceLevel> normalizedLevels(
            ProjectSourceDiscovery.Result sources, ProjectConfig config) {
        return SourceLevel.resolveFiles(sources.allFiles(), sources.model(), config)
                .entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().toAbsolutePath().normalize(),
                        Map.Entry::getValue));
    }

    private static String hashConfig(Path path) throws IOException {
        return Files.exists(path) ? Hashing.sha256(Files.readAllBytes(path)) : "missing";
    }
}
