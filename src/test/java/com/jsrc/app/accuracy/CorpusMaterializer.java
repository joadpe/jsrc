package com.jsrc.app.accuracy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

final class CorpusMaterializer {

    private static final String FIXTURE_SUFFIX = ".java.fixture";
    private static final String FIXTURE_EXTENSION = ".fixture";

    private CorpusMaterializer() {}

    static List<Path> materialize(Path corpusRoot, Path destination) throws IOException {
        Objects.requireNonNull(corpusRoot, "corpusRoot must not be null");
        Objects.requireNonNull(destination, "destination must not be null");

        List<Path> fixtures;
        try (Stream<Path> paths = Files.walk(corpusRoot)) {
            fixtures = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(FIXTURE_SUFFIX))
                    .sorted()
                    .toList();
        }

        List<Path> materialized = new ArrayList<>(fixtures.size());
        for (Path fixture : fixtures) {
            Path relative = corpusRoot.relativize(fixture);
            String fixtureFileName = relative.getFileName().toString();
            String javaFileName = fixtureFileName.substring(
                    0, fixtureFileName.length() - FIXTURE_EXTENSION.length());
            Path target = destination.resolve(relative).resolveSibling(javaFileName);
            Files.createDirectories(target.getParent());
            Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING);
            materialized.add(target);
        }
        return List.copyOf(materialized);
    }
}
