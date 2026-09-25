package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.project.SourceSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexedCodebaseSourceSetTest {

    @Test
    void incrementalRefreshPreservesSourceSetForNewFiles(@TempDir Path root) throws Exception {
        Path main = writeJava(root.resolve("src/main/java/Main.java"), "class Main {}");
        var initial = new CodebaseIndex();
        initial.build(
                new HybridJavaParser(),
                List.of(main),
                root,
                List.of(),
                List.of(),
                Map.of(main, SourceSet.MAIN));
        initial.save(root);
        Path fixture = writeJava(
                root.resolve("src/testFixtures/java/Fixture.java"),
                "class Fixture {}");

        IndexedCodebase refreshed = IndexedCodebase.tryLoad(
                root,
                List.of(main, fixture),
                false,
                Map.of(main, SourceSet.MAIN, fixture, SourceSet.TEST_FIXTURES));

        IndexEntry fixtureEntry = refreshed.getEntries().stream()
                .filter(entry -> entry.path().endsWith("Fixture.java"))
                .findFirst()
                .orElseThrow();
        assertEquals(SourceSet.TEST_FIXTURES, fixtureEntry.sourceSet());
        assertEquals(
                SourceSet.TEST_FIXTURES,
                refreshed.findSourceSetForClass("Fixture").orElseThrow());
    }

    private static Path writeJava(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
