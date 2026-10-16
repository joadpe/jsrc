package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.output.JsonReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Build-model problems reach stderr; JSON output and exit codes do not change. */
class BuildModelDiagnosticsCliTest {

    private static final String UNREADABLE_POM =
            "BUILD_MODEL_PARTIAL: Could not fully interpret pom.xml: ";

    @TempDir
    Path projectRoot;

    @Test
    void indexPrintsUnreadableBuildFileBeforeTheSourceLevelItLeftUnknown() throws Exception {
        writeUnreadablePom("");
        Path sources = Files.createDirectories(projectRoot.resolve("src/main/java/com/example"));
        Files.writeString(sources.resolve("Invoice.java"),
                "package com.example; public class Invoice {}");

        Run index = run("--dir", projectRoot.toString(), "index");

        assertEquals(0, index.exitCode(), index.stderr());
        List<String> lines = index.stderr().lines().toList();
        assertEquals(1, lines.stream().filter(line -> line.startsWith(UNREADABLE_POM)).count(),
                index.stderr());
        int unknownLevel = indexOfLineStartingWith(lines, "SOURCE_LEVEL_UNKNOWN: 1 file(s)");
        assertTrue(unknownLevel >= 0, index.stderr());
        assertTrue(indexOfLineStartingWith(lines, UNREADABLE_POM) < unknownLevel, index.stderr());
    }

    @Test
    void indexExplainsWhyAModuleBuildItCouldNotReadIndexedNothing() throws Exception {
        writeUnreadablePom("<packaging>pom</packaging><modules><module>billing</module></modules>");
        Path module = Files.createDirectories(projectRoot.resolve("billing"));
        Files.writeString(module.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>billing</artifactId><version>1</version>
                </project>
                """);
        Path sources = Files.createDirectories(module.resolve("src/main/java/com/example"));
        Files.writeString(sources.resolve("Invoice.java"),
                "package com.example; public class Invoice {}");

        Run index = run("--dir", projectRoot.toString(), "index");

        List<String> lines = index.stderr().lines().toList();
        int indexing = indexOfLineStartingWith(lines, "Indexing 0 Java files");
        assertTrue(indexing >= 0, index.stderr());
        int problem = indexOfLineStartingWith(lines, UNREADABLE_POM);
        assertTrue(problem >= 0 && problem < indexing, index.stderr());
    }

    @Test
    void printsEachBuildModelProblemOnceWithoutChangingJsonOrExitCodes() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing', 'ledger'\n");
        Files.writeString(projectRoot.resolve("build.gradle"),
                "sourceCompatibility = JavaVersion.VERSION_17\n");
        // Both modules declare the same root outside the project: one identical message each.
        for (String module : List.of("billing", "ledger")) {
            Files.writeString(Files.createDirectories(projectRoot.resolve(module))
                            .resolve("build.gradle"),
                    "sourceSets.main.java.srcDirs = ['../../shared/src']\n");
        }
        Path sources = Files.createDirectories(projectRoot.resolve("src/main/java/com/example"));
        Files.writeString(sources.resolve("App.java"), "package com.example; public class App {}");

        Run index = run("--dir", projectRoot.toString(), "index");

        assertEquals(0, index.exitCode(), index.stderr());
        assertEquals(
                List.of("BUILD_MODEL_PARTIAL: Gradle main source root escapes project root:"
                        + " ../../shared/src"),
                index.stderr().lines()
                        .filter(line -> line.startsWith("BUILD_MODEL_")).toList(),
                index.stderr());

        Run versioned = run("--dir", projectRoot.toString(), "--json", "--protocol", "1",
                "classes");
        assertEquals(0, versioned.exitCode(), versioned.stderr());
        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(versioned.stdout().trim()));
        assertEquals("ok", envelope.get("status"));
        assertEquals(List.of(), envelope.get("diagnostics"));
        assertEquals(0, run("--dir", projectRoot.toString(), "classes").exitCode());
    }

    @Test
    void keepsTheConventionalLayoutFallbackOffStderr() throws Exception {
        Path sources = Files.createDirectories(projectRoot.resolve("web/src/com/example"));
        Files.writeString(sources.resolve("Page.java"),
                "package com.example; public class Page {}");
        Files.writeString(projectRoot.resolve(".jsrc.yaml"), """
                sourceRoots:
                  - ./web/src
                javaVersion: "8"
                """);

        Run index = run("--dir", projectRoot.toString(), "index");

        assertEquals(0, index.exitCode(), index.stderr());
        assertFalse(index.stderr().contains("BUILD_MODEL"), index.stderr());
        Run overview = run("--dir", projectRoot.toString(), "--json", "overview");
        Map<?, ?> output = assertInstanceOf(Map.class, JsonReader.parse(overview.stdout().trim()));
        Map<?, ?> project = assertInstanceOf(Map.class, output.get("project"));
        List<?> diagnostics = assertInstanceOf(List.class, project.get("diagnostics"));
        assertEquals("BUILD_MODEL_FALLBACK", ((Map<?, ?>) diagnostics.getFirst()).get("code"));
    }

    @Test
    void commandsThatReadNoSourcesDoNotReportTheBuildModel() throws Exception {
        writeUnreadablePom("");

        Run describe = run("--dir", projectRoot.toString(), "describe");

        assertFalse(describe.stderr().contains("BUILD_MODEL"), describe.stderr());
    }

    /**
     * Writes a truncated pom.xml. Any build file that cannot be read takes the same path; this
     * one stays unreadable whatever encodings build files may be decoded with.
     */
    private void writeUnreadablePom(String extraElements) throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>legacy</artifactId><version>1</version>
                %s
                """.formatted(extraElements));
    }

    private static int indexOfLineStartingWith(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static Run run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            exitCode = JsrcCliFactory.create().execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Run(exitCode, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Run(int exitCode, String stdout, String stderr) {}
}
