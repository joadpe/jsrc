package com.jsrc.app.accuracy;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

record CorpusCase(
        String id,
        int sourceVersion,
        SemanticCapability capability,
        Path root,
        Set<String> expectedSymbols,
        Set<String> expectedEdges,
        Set<String> forbiddenEdges) {

    CorpusCase {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(root, "root must not be null");
        expectedSymbols = Set.copyOf(expectedSymbols);
        expectedEdges = Set.copyOf(expectedEdges);
        forbiddenEdges = Set.copyOf(forbiddenEdges);
    }
}
