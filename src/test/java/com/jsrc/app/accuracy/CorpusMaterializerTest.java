package com.jsrc.app.accuracy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusMaterializerTest {

    @TempDir
    Path tempDir;

    @Test
    void materializesFixtureAsJavaSourceAndPreservesRelativePath() throws Exception {
        Path corpusRoot = tempDir.resolve("corpus");
        Path fixture = corpusRoot.resolve("java8/names/com/example/Service.java.fixture");
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, "package com.example; class Service {}");
        Path destination = tempDir.resolve("materialized");

        List<Path> materialized = CorpusMaterializer.materialize(corpusRoot, destination);

        Path javaSource = destination.resolve("java8/names/com/example/Service.java");
        assertEquals(List.of(javaSource), materialized);
        assertTrue(Files.exists(javaSource));
        assertEquals("package com.example; class Service {}", Files.readString(javaSource));
        assertFalse(Files.exists(destination.resolve(
                "java8/names/com/example/Service.java.fixture")));
    }

    @Test
    void ignoresFilesThatAreNotJavaFixtures() throws Exception {
        Path corpusRoot = tempDir.resolve("corpus");
        Files.createDirectories(corpusRoot);
        Files.writeString(corpusRoot.resolve("case.properties"), "sourceVersion=8");

        List<Path> materialized = CorpusMaterializer.materialize(
                corpusRoot, tempDir.resolve("materialized"));

        assertTrue(materialized.isEmpty());
    }
}
