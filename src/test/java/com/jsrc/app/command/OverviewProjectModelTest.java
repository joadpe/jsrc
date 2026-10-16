package com.jsrc.app.command;

import com.jsrc.app.command.navigate.OverviewCommand;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.project.BuildSystem;
import com.jsrc.app.project.ProjectDiagnostic;
import com.jsrc.app.project.ProjectModel;
import com.jsrc.app.project.ProjectModule;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverviewProjectModelTest {

    @TempDir
    Path root;

    @Test
    void overviewIncludesOfflineProjectModel() {
        ProjectModule module = new ProjectModule(
                "application",
                root.resolve("application"),
                List.of(root.resolve("application/src/main/java")),
                List.of(root.resolve("application/src/test/java")),
                List.of(),
                List.of(root.resolve("application/target")),
                List.of("domain"));
        ProjectModel model = new ProjectModel(
                root, BuildSystem.MAVEN, "21", List.of(module), List.of());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CommandContext context = new CommandContext(
                List.of(), root.toString(), null,
                new JsonFormatter(false, null, new PrintStream(bytes)),
                null, new HybridJavaParser(), false, null, false, false, null, false,
                model);

        new OverviewCommand().execute(context);

        Map<?, ?> output = assertInstanceOf(
                Map.class, JsonReader.parse(bytes.toString().trim()));
        Map<?, ?> project = assertInstanceOf(Map.class, output.get("project"));
        assertEquals("maven", project.get("buildSystem"));
        assertEquals("21", project.get("javaVersion"));
        List<?> modules = assertInstanceOf(List.class, project.get("modules"));
        Map<?, ?> application = assertInstanceOf(Map.class, modules.getFirst());
        assertEquals("application", application.get("name"));
        assertEquals(List.of("application/target"), application.get("excludedRoots"));
        assertEquals(List.of("domain"), application.get("internalDependencies"));
    }

    @Test
    void overviewReportsCountedSourceSets() {
        Path moduleRoot = root.resolve("application");
        Path main = moduleRoot.resolve("src/main/java/App.java");
        Path test = moduleRoot.resolve("src/test/java/AppTest.java");
        Path fixtures = moduleRoot.resolve("src/testFixtures/java/Fixture.java");
        Path generated = moduleRoot.resolve("target/generated-sources/annotations/Generated.java");
        ProjectModule module = new ProjectModule(
                "application",
                moduleRoot,
                List.of(moduleRoot.resolve("src/main/java")),
                List.of(
                        moduleRoot.resolve("src/test/java"),
                        moduleRoot.resolve("src/testFixtures/java")),
                List.of(moduleRoot.resolve("target/generated-sources/annotations")),
                List.of(moduleRoot.resolve("target")),
                List.of());
        ProjectModel model = new ProjectModel(
                root, BuildSystem.MAVEN, "21", List.of(module), List.of());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CommandContext context = new CommandContext(
                List.of(main, test, fixtures, generated), root.toString(), null,
                new JsonFormatter(false, null, new PrintStream(bytes)),
                null, new HybridJavaParser(), false, null, false, false, null, false,
                model);

        new OverviewCommand().execute(context);

        Map<?, ?> output = assertInstanceOf(
                Map.class, JsonReader.parse(bytes.toString().trim()));
        Map<?, ?> sourceSets = assertInstanceOf(Map.class, output.get("sourceSets"));
        assertEquals(1L, sourceSets.get("main"));
        assertEquals(1L, sourceSets.get("test"));
        assertEquals(1L, sourceSets.get("testFixtures"));
        assertEquals(1L, sourceSets.get("generated"));
    }

    @Test
    void diagnosticsListCodeBeforeMessageInEveryJvm() throws Exception {
        // Map.of iterates in an order salted once per JVM, so a single JVM may print
        // code first by chance. Print the overview in a JVM where Map.of puts message first.
        String json = null;
        for (int attempt = 0; attempt < 20 && json == null; attempt++) {
            json = overviewInFreshJvm(attempt);
        }
        assertNotNull(json, "No fresh JVM iterated Map.of with message first");

        Map<?, ?> output = assertInstanceOf(Map.class, JsonReader.parse(json));
        Map<?, ?> project = assertInstanceOf(Map.class, output.get("project"));
        List<?> diagnostics = assertInstanceOf(List.class, project.get("diagnostics"));
        Map<?, ?> diagnostic = assertInstanceOf(Map.class, diagnostics.getFirst());
        assertEquals(List.of("code", "message"), List.copyOf(diagnostic.keySet()));
        assertEquals("BUILD_MODEL_PARTIAL", diagnostic.get("code"));
    }

    /** Prints the overview of a model with a diagnostic; see {@link #MAP_OF_IN_ORDER}. */
    public static final class OverviewPrinter {
        private OverviewPrinter() {
        }

        public static void main(String[] args) {
            if (Map.of("code", "", "message", "").keySet().iterator().next().equals("code")) {
                System.exit(MAP_OF_IN_ORDER);
            }
            Path root = Path.of(args[0]);
            ProjectModel model = new ProjectModel(root, BuildSystem.MAVEN, "21", List.of(),
                    List.of(new ProjectDiagnostic(
                            "BUILD_MODEL_PARTIAL", "Maven module core has no pom.xml")));
            CommandContext context = new CommandContext(
                    List.of(), root.toString(), null,
                    new JsonFormatter(false, null, System.out),
                    null, new HybridJavaParser(), false, null, false, false, null, false,
                    model);
            new OverviewCommand().execute(context);
            System.out.flush();
        }
    }

    /**
     * Exit code of {@link OverviewPrinter} in a JVM where Map.of already iterates code first,
     * which cannot tell a Map.of from an insertion-ordered map.
     */
    private static final int MAP_OF_IN_ORDER = 42;

    /** Returns the overview JSON, or null if that JVM iterated Map.of code first. */
    private String overviewInFreshJvm(int attempt) throws Exception {
        Path output = root.resolve("overview-" + attempt + ".out");
        Path errors = root.resolve("overview-" + attempt + ".err");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED",
                "-Djava.library.path=" + System.getProperty("java.library.path", ""),
                "-cp", classpath, OverviewPrinter.class.getName(), root.toString())
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile())
                .start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Overview subprocess timed out");
        } finally {
            process.destroyForcibly();
        }
        if (process.exitValue() == MAP_OF_IN_ORDER) {
            return null;
        }
        assertEquals(0, process.exitValue(), Files.readString(errors));
        return Files.readString(output).trim();
    }
}
