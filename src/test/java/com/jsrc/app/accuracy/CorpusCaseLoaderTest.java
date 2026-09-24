package com.jsrc.app.accuracy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusCaseLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsVersionedPositiveAndNegativeExpectations() throws Exception {
        Files.writeString(tempDir.resolve("case.properties"), """
                id=java8-overloads
                sourceVersion=8
                capability=SYMBOL_RESOLUTION
                expected.symbols=com.example.Service#process(java.lang.String);com.example.Service#process(com.example.Order)
                expected.edges=com.example.Client#run()->com.example.Service#process(com.example.Order)
                forbidden.edges=com.example.Client#run()->com.example.Service#process(java.lang.String)
                """);

        CorpusCase corpusCase = CorpusCaseLoader.load(tempDir);

        assertEquals("java8-overloads", corpusCase.id());
        assertEquals(8, corpusCase.sourceVersion());
        assertEquals(SemanticCapability.SYMBOL_RESOLUTION, corpusCase.capability());
        assertEquals(Set.of(
                "com.example.Service#process(java.lang.String)",
                "com.example.Service#process(com.example.Order)"), corpusCase.expectedSymbols());
        assertEquals(Set.of(
                "com.example.Client#run()->com.example.Service#process(com.example.Order)"),
                corpusCase.expectedEdges());
        assertEquals(Set.of(
                "com.example.Client#run()->com.example.Service#process(java.lang.String)"),
                corpusCase.forbiddenEdges());
    }

    @Test
    void rejectsManifestWithoutRequiredIdentity() throws Exception {
        Files.writeString(tempDir.resolve("case.properties"), """
                sourceVersion=8
                capability=SYMBOL_RESOLUTION
                """);

        assertThrows(IllegalArgumentException.class, () -> CorpusCaseLoader.load(tempDir));
    }
}
