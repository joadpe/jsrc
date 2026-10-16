package com.jsrc.app.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jsrc.app.config.ArchitectureConfig;
import com.jsrc.app.config.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSourceDiscoveryTest {

    @TempDir
    Path root;

    @Test
    void configuredRootWithDotSegmentDoesNotDuplicateFallbackFiles() throws Exception {
        // Without build metadata the whole tree is walked; "./module/src" reaches the
        // same files through a non-normalized path.
        Path app = write("module/src/com/example/App.java");
        Path tool = write("tools/Tool.java");
        var config = new ProjectConfig(
                List.of("./module/src"), List.of(), "", ArchitectureConfig.empty());

        var result = new ProjectSourceDiscovery().discover(root, config);

        assertEquals(List.of(app, tool), result.allFiles());
        assertEquals(Map.of(app, SourceSet.MAIN, tool, SourceSet.UNKNOWN),
                result.allSourceSets());
        assertEquals(result.allSourceSets(), result.sourceSets());
    }

    @Test
    void excludesMatchWindowsSeparators() {
        Path windowsTest = Path.of("C:\\work\\legacy\\web\\test\\ServletTest.java");
        Path windowsMain = Path.of("C:\\work\\legacy\\web\\src\\Servlet.java");

        assertEquals(List.of(windowsMain), ProjectSourceDiscovery.filterExcludes(
                List.of(windowsTest, windowsMain), List.of("**/test/**")));
    }

    @Test
    void excludesApplyToFilesReachedThroughDotSegments() throws Exception {
        // The pattern spans the project directory, so "legacy/./module" must not slip past it.
        Path app = write("legacy/module/src/com/example/App.java");
        write("legacy/module/src/gen/Generated.java");
        var config = new ProjectConfig(
                List.of("./module/src"), List.of("legacy/module/src/gen/**"), "",
                ArchitectureConfig.empty());

        var result = new ProjectSourceDiscovery().discover(root.resolve("legacy"), config);

        assertEquals(List.of(app), result.allFiles());
    }

    @Test
    void doubleStarInsidePatternSpansDirectories() {
        Path generated = Path.of("/work/src/a/b/gen/F.java");
        Path kept = Path.of("/work/src/a/b/main/G.java");

        assertEquals(List.of(kept), ProjectSourceDiscovery.filterExcludes(
                List.of(generated, kept), List.of("src/**/gen/**")));
    }

    private Path write(String relativePath) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class "
                + file.getFileName().toString().replace(".java", "") + " {}");
        return file;
    }
}
