package com.jsrc.app.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectFileDiscoveryTest {

    @TempDir
    Path root;

    @Test
    void discoversOnlyJavaFilesFromModeledSourceRoots() throws Exception {
        Path main = writeJava("module/src/main/java/App.java");
        Path test = writeJava("module/src/test/java/AppTest.java");
        Path generated = writeJava(
                "module/target/generated-sources/annotations/Generated.java");
        writeJava("module/scratch/Ignored.java");
        ProjectModule module = new ProjectModule(
                "module",
                root.resolve("module"),
                List.of(root.resolve("module/src/main/java")),
                List.of(root.resolve("module/src/test/java")),
                List.of(root.resolve("module/target/generated-sources/annotations")),
                List.of(root.resolve("module/target")),
                List.of());
        ProjectModel model = new ProjectModel(
                root, BuildSystem.MAVEN, "21", List.of(module), List.of());

        List<Path> files = new ProjectFileDiscovery().discover(model);

        assertEquals(List.of(generated, main, test).stream().sorted().toList(), files);
    }

    private Path writeJava(String relativePath) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class " + file.getFileName().toString().replace(".java", "") + " {}");
        return file;
    }
}
