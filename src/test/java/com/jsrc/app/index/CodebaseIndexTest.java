package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.parser.HybridJavaParser;

class CodebaseIndexTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should build index and save to disk")
    void shouldBuildAndSave() throws IOException {
        Path javaFile = writeFile("Hello.java", """
                package com.test;
                public class Hello {
                    public void greet() {}
                    public String name() { return ""; }
                }
                """);

        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        int reindexed = index.build(parser, List.of(javaFile), tempDir, List.of());

        assertEquals(1, reindexed);
        assertEquals(1, index.getEntries().size());

        IndexEntry entry = index.getEntries().getFirst();
        assertFalse(entry.contentHash().isEmpty());
        assertEquals(1, entry.classes().size());
        assertEquals("Hello", entry.classes().getFirst().name());
        assertEquals(2, entry.classes().getFirst().methods().size());

        index.save(tempDir);
        assertTrue(Files.exists(tempDir.resolve(".jsrc/index.json")));
    }

    @Test
    @DisplayName("Should load saved index from disk")
    void shouldLoadFromDisk() throws IOException {
        Path javaFile = writeFile("Service.java", """
                package com.app;
                public class Service {
                    public void process(String input) {}
                }
                """);

        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, List.of(javaFile), tempDir, List.of());
        index.save(tempDir);

        // Load from disk
        List<IndexEntry> loaded = CodebaseIndex.load(tempDir);
        assertEquals(1, loaded.size());

        IndexEntry entry = loaded.getFirst();
        assertEquals("Service", entry.classes().getFirst().name());
        assertEquals("com.app", entry.classes().getFirst().packageName());
        assertEquals(1, entry.classes().getFirst().methods().size());
        assertEquals("process", entry.classes().getFirst().methods().getFirst().name());
    }

    @Test
    @DisplayName("Incremental: should skip unchanged files")
    void shouldSkipUnchanged() throws IOException {
        Path javaFile = writeFile("Stable.java", """
                public class Stable {
                    public void doNothing() {}
                }
                """);

        var parser = new HybridJavaParser();
        var index1 = new CodebaseIndex();
        index1.build(parser, List.of(javaFile), tempDir, List.of());
        index1.save(tempDir);

        // Build again with existing index
        List<IndexEntry> existing = CodebaseIndex.load(tempDir);
        var index2 = new CodebaseIndex();
        int reindexed = index2.build(parser, List.of(javaFile), tempDir, existing);

        assertEquals(0, reindexed, "Unchanged file should not be re-indexed");
        assertEquals(1, index2.getEntries().size());
    }

    @Test
    @DisplayName("Incremental: should re-index modified files")
    void shouldReindexModified() throws IOException {
        Path javaFile = writeFile("Mutable.java", """
                public class Mutable {
                    public void v1() {}
                }
                """);

        var parser = new HybridJavaParser();
        var index1 = new CodebaseIndex();
        index1.build(parser, List.of(javaFile), tempDir, List.of());
        index1.save(tempDir);

        // Modify the file
        Files.writeString(javaFile, """
                public class Mutable {
                    public void v2() {}
                    public void v2extra() {}
                }
                """);

        List<IndexEntry> existing = CodebaseIndex.load(tempDir);
        var index2 = new CodebaseIndex();
        int reindexed = index2.build(parser, List.of(javaFile), tempDir, existing);

        assertEquals(1, reindexed, "Modified file should be re-indexed");
        assertEquals(2, index2.getEntries().getFirst().classes().getFirst().methods().size());
    }

    @Test
    @DisplayName("Should return empty list when no index exists")
    void shouldReturnEmptyWhenNoIndex() {
        List<IndexEntry> loaded = CodebaseIndex.load(tempDir);
        assertTrue(loaded.isEmpty());
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
