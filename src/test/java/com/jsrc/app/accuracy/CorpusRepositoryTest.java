package com.jsrc.app.accuracy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversCasesRecursivelyInDeterministicOrder() throws Exception {
        writeManifest("java17/records", "java17-records", 17);
        writeManifest("java8/overloads", "java8-overloads", 8);
        Files.writeString(tempDir.resolve("README.txt"), "ignored");

        List<CorpusCase> cases = CorpusRepository.loadAll(tempDir);

        assertEquals(List.of("java17-records", "java8-overloads"),
                cases.stream().map(CorpusCase::id).toList());
    }

    private void writeManifest(String relativePath, String id, int sourceVersion)
            throws Exception {
        Path caseRoot = tempDir.resolve(relativePath);
        Files.createDirectories(caseRoot);
        Files.writeString(caseRoot.resolve("case.properties"), """
                id=%s
                sourceVersion=%d
                capability=SYMBOL_DISCOVERY
                """.formatted(id, sourceVersion));
    }
}
