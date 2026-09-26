package com.jsrc.app.index;

import com.jsrc.app.analysis.CallGraph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

final class IndexSnapshotStore {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(IndexSnapshotStore.class);
    private static final String MANIFEST_VERSION = "JSRC-SNAPSHOT-1";
    private static final int MAX_MANIFEST_BYTES = 512;
    private static final int MAX_GENERATIONS = 8;
    private static final long LOCK_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private IndexSnapshotStore() {
    }

    static Path currentBinary(Path projectRoot, boolean checkGitTree) throws IOException {
        Path indexDir = projectRoot.resolve(".jsrc");
        Path manifest = indexDir.resolve("current");
        if (!Files.exists(manifest)) {
            if (checkGitTree && Files.exists(indexDir.resolve("index.bin"))) {
                throw new IOException("Legacy index has no snapshot identity. "
                        + "Run 'jsrc index' to migrate before using --frozen-index.");
            }
            return indexDir.resolve("index.bin");
        }

        Manifest current = readManifest(manifest);
        if (checkGitTree && !"-".equals(current.gitTree())) {
            String currentTree = gitTree(projectRoot);
            if (!"-".equals(currentTree) && !current.gitTree().equals(currentTree)) {
                throw new IOException("Index snapshot belongs to a different Git tree. "
                        + "Run 'jsrc index' after switching branches.");
            }
        }
        Path binary = indexDir.resolve("generations").resolve(current.generation());
        if (!Files.isRegularFile(binary) || Files.size(binary) != current.size()) {
            throw new IOException("Index snapshot missing or truncated: " + binary);
        }
        return binary;
    }

    static Path currentBinary(Path projectRoot) throws IOException {
        return currentBinary(projectRoot, false);
    }

    static BinaryIndexV2Reader.LazyIndexData readCurrent(Path projectRoot,
                                                          boolean checkGitTree) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return BinaryIndexV2Reader.readLazy(currentBinary(projectRoot, checkGitTree));
            } catch (IOException ex) {
                lastFailure = ex;
            }
        }
        throw lastFailure;
    }

    static void publish(Path indexDir, List<IndexEntry> entries, CallGraph graph,
                        Map<String, List<CachedMigration>> migrations) throws IOException {
        publish(indexDir, entries, graph, migrations, false);
    }

    static void publish(Path indexDir, List<IndexEntry> entries, CallGraph graph,
                        Map<String, List<CachedMigration>> migrations,
                        boolean validateSources) throws IOException {
        withWriterLock(indexDir, locked ->
                publishLocked(locked, entries, graph, migrations, validateSources));
    }

    static void updateSmells(Path projectRoot, List<IndexEntry> updates) throws IOException {
        Path indexDir = projectRoot.resolve(".jsrc");
        withWriterLock(indexDir, locked -> {
            if (!Files.isRegularFile(locked.resolve("current"))) {
                throw new IOException("No published index snapshot for smell cache");
            }
            var snapshot = BinaryIndexV2Reader.read(currentBinary(projectRoot));
            Map<String, IndexEntry> byPath = new java.util.HashMap<>();
            for (IndexEntry update : updates) {
                byPath.put(update.path(), update);
            }
            boolean changed = false;
            List<IndexEntry> merged = new java.util.ArrayList<>(snapshot.entries().size());
            for (IndexEntry entry : snapshot.entries()) {
                IndexEntry update = byPath.get(entry.path());
                if (update != null && !entry.contentHash().equals(update.contentHash())) {
                    throw new IOException("Source changed before smell cache update: "
                            + entry.path());
                }
                if (update != null && !entry.smells().equals(update.smells())) {
                    merged.add(entry.withSmells(update.smells()));
                    changed = true;
                } else {
                    merged.add(entry);
                }
            }
            if (changed) {
                publishLocked(locked, merged, snapshot.callGraph(),
                        snapshot.migrations(), true);
            }
        });
    }

    @FunctionalInterface
    private interface LockedOperation {
        void run(Path indexDir) throws IOException;
    }

    private static void withWriterLock(Path indexDir, LockedOperation operation)
            throws IOException {
        Path canonicalDir = indexDir.toAbsolutePath().normalize();
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(canonicalDir,
                ignored -> new ReentrantLock());
        try {
            if (!jvmLock.tryLock(2, TimeUnit.MINUTES)) {
                throw new IOException("Timed out waiting for index writer lock: " + canonicalDir);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for index writer lock", ex);
        }
        try {
            Files.createDirectories(canonicalDir);
            try (FileChannel channel = FileChannel.open(canonicalDir.resolve("index.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = acquireFileLock(channel)) {
                operation.run(canonicalDir);
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private static FileLock acquireFileLock(FileChannel channel) throws IOException {
        long deadline = System.nanoTime() + LOCK_TIMEOUT_NANOS;
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // Another thread in this JVM already owns the OS lock.
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IOException("Timed out waiting for index writer lock");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for index writer lock", ex);
            }
        }
    }

    private static void publishLocked(Path indexDir, List<IndexEntry> entries, CallGraph graph,
                                      Map<String, List<CachedMigration>> migrations,
                                      boolean validateSources) throws IOException {
        Path generations = indexDir.resolve("generations");
        Files.createDirectories(generations);
        String previous = "-";
        String older = "-";
        Path currentPath = indexDir.resolve("current");
        if (Files.exists(currentPath)) {
            try {
                Manifest current = readManifest(currentPath);
                previous = current.generation();
                older = current.previous();
            } catch (IOException ex) {
                logger.warn("Replacing invalid index manifest: {}", ex.getMessage());
            }
        }
        pruneGenerations(generations, previous, older, true);
        String generation = UUID.randomUUID() + ".bin";
        Path binary = generations.resolve(generation);
        Path temporary = indexDir.resolve("current." + UUID.randomUUID() + ".tmp");
        try {
            BinaryIndexV2Writer.write(binary, entries, graph, migrations);
            forceFile(binary);
            forceDirectory(generations);
            BinaryIndexV2Reader.read(binary);
            if (validateSources) {
                verifySources(indexDir.getParent(), entries);
            }

            String manifest = MANIFEST_VERSION + "\n" + generation + "\n"
                    + Files.size(binary) + "\n" + gitTree(indexDir.getParent()) + "\n"
                    + previous + "\n";
            Files.writeString(temporary, manifest, StandardCharsets.UTF_8);
            forceFile(temporary);
            Files.move(temporary, currentPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(temporary);
                Files.deleteIfExists(binary);
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
        forceDirectory(indexDir);
        try {
            pruneGenerations(generations, generation, previous, false);
        } catch (IOException ex) {
            logger.warn("Could not clean old index generations: {}", ex.getMessage());
        }
    }

    private static void pruneGenerations(Path generations, String current, String previous,
                                         boolean enforceLimit) throws IOException {
        try (var paths = Files.list(generations)) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".bin"))
                    .toList()) {
                String name = path.getFileName().toString();
                if (name.equals(current) || name.equals(previous)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    logger.warn("Could not clean old index generation {}: {}",
                            path, ex.getMessage());
                }
            }
        }
        if (enforceLimit) {
            try (var paths = Files.list(generations)) {
                if (paths.filter(path -> path.getFileName().toString().endsWith(".bin"))
                        .count() >= MAX_GENERATIONS) {
                    throw new IOException("Too many retained index generations; close readers "
                            + "and remove stale generations before indexing again");
                }
            }
        }
    }

    private static void verifySources(Path projectRoot, List<IndexEntry> entries)
            throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        for (IndexEntry entry : entries) {
            Path file = root.resolve(entry.path()).normalize();
            if (!Files.isRegularFile(file)) {
                throw new IOException("Source changed while indexing: " + entry.path());
            }
            String actual = com.jsrc.app.util.Hashing.sha256(Files.readAllBytes(file));
            if (!actual.equals(entry.contentHash())) {
                throw new IOException("Source changed while indexing: " + entry.path());
            }
        }
    }

    private static Manifest readManifest(Path file) throws IOException {
        if (Files.size(file) > MAX_MANIFEST_BYTES) {
            throw new IOException("Invalid index snapshot manifest size: " + file);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() != 5 || !MANIFEST_VERSION.equals(lines.get(0))
                || !validGeneration(lines.get(1))
                || !("-".equals(lines.get(4)) || validGeneration(lines.get(4)))
                || !("-".equals(lines.get(3)) || lines.get(3).matches("[0-9a-f]{40,64}"))) {
            throw new IOException("Invalid index snapshot manifest: " + file);
        }
        try {
            long size = Long.parseLong(lines.get(2));
            if (size < 12) {
                throw new IOException("Invalid index snapshot size: " + file);
            }
            return new Manifest(lines.get(1), size, lines.get(3), lines.get(4));
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid index snapshot size: " + file, ex);
        }
    }

    private static boolean validGeneration(String value) {
        return value.matches("[0-9a-f-]{36}\\.bin");
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ex) {
            logger.warn("Directory sync unavailable for {}; power-loss durability is reduced: {}",
                    directory, ex.getMessage());
        }
    }

    private record Manifest(String generation, long size, String gitTree, String previous) {
    }

    private static String gitTree(Path projectRoot) {
        Process process;
        try {
            process = new ProcessBuilder("git", "-C", projectRoot.toString(),
                    "rev-parse", "HEAD^{tree}")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "-";
            }
            if (process.exitValue() != 0) {
                return "-";
            }
            String tree = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            return tree.matches("[0-9a-f]{40,64}") ? tree : "-";
        } catch (IOException ex) {
            return "-";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "-";
        }
    }
}
