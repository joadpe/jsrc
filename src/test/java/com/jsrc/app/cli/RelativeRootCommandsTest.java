package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexEntry;
import com.jsrc.app.util.Hashing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelativeRootCommandsTest {

    @TempDir
    Path tempDir;

    @Test
    void diffReadsPublishedSnapshotForEveryRootMode() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path source = Files.writeString(project.resolve("Demo.java"), "class Demo {}\n");
        assertTrue(run(project, "index").contains("Done. Indexed"));
        long indexedTime = Files.getLastModifiedTime(source).toMillis();
        Files.writeString(source, "class Demo { int value; }\n");
        Files.setLastModifiedTime(source, FileTime.fromMillis(indexedTime + 2000));
        Files.writeString(project.resolve(".jsrc/index.json"),
                "[{\"path\":\"Ghost.java\",\"contentHash\":\"stale\",\"lastModified\":0,\"classes\":[]}]");

        for (String output : List.of(
                run(project, "--json", "diff"),
                run(tempDir, "--dir", "project", "--json", "diff"),
                run(tempDir, "--dir", project.toString(), "--json", "diff"))) {
            assertTrue(output.contains("\"modified\":[\"Demo.java\"]"), output);
            assertTrue(output.contains("\"deleted\":[]"), output);
        }
    }

    @Test
    void diffFallsBackToLegacyIndexWithAbsoluteRoot() throws Exception {
        Path source = Files.writeString(tempDir.resolve("Demo.java"), "class Demo {}\n");
        String hash = Hashing.sha256(Files.readAllBytes(source));
        long indexedTime = Files.getLastModifiedTime(source).toMillis();
        new CodebaseIndex(List.of(new IndexEntry("Demo.java", hash, indexedTime, List.of())))
                .save(tempDir);
        Files.writeString(source, "class Demo { int value; }\n");
        Files.setLastModifiedTime(source, FileTime.fromMillis(indexedTime + 2000));

        String output = run(tempDir, "--dir", tempDir.toString(), "--json", "diff");

        assertTrue(output.contains("\"modified\":[\"Demo.java\"]"), output);
    }

    @Test
    void diffDoesNotReportTestsAsDeletedWhenExcluded() throws Exception {
        Path main = tempDir.resolve("src/main/java/Main.java");
        Path test = tempDir.resolve("src/test/java/MainTest.java");
        Files.createDirectories(main.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(main, "class Main {}\n");
        Files.writeString(test, "class MainTest {}\n");
        assertTrue(run(tempDir, "index").contains("Done. Indexed"));

        for (String output : List.of(
                run(tempDir, "--no-test", "--json", "diff"),
                run(tempDir, "--source-set", "main", "--json", "diff"))) {
            assertTrue(output.contains("\"modified\":[]"), output);
            assertTrue(output.contains("\"added\":[]"), output);
            assertTrue(output.contains("\"deleted\":[]"), output);
        }
    }

    @Test
    void diffAppliesNoTestToLegacyEntriesWithoutSourceSet() throws Exception {
        Path main = tempDir.resolve("src/main/java/Main.java");
        Path test = tempDir.resolve("src/test/java/MainTest.java");
        Files.createDirectories(main.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(main, "class Main {}\n");
        Files.writeString(test, "class MainTest {}\n");
        new CodebaseIndex(List.of(
                legacyEntry(tempDir, main), legacyEntry(tempDir, test))).save(tempDir);

        String output = run(tempDir, "--no-test", "--json", "diff");

        assertTrue(output.contains("\"deleted\":[]"), output);
        assertTrue(output.contains("\"totalChanges\":0"), output);
    }

    @Test
    void diffRejectsInvalidManifestInsteadOfReadingLegacyIndex() throws Exception {
        Path source = Files.writeString(tempDir.resolve("Demo.java"), "class Demo {}\n");
        String hash = Hashing.sha256(Files.readAllBytes(source));
        new CodebaseIndex(List.of(new IndexEntry("Demo.java", hash,
                Files.getLastModifiedTime(source).toMillis(), List.of()))).save(tempDir);
        Path manifest = tempDir.resolve(".jsrc/current");
        Files.createDirectory(manifest);

        String output = run(tempDir, "--json", "diff");

        assertTrue(output.contains("Error: Invalid published index manifest"), output);
        assertTrue(!output.contains("\"totalChanges\":0"), output);
    }

    @Test
    void diffRejectsBrokenManifestSymlink() throws Exception {
        Path source = Files.writeString(tempDir.resolve("Demo.java"), "class Demo {}\n");
        new CodebaseIndex(List.of(legacyEntry(tempDir, source))).save(tempDir);
        try {
            Files.createSymbolicLink(tempDir.resolve(".jsrc/current"), Path.of("missing"));
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            assumeTrue(false, "Symbolic links unavailable: " + ex.getMessage());
        }

        String output = run(tempDir, "--json", "diff");

        assertTrue(output.contains("Error: Invalid published index manifest"), output);
        assertTrue(!output.contains("\"totalChanges\":0"), output);
    }

    @Test
    void smellsAllReportsProjectRelativePathForEveryRootMode() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(project.resolve("Demo.java"), smellySource());

        for (String output : List.of(
                run(project, "--json", "smells", "--all"),
                run(tempDir, "--dir", "project", "--json", "smells", "--all"),
                run(tempDir, "--dir", project.toString(), "--json", "smells", "--all"))) {
            assertTrue(output.contains("TOO_MANY_PARAMETERS"), output);
            assertTrue(output.contains("\"file\":\"Demo.java\""), output);
        }
    }

    @Test
    void smellsTrendWorksForEveryRootMode() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(project.resolve("Demo.java"), smellySource());

        for (String output : List.of(
                run(project, "--json", "smells", "--trend"),
                run(tempDir, "--dir", "project", "--json", "smells", "--trend"),
                run(tempDir, "--dir", project.toString(), "--json", "smells", "--trend"))) {
            assertTrue(output.matches("(?s).*\"totalSmells\":[1-9][0-9]*.*"), output);
            assertTrue(output.contains("trend"), output);
        }
    }

    @Test
    void todoReportsMarkerForEveryRootMode() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(project.resolve("Demo.java"), "class Demo {\n    // TODO: fix this\n}\n");

        for (String output : List.of(
                run(project, "--json", "todo"),
                run(tempDir, "--dir", "project", "--json", "todo"),
                run(tempDir, "--dir", project.toString(), "--json", "todo"))) {
            assertTrue(output.contains("\"total\":1"), output);
            assertTrue(output.contains("fix this"), output);
            assertTrue(output.contains("\"file\":\"Demo.java\""), output);
        }
    }

    private static String smellySource() {
        return "class Demo { void run(int a, int b, int c, int d, int e, int f, int g, int h) {} }\n";
    }

    private static IndexEntry legacyEntry(Path root, Path source) throws IOException {
        return new IndexEntry(root.relativize(source).toString(),
                Hashing.sha256(Files.readAllBytes(source)),
                Files.getLastModifiedTime(source).toMillis(), List.of());
    }

    private static String run(Path workingDirectory, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(java, "--enable-native-access=ALL-UNNAMED",
                "-cp", classpath, "com.jsrc.app.App"));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Command timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return output;
    }
}
