package com.jsrc.app.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.config.ArchitectureConfig;
import com.jsrc.app.config.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    @Test
    void doubleStarInsidePatternSpansWindowsDirectories() {
        Path generated = Path.of("C:\\work\\legacy\\src\\a\\b\\gen\\F.java");
        Path kept = Path.of("C:\\work\\legacy\\src\\a\\b\\main\\G.java");

        assertEquals(List.of(kept), ProjectSourceDiscovery.filterExcludes(
                List.of(generated, kept), List.of("src/**/gen/**")));
    }

    @Test
    void doubleStarInsidePatternSpansLineBreaksInNames() {
        // Linux allows line breaks in file names, and "**" spans every character.
        Path generated = Path.of("/work/src/a\nb/gen/F.java");
        Path kept = Path.of("/work/src/a\nb/main/G.java");

        assertEquals(List.of(kept), ProjectSourceDiscovery.filterExcludes(
                List.of(generated, kept), List.of("src/**/gen/**")));
    }

    @Test
    void singleStarStaysWithinOneName() {
        Path oneLevel = Path.of("/work/src/a/gen/F.java");
        Path windowsOneLevel = Path.of("C:\\work\\src\\a\\gen\\G.java");
        Path twoLevels = Path.of("/work/src/a/b/gen/H.java");
        Path windowsTwoLevels = Path.of("C:\\work\\src\\a\\b\\gen\\I.java");
        Path oneName = Path.of("/work/src/LegacyBillingTest.java");
        Path twoNames = Path.of("/work/src/Legacy/BillingTest.java");

        assertEquals(List.of(twoLevels, windowsTwoLevels), ProjectSourceDiscovery.filterExcludes(
                List.of(oneLevel, windowsOneLevel, twoLevels, windowsTwoLevels),
                List.of("src/*/gen/**")));
        assertEquals(List.of(twoNames), ProjectSourceDiscovery.filterExcludes(
                List.of(oneName, twoNames), List.of("**/Legacy*Test.java")));
    }

    @Test
    void plainPatternsMatchAnywhereInThePath() {
        Path test = Path.of("/work/web/test/A.java");
        Path windowsTest = Path.of("C:\\work\\web\\test\\B.java");
        Path latest = Path.of("/work/web/latest/C.java");
        Path testing = Path.of("/work/web/testing/D.java");
        List<Path> files = List.of(test, windowsTest, latest, testing);

        assertEquals(List.of(latest, testing),
                ProjectSourceDiscovery.filterExcludes(files, List.of("/test/")));
        // Stars around a pattern do not change where it matches.
        assertEquals(List.of(latest, testing),
                ProjectSourceDiscovery.filterExcludes(files, List.of("**/test/**")));
        assertEquals(List.of(test, windowsTest, latest),
                ProjectSourceDiscovery.filterExcludes(files, List.of("testing")));
    }

    @Test
    void dotInPatternMatchesOnlyADot() {
        Path dotted = Path.of("/work/legacy.v1/src/A.java");
        Path dashed = Path.of("/work/legacy-v1/src/B.java");

        assertEquals(List.of(dashed), ProjectSourceDiscovery.filterExcludes(
                List.of(dotted, dashed), List.of("**/legacy.v1/**")));
    }

    @Test
    void otherRegexCharactersInPatternsAreLiteral() {
        Path windowsCopy = Path.of("C:\\work\\src (2)\\A.java");
        Path windowsSource = Path.of("C:\\work\\src 2\\B.java");
        Path plus = Path.of("/work/c++/C.java");
        Path plain = Path.of("/work/c/D.java");
        Path bracket = Path.of("/work/[draft/E.java");

        // As regexes, "(2)" matched "2", "c++" matched "c" and "[draft" did not compile.
        assertEquals(List.of(windowsSource, plain), ProjectSourceDiscovery.filterExcludes(
                List.of(windowsCopy, windowsSource, plus, plain, bracket),
                List.of("**/src (2)/**", "**/c++/**", "**/[draft/**")));
    }

    @Test
    void leadingDotSegmentsMatchLikeASeparator() {
        Path build = Path.of("/work/legacy/build/A.java");
        Path windowsBuild = Path.of("C:\\work\\legacy\\build\\B.java");
        Path rebuild = Path.of("/work/legacy/rebuild/C.java");
        Path shared = Path.of("/work/shared/gen/D.java");
        Path main = Path.of("/work/legacy/src/E.java");
        List<Path> files = List.of(build, windowsBuild, rebuild, shared, main);

        // As in 2.6.1, "./build/" excludes the files that "/build/" excludes.
        assertEquals(List.of(rebuild, shared, main),
                ProjectSourceDiscovery.filterExcludes(files, List.of("./build/")));
        assertEquals(List.of(rebuild, shared, main),
                ProjectSourceDiscovery.filterExcludes(files, List.of(".\\build\\")));
        assertEquals(List.of(build, windowsBuild, rebuild, main),
                ProjectSourceDiscovery.filterExcludes(files, List.of("../shared/gen/**")));
        // Repeated leading segments count as one separator too.
        assertEquals(List.of(rebuild, shared, main),
                ProjectSourceDiscovery.filterExcludes(files, List.of("././build/")));
        assertEquals(List.of(build, windowsBuild, rebuild, main),
                ProjectSourceDiscovery.filterExcludes(files, List.of("../../shared/gen/**")));
    }

    @Test
    void backslashesInPatternsSeparateDirectories() {
        Path test = Path.of("/work/web/test/A.java");
        Path windowsTest = Path.of("C:\\work\\web\\test\\B.java");
        Path main = Path.of("/work/web/src/C.java");

        assertEquals(List.of(main), ProjectSourceDiscovery.filterExcludes(
                List.of(test, windowsTest, main), List.of("**\\test\\**")));
    }

    @Test
    void excludesFilterTenThousandLongPathsQuickly() {
        // 2.6.1 ran String.matches(".*.*/test/.*.*") for every file and pattern: it compiled
        // the regex each time and backtracked quadratically on every path without "/test/".
        // That took about 7 s on these paths. Compiled once, find() still takes over a
        // second when it keeps the leading and trailing stars, and well under 100 ms
        // without them.
        List<Path> files = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) {
            var path = new StringBuilder("/work/legacy/module").append(index % 40).append("/src");
            for (int depth = 0; depth < 16; depth++) {
                path.append("/package").append(depth).append("segment").append(index % 7);
            }
            files.add(Path.of(path.append("/Component").append(index).append(".java").toString()));
        }

        long started = System.nanoTime();
        List<Path> kept = ProjectSourceDiscovery.filterExcludes(
                files, List.of("**/test/**", "**/generated/**"));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(files, kept);
        assertTrue(elapsedMillis < 500, "Filtering 10,000 paths took " + elapsedMillis + " ms");
    }

    private Path write(String relativePath) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class "
                + file.getFileName().toString().replace(".java", "") + " {}");
        return file;
    }
}
