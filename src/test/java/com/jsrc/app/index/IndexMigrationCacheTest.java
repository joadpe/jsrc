package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexMigrationCacheTest {

    @Test
    void unchangedIndexReusesPublishedMigrationSuggestions(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Demo.java"),
                "import java.util.Vector;\nclass Demo { Vector<String> values = new Vector<>(); }\n");

        runIndex(root, root.resolve("first-trace.json"));
        var original = BinaryIndexV2Reader.read(CodebaseIndex.currentBinary(root)).migrations();
        assertFalse(original.isEmpty(), "Fixture must produce a migration suggestion");

        Path secondTrace = root.resolve("second-trace.json");
        String output = runIndex(root, secondTrace);

        assertTrue(output.contains("0 re-indexed"), output);
        assertEquals(original, BinaryIndexV2Reader.read(CodebaseIndex.currentBinary(root)).migrations());
        assertTrue(Files.readString(secondTrace).contains("\"index.migrations.reused\":1"),
                Files.readString(secondTrace));
    }

    @Test
    void editedSourceRecomputesMigrationSuggestions(@TempDir Path root) throws Exception {
        Path source = root.resolve("Demo.java");
        Files.writeString(source,
                "import java.util.Vector;\nclass Demo { Vector<String> values = new Vector<>(); }\n");
        runIndex(root, root.resolve("first-trace.json"));

        Files.writeString(source, "class Demo {}\n");
        Path editedTrace = root.resolve("edited-trace.json");
        String output = runIndex(root, editedTrace);

        assertTrue(output.contains("1 re-indexed"), output);
        assertTrue(BinaryIndexV2Reader.read(CodebaseIndex.currentBinary(root)).migrations().isEmpty());
        assertFalse(Files.readString(editedTrace).contains("\"index.migrations.reused\":1"));
    }

    @Test
    void snapshotWithoutMigrationCacheRecomputesSuggestions(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Demo.java"),
                "import java.util.Vector;\nclass Demo { Vector<String> values = new Vector<>(); }\n");
        runIndex(root, root.resolve("first-trace.json"));
        var indexed = BinaryIndexV2Reader.read(CodebaseIndex.currentBinary(root));
        assertFalse(indexed.migrations().isEmpty(), "Fixture must produce a migration suggestion");

        new CodebaseIndex(indexed.entries()).saveWithGraph(root, indexed.callGraph(), null, true);
        Path refreshTrace = root.resolve("refresh-trace.json");
        String output = runIndex(root, refreshTrace);

        assertTrue(output.contains("0 re-indexed"), output);
        assertEquals(indexed.migrations(),
                BinaryIndexV2Reader.read(CodebaseIndex.currentBinary(root)).migrations());
        assertFalse(Files.readString(refreshTrace).contains("\"index.migrations.reused\":1"));
    }

    @Test
    void outdatedMigrationCacheVersionRecomputesSuggestions(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Demo.java"),
                "import java.util.Vector;\nclass Demo { Vector<String> values = new Vector<>(); }\n");
        runIndex(root, root.resolve("first-trace.json"));
        Path binary = CodebaseIndex.currentBinary(root);
        var original = BinaryIndexV2Reader.read(binary).migrations();
        assertFalse(original.isEmpty(), "Fixture must produce a migration suggestion");

        byte[] bytes = Files.readAllBytes(binary);
        ByteBuffer.wrap(bytes, bytes.length - Integer.BYTES, Integer.BYTES).putInt(1);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, bytes.length - 12);
        ByteBuffer.wrap(bytes, 8, Integer.BYTES).putInt((int) crc.getValue());
        Files.write(binary, bytes);

        Path refreshTrace = root.resolve("refresh-trace.json");
        String output = runIndex(root, refreshTrace);

        assertTrue(output.contains("0 re-indexed"), output);
        assertEquals(original,
                BinaryIndexV2Reader.read(CodebaseIndex.currentBinary(root)).migrations());
        assertFalse(Files.readString(refreshTrace).contains("\"index.migrations.reused\":1"));
    }

    @Test
    void automaticRefreshDropsMigrationsFromChangedSources(@TempDir Path root) throws Exception {
        Path source = root.resolve("Demo.java");
        Files.writeString(source,
                "import java.util.Vector;\nclass Demo { Vector<String> values = new Vector<>(); }\n");
        runIndex(root, root.resolve("first-trace.json"));

        Files.writeString(source, "class Demo {}\n");
        var refreshed = IndexedCodebase.tryLoad(root, java.util.List.of(source));

        assertFalse(refreshed.hasCachedMigrations());
        assertTrue(BinaryIndexV2Reader.read(CodebaseIndex.currentBinary(root)).migrations().isEmpty());
    }

    @Test
    void legacyBinaryWithoutManifestIsRebuilt(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Demo.java"), "class Demo {}\n");
        runIndex(root, root.resolve("first-trace.json"));

        Path legacy = root.resolve(".jsrc/index.bin");
        Files.copy(CodebaseIndex.currentBinary(root), legacy);
        Files.delete(root.resolve(".jsrc/current"));

        String output = runIndex(root, root.resolve("legacy-trace.json"));
        assertTrue(output.contains("1 re-indexed"), output);
    }

    private static String runIndex(Path root, Path trace) throws Exception {
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
        return output;
    }
}
