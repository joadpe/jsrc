package com.jsrc.app.accuracy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

final class CorpusRepository {

    private static final String MANIFEST = "case.properties";

    private CorpusRepository() {}

    static List<CorpusCase> loadAll(Path corpusRoot) throws IOException {
        Objects.requireNonNull(corpusRoot, "corpusRoot must not be null");
        List<Path> caseRoots;
        try (Stream<Path> paths = Files.walk(corpusRoot)) {
            caseRoots = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(MANIFEST))
                    .map(Path::getParent)
                    .sorted()
                    .toList();
        }

        List<CorpusCase> cases = new ArrayList<>(caseRoots.size());
        for (Path caseRoot : caseRoots) {
            cases.add(CorpusCaseLoader.load(caseRoot));
        }
        cases.sort(Comparator.comparing(CorpusCase::id));
        return List.copyOf(cases);
    }
}
