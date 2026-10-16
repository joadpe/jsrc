package com.jsrc.app.project;

import com.jsrc.app.codebase.CodeBaseLoader;
import com.jsrc.app.config.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the project model and canonical Java source files for command execution. */
public final class ProjectSourceDiscovery {

    private static final Pattern STARS = Pattern.compile("\\*+");

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
        }
        // Configured roots such as "./module/src" or "." reach modeled files through
        // non-normalized paths. Normalize and deduplicate before applying excludes, so
        // each file is matched once and by the path it is indexed under.
        List<Path> distinctFiles = files.stream()
                .map(Path::normalize)
                .distinct()
                .sorted()
                .toList();
        if (config != null) {
            distinctFiles = filterExcludes(distinctFiles, config.excludes());
        }
        List<DiscoveredSource> allSources = distinctFiles.stream()
                .map(file -> new DiscoveredSource(
                        file, sourceSets.getOrDefault(file, SourceSet.UNKNOWN)))
                .toList();
        List<DiscoveredSource> sources = allSources.stream()
                .filter(source -> includedSourceSets.isEmpty()
                        || includedSourceSets.contains(source.sourceSet()))
                .filter(source -> !excludeTests || !source.sourceSet().isTest())
                .toList();
        return new Result(model, sources, allSources);
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

    /** Drops the files whose path contains a match for any exclude pattern. */
    static List<Path> filterExcludes(List<Path> files, List<String> excludes) {
        if (excludes.isEmpty()) {
            return files;
        }
        List<Pattern> patterns = excludes.stream()
                .map(ProjectSourceDiscovery::excludePattern)
                .toList();
        return files.stream()
                .filter(file -> {
                    // Patterns use '/', so match Windows paths with the same separator.
                    String path = file.toString().replace('\\', '/');
                    return patterns.stream().noneMatch(pattern -> pattern.matcher(path).find());
                })
                .toList();
    }

    /**
     * Compiles an exclude glob into a regex to search for anywhere in a '/'-separated
     * path. A run of two or more stars spans directories, a single star stays within one
     * file or directory name, a backslash separates directories like '/', leading "./"
     * and "../" segments match like '/', and every other character is literal.
     */
    private static Pattern excludePattern(String exclude) {
        // A pattern written as a relative path, such as "./build/", cannot match an
        // absolute path as written. Match its leading "./" and "../" segments as one '/',
        // so it still starts at a directory; 2.6.1 matched "./build/" the same way by
        // reading "." as any character.
        String glob = exclude.replace('\\', '/').replaceFirst("^(\\.\\.?/)+", "/");
        // Leading and trailing stars cannot change whether a search finds the pattern, so
        // drop them. Matching "**/test/**" as ".*.*/test/.*.*" backtracked quadratically
        // on every path that does not contain "/test/".
        glob = glob.replaceAll("^\\*+|\\*+$", "");
        var regex = new StringBuilder();
        Matcher stars = STARS.matcher(glob);
        int literalStart = 0;
        while (stars.find()) {
            regex.append(Pattern.quote(glob.substring(literalStart, stars.start())))
                    .append(stars.end() - stars.start() > 1 ? ".*" : "[^/]*");
            literalStart = stars.end();
        }
        regex.append(Pattern.quote(glob.substring(literalStart)));
        // DOTALL lets "**" span every character, including line terminators in names.
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /** Canonical model and source files produced by one discovery pass. */
    public record Result(ProjectModel model, List<DiscoveredSource> sources,
                         List<DiscoveredSource> allSources) {
        public Result {
            sources = List.copyOf(sources);
            allSources = List.copyOf(allSources);
        }

        public Result(ProjectModel model, List<DiscoveredSource> sources) {
            this(model, sources, sources);
        }

        public List<Path> files() {
            return sources.stream().map(DiscoveredSource::path).toList();
        }

        public List<Path> allFiles() {
            return allSources.stream().map(DiscoveredSource::path).toList();
        }

        public java.util.Map<Path, SourceSet> sourceSets() {
            return sources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    DiscoveredSource::path, DiscoveredSource::sourceSet));
        }

        public java.util.Map<Path, SourceSet> allSourceSets() {
            return allSources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    DiscoveredSource::path, DiscoveredSource::sourceSet));
        }
    }
}
