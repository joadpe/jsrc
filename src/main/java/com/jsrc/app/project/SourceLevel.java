package com.jsrc.app.project;

import com.jsrc.app.config.ProjectConfig;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Effective Java source language level for one file, not the jsrc runtime JDK. */
public record SourceLevel(int version, String provenance) {

    /** The legacy spelling 1.N of Java N, which Maven and Gradle detection also read. */
    private static final Pattern LEGACY_SPELLING = Pattern.compile("1\\.(\\d+)");

    public static Optional<SourceLevel> resolve(
            Path file, ProjectModel model, ProjectConfig config) {
        if (config != null && model != null) {
            for (ProjectModule module : model.modules()) {
                if (module.sourceSet(file) != SourceSet.UNKNOWN) {
                    String relative = model.root().relativize(module.path()).toString();
                    String override = config.moduleJavaVersions().get(relative);
                    if (override != null) {
                        return parse(override, "config:module:" + relative);
                    }
                    break;
                }
            }
        }
        if (config != null && !config.javaVersion().isBlank()) {
            return parse(config.javaVersion(), "config");
        }
        if (model != null) {
            for (ProjectModule module : model.modules()) {
                if (module.sourceSet(file) != SourceSet.UNKNOWN) {
                    return parse(module.javaVersion(), "module:" + module.name());
                }
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    public static java.util.Map<Path, SourceLevel> resolveFiles(
            java.util.List<Path> files, ProjectModel model, ProjectConfig config) {
        var levels = new java.util.LinkedHashMap<Path, SourceLevel>();
        for (Path file : files) {
            resolve(file, model, config).ifPresent(level -> levels.put(file, level));
        }
        return java.util.Map.copyOf(levels);
    }

    private static Optional<SourceLevel> parse(String value, String provenance) {
        if (value == null || value.isBlank() || "unknown".equals(value)) {
            return Optional.empty();
        }
        Matcher legacy = LEGACY_SPELLING.matcher(value);
        try {
            return Optional.of(new SourceLevel(
                    Integer.parseInt(legacy.matches() ? legacy.group(1) : value), provenance));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
