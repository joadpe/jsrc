package com.jsrc.app.project;

import java.nio.file.Path;
import java.util.Objects;

/** Java source file paired with its modeled origin. */
public record DiscoveredSource(Path path, SourceSet sourceSet) {
    public DiscoveredSource {
        path = Objects.requireNonNull(path, "path").normalize();
        Objects.requireNonNull(sourceSet, "sourceSet");
    }
}
