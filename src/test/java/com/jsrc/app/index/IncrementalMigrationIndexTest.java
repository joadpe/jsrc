package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncrementalMigrationIndexTest {

    @Test
    void scansOnlyEditedPathAndMatchesCleanRebuild(@TempDir Path root) throws Exception {
        Path first = root.resolve("First.java");
        Path second = root.resolve("Second.java");
        Files.writeString(first, "import java.util.Vector; class First { Vector<String> values; }\n");
        Files.writeString(second, "import java.util.Vector; class Second { Vector<String> values; }\n");
        runIndex(root, root.resolve("initial-trace.json"));
        var original = migrations(root);
        assertEquals(Set.of("First.java", "Second.java"), original.keySet());

        String edited = "class First { int revision() { return 1; } }\n";
        Files.writeString(first, edited);
        Path trace = root.resolve("edited-trace.json");
        runIndex(root, trace);

        Path clean = Files.createDirectory(root.resolve("clean"));
        Files.writeString(clean.resolve("First.java"), edited);
        Files.writeString(clean.resolve("Second.java"), Files.readString(second));
        runIndex(clean, clean.resolve("clean-trace.json"));
        assertEquals(migrations(clean), migrations(root));
        assertEquals(Set.of("Second.java"), migrations(root).keySet());
        var frozen = IndexedCodebase.tryLoad(root, List.of(first, second), true);
        assertEquals(migrations(root), frozen.getAllMigrations());
        String metrics = Files.readString(trace);
        assertTrue(metrics.contains("\"index.migrations.reused_paths\":1"), metrics);
        assertTrue(metrics.contains("\"index.migrations.scanned_paths\":1"), metrics);
    }

    @Test
    void addedAndDeletedFilesMatchCleanRebuild(@TempDir Path root) throws Exception {
        Path removed = root.resolve("Removed.java");
        Files.writeString(removed, "import java.util.Vector; class Removed { Vector<String> values; }\n");
        runIndex(root, root.resolve("initial-trace.json"));
        Files.delete(removed);
        Path added = root.resolve("Added.java");
        String source = "import java.util.Vector; class Added { Vector<String> values; }\n";
        Files.writeString(added, source);
        runIndex(root, root.resolve("changed-trace.json"));

        Path clean = Files.createDirectory(root.resolve("clean"));
        Files.writeString(clean.resolve("Added.java"), source);
        runIndex(clean, clean.resolve("clean-trace.json"));
        assertEquals(migrations(clean), migrations(root));
        assertEquals(Set.of("Added.java"), migrations(root).keySet());
        assertFalse(migrations(root).containsKey("Removed.java"));
    }

    @Test
    void multipleClassesShareOneMigrationPath(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Shared.java"), """
                import java.util.Vector;
                class First { Vector<String> values; }
                class Second { Vector<String> values; }
                """);
        runIndex(root, root.resolve("trace.json"));
        assertEquals(Set.of("Shared.java"), migrations(root).keySet());
        assertEquals(2, migrations(root).get("Shared.java").size());
    }

    private static Map<String, List<CachedMigration>> migrations(Path root) throws Exception {
        return BinaryIndexV2Reader.read(CodebaseIndex.currentBinary(root)).migrations();
    }

    private static void runIndex(Path root, Path trace) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-Djsrc.perf.trace=" + trace,
                "-cp", classpath, "com.jsrc.app.App", "--dir", root.toString(), "index")
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Index subprocess timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }
}
