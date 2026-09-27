package com.jsrc.app.index;

import com.jsrc.app.project.SourceSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexSnapshotStoreTest {

    @Test
    void filteredQueryDoesNotReplaceCanonicalGeneration(@TempDir Path projectRoot)
            throws Exception {
        Path main = projectRoot.resolve("src/main/java/Main.java");
        Path test = projectRoot.resolve("src/test/java/MainTest.java");
        Files.createDirectories(main.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(main, "class Main {}");
        Files.writeString(test, "class MainTest {}");
        assertEquals(0, com.jsrc.app.cli.JsrcCliFactory.create().execute(
                "--dir", projectRoot.toString(), "index"));

        assertEquals(0, com.jsrc.app.cli.JsrcCliFactory.create().execute(
                "--dir", projectRoot.toString(), "--no-test", "overview"));

        assertEquals(java.util.Set.of("src/main/java/Main.java", "src/test/java/MainTest.java"),
                CodebaseIndex.loadPublished(projectRoot).stream()
                        .map(IndexEntry::path).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void splitLegacyJsonIsRebuiltEvenWhenHashesMatch(@TempDir Path projectRoot)
            throws Exception {
        Path source = projectRoot.resolve("A.java");
        Files.writeString(source, "class A { void current() {} }");
        var entry = new IndexEntry("A.java",
                com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(source)),
                0L, SourceSet.UNKNOWN, List.of(), List.of(), List.of(), 0);
        new CodebaseIndex(List.of(entry)).save(projectRoot);
        assertTrue(Files.exists(projectRoot.resolve(".jsrc/classes.json")));

        var rebuilt = IndexedCodebase.tryLoad(projectRoot, List.of(source), false);

        assertNotNull(rebuilt);
        assertTrue(Files.isRegularFile(projectRoot.resolve(".jsrc/current")));
        assertTrue(!rebuilt.getAllClasses().isEmpty());
    }

    @Test
    void addedSourceBeforePublicationRejectsIncompleteGeneration(@TempDir Path projectRoot)
            throws Exception {
        Path source = projectRoot.resolve("A.java");
        Files.writeString(source, "class A {}");
        var discovery = new com.jsrc.app.project.ProjectSourceDiscovery()
                .discover(projectRoot, null);
        var snapshot = SourceSnapshot.capture(projectRoot, null, null,
                discovery, discovery.allFiles());
        var entry = new IndexEntry("A.java",
                com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(source)),
                0L, SourceSet.UNKNOWN, List.of(), List.of(), List.of(), 0);
        Files.writeString(projectRoot.resolve("B.java"), "class B {}");

        assertThrows(IOException.class, () -> new CodebaseIndex(List.of(entry))
                .saveWithGraph(projectRoot, null, null, true, snapshot));
        assertTrue(Files.notExists(projectRoot.resolve(".jsrc/current")));
    }

    @Test
    void buildLevelChangeBeforePublicationRejectsGeneration(@TempDir Path projectRoot)
            throws Exception {
        Path source = projectRoot.resolve("src/main/java/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A {}");
        Path pom = projectRoot.resolve("pom.xml");
        String template = "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>test</groupId><artifactId>sample</artifactId><version>1</version>"
                + "<properties><maven.compiler.release>%s</maven.compiler.release>"
                + "</properties></project>";
        Files.writeString(pom, template.formatted("8"));
        var discovery = new com.jsrc.app.project.ProjectSourceDiscovery()
                .discover(projectRoot, null);
        var snapshot = SourceSnapshot.capture(projectRoot, null, null,
                discovery, discovery.allFiles());
        var entry = new IndexEntry("src/main/java/A.java",
                com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(source)),
                0L, SourceSet.MAIN, List.of(), List.of(), List.of(), 8);
        Files.writeString(pom, template.formatted("17"));

        assertThrows(IOException.class, () -> new CodebaseIndex(List.of(entry))
                .saveWithGraph(projectRoot, null, null, true, snapshot));
        assertTrue(Files.notExists(projectRoot.resolve(".jsrc/current")));
    }

    @Test
    void publishedIndexIsAuthorityEvenWhenLegacyJsonExists(@TempDir Path projectRoot)
            throws Exception {
        var entry = new IndexEntry("A.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);
        var index = new CodebaseIndex(List.of(entry));
        index.save(projectRoot);

        assertTrue(CodebaseIndex.loadPublished(projectRoot).isEmpty());
        index.saveWithGraph(projectRoot, null);
        assertEquals(List.of("A.java"), CodebaseIndex.loadPublished(projectRoot).stream()
                .map(IndexEntry::path).toList());
    }

    @Test
    void publishingIndexCreatesGenerationAndManifest(@TempDir Path projectRoot) throws Exception {
        Path source = projectRoot.resolve("A.java");
        Files.writeString(source, "class A {}");
        var entry = new IndexEntry("A.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);

        new CodebaseIndex(List.of(entry)).saveWithGraph(projectRoot, null);

        Path manifest = projectRoot.resolve(".jsrc/current");
        assertTrue(Files.isRegularFile(manifest), "a published snapshot needs an atomic pointer");
        try (var generations = Files.list(projectRoot.resolve(".jsrc/generations"))) {
            assertEquals(1, generations.filter(path -> path.toString().endsWith(".bin")).count());
        }
    }

    @Test
    void frozenReaderLoadsPublishedGeneration(@TempDir Path projectRoot) throws Exception {
        Path source = projectRoot.resolve("A.java");
        Files.writeString(source, "class A {}");
        var entry = new IndexEntry("A.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);
        new CodebaseIndex(List.of(entry)).saveWithGraph(projectRoot, null);

        assertEquals(1, IndexedCodebase.tryLoad(projectRoot, List.of(source), true).fileCount());
    }

    @Test
    void frozenReaderRejectsLegacyIndexWithoutManifest(@TempDir Path projectRoot) throws Exception {
        Path source = projectRoot.resolve("A.java");
        Files.writeString(source, "class A {}");
        Path legacy = projectRoot.resolve(".jsrc/index.bin");
        Files.createDirectories(legacy.getParent());
        var entry = new IndexEntry("A.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);
        BinaryIndexV2Writer.write(legacy, List.of(entry), null);

        assertThrows(com.jsrc.app.exception.JsrcIOException.class,
                () -> IndexedCodebase.tryLoad(projectRoot, List.of(source), true));
        assertTrue(Files.notExists(projectRoot.resolve(".jsrc/current")));
    }

    @Test
    void normalReaderRebuildsLegacyIndex(@TempDir Path projectRoot) throws Exception {
        Path source = projectRoot.resolve("A.java");
        Files.writeString(source, "class A { void current() {} }");
        Path legacy = projectRoot.resolve(".jsrc/index.bin");
        Files.createDirectories(legacy.getParent());
        var stale = new IndexEntry("A.java", "stale", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);
        BinaryIndexV2Writer.write(legacy, List.of(stale), null);

        var rebuilt = IndexedCodebase.tryLoad(projectRoot, List.of(source), false);

        assertEquals(com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(source)),
                rebuilt.getEntries().getFirst().contentHash());
        assertTrue(Files.isRegularFile(projectRoot.resolve(".jsrc/current")));
    }

    @Test
    void smellCacheUpdatePreservesUnselectedFiles(@TempDir Path projectRoot) throws Exception {
        Path sourceA = projectRoot.resolve("A.java");
        Path sourceB = projectRoot.resolve("B.java");
        Files.writeString(sourceA, "class A {}");
        Files.writeString(sourceB, "class B {}");
        var entryA = new IndexEntry("A.java",
                com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(sourceA)),
                0L, SourceSet.UNKNOWN, List.of(), List.of(), List.of(), 0);
        var entryB = new IndexEntry("B.java",
                com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(sourceB)),
                0L, SourceSet.UNKNOWN, List.of(), List.of(), List.of(), 0);
        new CodebaseIndex(List.of(entryA, entryB)).saveWithGraph(projectRoot, null);
        var selected = IndexedCodebase.tryLoad(projectRoot, List.of(sourceA), true);

        selected.setCachedSmells("A.java", List.of(new CachedSmell(
                "TEST", "INFO", 1, "", "A", "example")));
        selected.save(projectRoot);

        var persisted = CodebaseIndex.loadPublished(projectRoot);
        assertEquals(2, persisted.size());
        assertEquals(1, persisted.stream().filter(entry -> entry.path().equals("A.java"))
                .findFirst().orElseThrow().smells().size());
        assertTrue(Files.notExists(projectRoot.resolve(".jsrc/index.json")));
    }

    @Test
    void staleSmellCacheCannotOverrideNewerGeneration(@TempDir Path projectRoot)
            throws Exception {
        Path source = projectRoot.resolve("A.java");
        Files.writeString(source, "class A {}");
        var original = new IndexEntry("A.java",
                com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(source)),
                0L, SourceSet.UNKNOWN, List.of(), List.of(), List.of(), 0);
        new CodebaseIndex(List.of(original)).saveWithGraph(projectRoot, null);
        var staleReader = IndexedCodebase.tryLoad(projectRoot, List.of(source), true);
        staleReader.setCachedSmells("A.java", List.of(new CachedSmell(
                "TEST", "INFO", 1, "", "A", "obsolete")));

        Files.writeString(source, "class A { void changed() {} }");
        var newer = new IndexEntry("A.java",
                com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(source)),
                0L, SourceSet.UNKNOWN, List.of(), List.of(), List.of(), 0);
        new CodebaseIndex(List.of(newer)).saveWithGraph(projectRoot, null, null, true);
        staleReader.save(projectRoot);

        var persisted = CodebaseIndex.loadPublished(projectRoot).getFirst();
        assertEquals(newer.contentHash(), persisted.contentHash());
        assertTrue(persisted.smells().isEmpty());
    }

    @Test
    void publicationWaitsForProjectWriterLock(@TempDir Path projectRoot) throws Exception {
        Path indexDir = projectRoot.resolve(".jsrc");
        Files.createDirectories(indexDir);
        var entry = new IndexEntry("A.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);
        var started = new CountDownLatch(1);

        try (var channel = FileChannel.open(indexDir.resolve("index.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock();
             var executor = Executors.newSingleThreadExecutor()) {
            var publication = executor.submit(() -> {
                started.countDown();
                new CodebaseIndex(List.of(entry)).saveWithGraph(projectRoot, null);
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> publication.get(250, TimeUnit.MILLISECONDS));
            lock.release();
            publication.get(5, TimeUnit.SECONDS);
        }

        assertTrue(Files.isRegularFile(indexDir.resolve("current")));
    }

    @Test
    void publicationWaitsForAnotherJvmWriter(@TempDir Path projectRoot) throws Exception {
        Path indexDir = projectRoot.resolve(".jsrc");
        Files.createDirectories(indexDir);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process holder = new ProcessBuilder(java.toString(), "-cp",
                System.getProperty("java.class.path"), LockHolder.class.getName(),
                indexDir.resolve("index.lock").toString())
                .redirectErrorStream(true).start();
        try (var output = new java.io.BufferedReader(
                new java.io.InputStreamReader(holder.getInputStream()));
             var executor = Executors.newSingleThreadExecutor()) {
            assertEquals("LOCKED", output.readLine());
            var entry = new IndexEntry("A.java", "hash", 0L, SourceSet.UNKNOWN,
                    List.of(), List.of(), List.of(), 0);
            var publication = executor.submit(() -> {
                new CodebaseIndex(List.of(entry)).saveWithGraph(projectRoot, null);
                return null;
            });
            assertThrows(TimeoutException.class,
                    () -> publication.get(250, TimeUnit.MILLISECONDS));
            holder.getOutputStream().close();
            assertTrue(holder.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, holder.exitValue());
            publication.get(5, TimeUnit.SECONDS);
        } finally {
            holder.destroyForcibly();
        }
        assertTrue(Files.isRegularFile(indexDir.resolve("current")));
    }

    public static final class LockHolder {
        private LockHolder() {
        }

        public static void main(String[] args) throws Exception {
            try (var channel = FileChannel.open(Path.of(args[0]),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                System.out.println("LOCKED");
                System.in.read();
            }
        }
    }

    @Test
    void normalLoadRebuildsCorruptPublishedGeneration(@TempDir Path projectRoot) throws Exception {
        Path source = projectRoot.resolve("A.java");
        Files.writeString(source, "class A {}");
        var entry = new IndexEntry("A.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);
        new CodebaseIndex(List.of(entry)).saveWithGraph(projectRoot, null);

        Path binary;
        try (var generations = Files.list(projectRoot.resolve(".jsrc/generations"))) {
            binary = generations.filter(path -> path.toString().endsWith(".bin"))
                    .findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(binary);
        bytes[bytes.length - 1] ^= 1;
        Files.write(binary, bytes);

        IndexedCodebase recovered = IndexedCodebase.tryLoad(projectRoot, List.of(source), false);

        assertNotNull(recovered, "normal mode should rebuild rather than abandon a corrupt cache");
        assertEquals(1, recovered.fileCount());
        assertNotNull(IndexedCodebase.tryLoad(projectRoot, List.of(source), true));
    }

    @Test
    void frozenReaderRejectsDifferentGitTreeAfterBranchSwitch(@TempDir Path projectRoot)
            throws Exception {
        Path source = projectRoot.resolve("A.java");
        runGit(projectRoot, "init", "-q", "-b", "main");
        Files.writeString(source, "class A { void oldMethod() {} }");
        runGit(projectRoot, "add", "A.java");
        runGit(projectRoot, "-c", "user.name=Test", "-c", "user.email=test@example.org",
                "commit", "-qm", "main");
        runGit(projectRoot, "switch", "-qc", "feature");
        Files.writeString(source, "class A { void newMethod() {} }");
        runGit(projectRoot, "add", "A.java");
        runGit(projectRoot, "-c", "user.name=Test", "-c", "user.email=test@example.org",
                "commit", "-qm", "feature");
        runGit(projectRoot, "switch", "-q", "main");

        var entry = new IndexEntry("A.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);
        new CodebaseIndex(List.of(entry)).saveWithGraph(projectRoot, null);
        runGit(projectRoot, "switch", "-q", "feature");

        assertThrows(com.jsrc.app.exception.JsrcIOException.class,
                () -> IndexedCodebase.tryLoad(projectRoot, List.of(source), true));
    }

    @Test
    void normalReaderRefreshesAfterBranchSwitch(@TempDir Path projectRoot) throws Exception {
        Path source = projectRoot.resolve("A.java");
        runGit(projectRoot, "init", "-q", "-b", "main");
        Files.writeString(source, "class A { void oldMethod() {} }");
        runGit(projectRoot, "add", "A.java");
        runGit(projectRoot, "-c", "user.name=Test", "-c", "user.email=test@example.org",
                "commit", "-qm", "main");
        runGit(projectRoot, "switch", "-qc", "feature");
        Files.writeString(source, "class A { void newMethod() {} }");
        runGit(projectRoot, "add", "A.java");
        runGit(projectRoot, "-c", "user.name=Test", "-c", "user.email=test@example.org",
                "commit", "-qm", "feature");
        runGit(projectRoot, "switch", "-q", "main");

        var index = new CodebaseIndex();
        index.build(new com.jsrc.app.parser.HybridJavaParser(),
                List.of(source), projectRoot, List.of());
        index.saveWithGraph(projectRoot, null, null, true);
        runGit(projectRoot, "switch", "-q", "feature");

        var refreshed = IndexedCodebase.tryLoad(projectRoot, List.of(source), false);
        assertEquals(com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(source)),
                refreshed.getEntries().getFirst().contentHash());
        assertTrue(refreshed.findMethodsByName("newMethod").size() > 0);
    }

    @Test
    void publicationRetainsOnlyCurrentAndPreviousGenerations(@TempDir Path projectRoot)
            throws Exception {
        var entry = new IndexEntry("A.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);
        var index = new CodebaseIndex(List.of(entry));

        index.saveWithGraph(projectRoot, null);
        index.saveWithGraph(projectRoot, null);
        index.saveWithGraph(projectRoot, null);

        try (var generations = Files.list(projectRoot.resolve(".jsrc/generations"))) {
            assertEquals(2, generations.filter(path -> path.toString().endsWith(".bin")).count());
        }
    }

    @Test
    void mappedReaderKeepsItsGenerationAcrossPublicationAndGc(@TempDir Path projectRoot)
            throws Exception {
        var graph = com.jsrc.app.analysis.CallGraph.empty();
        var oldEntry = new IndexEntry("Old.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);
        new CodebaseIndex(List.of(oldEntry)).saveWithGraph(projectRoot, graph);
        var oldReader = IndexSnapshotStore.readCurrent(projectRoot, false);
        var newEntry = new IndexEntry("New.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);

        new CodebaseIndex(List.of(newEntry)).saveWithGraph(projectRoot, graph);
        new CodebaseIndex(List.of(newEntry)).saveWithGraph(projectRoot, graph);

        assertEquals("Old.java", oldReader.getData().entries().getFirst().path());
        assertNotNull(oldReader.ensureGraph());
        assertEquals("New.java", CodebaseIndex.loadPublished(projectRoot).getFirst().path());
    }

    @Test
    void invalidNewGenerationDoesNotReplacePublishedSnapshot(@TempDir Path projectRoot)
            throws Exception {
        var valid = new IndexEntry("A.java", "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);
        new CodebaseIndex(List.of(valid)).saveWithGraph(projectRoot, null);
        Path manifest = projectRoot.resolve(".jsrc/current");
        String before = Files.readString(manifest);
        var invalid = new IndexEntry("x".repeat(70_000), "hash", 0L, SourceSet.UNKNOWN,
                List.of(), List.of(), List.of(), 0);

        assertThrows(IOException.class,
                () -> new CodebaseIndex(List.of(invalid)).saveWithGraph(projectRoot, null));
        assertEquals(before, Files.readString(manifest));
    }

    @Test
    void changedSourceCannotBePublishedAsCurrent(@TempDir Path projectRoot) throws Exception {
        Path source = projectRoot.resolve("A.java");
        Files.writeString(source, "class A {}");
        var entry = new IndexEntry("A.java",
                com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(source)),
                0L, SourceSet.UNKNOWN, List.of(), List.of(), List.of(), 0);
        var index = new CodebaseIndex(List.of(entry));
        index.saveWithGraph(projectRoot, null);
        String before = Files.readString(projectRoot.resolve(".jsrc/current"));

        Files.writeString(source, "class A { void changed() {} }");

        assertThrows(IOException.class,
                () -> index.saveWithGraph(projectRoot, null, null, true));
        assertEquals(before, Files.readString(projectRoot.resolve(".jsrc/current")));
        try (var generations = Files.list(projectRoot.resolve(".jsrc/generations"))) {
            assertEquals(1, generations.filter(path -> path.toString().endsWith(".bin")).count());
        }
    }

    @Test
    void missingDiscoveredSourceDoesNotPublishPartialIndex(@TempDir Path projectRoot)
            throws Exception {
        Path source = projectRoot.resolve("A.java");
        Files.writeString(source, "class A {}");
        var entry = new IndexEntry("A.java",
                com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(source)),
                0L, SourceSet.UNKNOWN, List.of(), List.of(), List.of(), 0);
        new CodebaseIndex(List.of(entry)).saveWithGraph(projectRoot, null);
        String before = Files.readString(projectRoot.resolve(".jsrc/current"));
        Files.delete(source);

        assertThrows(com.jsrc.app.exception.JsrcIOException.class,
                () -> IndexedCodebase.tryLoad(projectRoot, List.of(source), false));
        assertEquals(before, Files.readString(projectRoot.resolve(".jsrc/current")));
    }

    private static void runGit(Path projectRoot, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(projectRoot.toString());
        command.addAll(List.of(args));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException missingGit) {
            org.junit.jupiter.api.Assumptions.abort("Git is unavailable");
            return;
        }
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }
}
