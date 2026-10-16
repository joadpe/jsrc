package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.App;
import com.jsrc.app.index.CodebaseIndex;

/**
 * Call order of identical clean {@code jsrc index} runs. Each command runs in a fresh JVM
 * that first draws its own number of identity hashes: a single-threaded JVM otherwise hashes
 * the enum constants of every call alike in each run, which hides hash-order dependence.
 */
class CallOrderCliTest {

    private static final Pattern CALLEES = Pattern.compile("\"callees\":\\[([^]]*)]");

    @TempDir
    Path tempDir;

    @Test
    void cleanIndexRunsPublishOneGenerationWithCallsInDefinedOrder() throws Exception {
        Path project = gradleProject();
        byte[] published = cleanIndex(project, 0);

        String reported = run(project, 1_013, "--frozen-index", "--json", "callees", "B.name");
        assertEquals(List.of("?.name", "com.example.a.A.A"), callees(reported), reported);
        // Every call of render() is on one line, so its callees come in callee order.
        String render = run(project, 0, "--frozen-index", "--json", "callees", "Report.render");
        List<String> callees = callees(render);
        assertEquals(7, callees.size(), render);
        assertEquals(callees.stream().sorted().toList(), callees, render);

        assertEquals(-1, Arrays.mismatch(published, cleanIndex(project, 7_919)),
                "Published generations differ");
    }

    private static byte[] cleanIndex(Path project, int draws) throws Exception {
        deleteIndex(project);
        String output = run(project, draws, "index");
        assertTrue(output.contains("Indexed 3 files"), output);
        return Files.readAllBytes(CodebaseIndex.currentBinary(project));
    }

    /** Runs jsrc after drawing the given number of identity hashes. */
    public static final class PerturbedApp {
        static int drawn;

        private PerturbedApp() {
        }

        public static void main(String[] args) {
            for (int draw = Integer.parseInt(args[0]); draw > 0; draw--) {
                drawn += new Object().hashCode();
            }
            App.main(Arrays.copyOfRange(args, 1, args.length));
        }
    }

    private Path gradleProject() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(project.resolve("settings.gradle"), "include 'a', 'b'\n");
        Files.writeString(project.resolve("build.gradle"), "subprojects { apply plugin: 'java'; "
                + "sourceCompatibility = JavaVersion.VERSION_17 }\n");
        write(project.resolve("a/src/main/java/com/example/a/A.java"),
                "package com.example.a; public class A { public String name() { return \"a\"; } }");
        write(project.resolve("b/src/main/java/com/example/b/B.java"),
                "package com.example.b; import com.example.a.A; "
                        + "public class B { public String name() { return new A().name() + \"b\"; } }");
        write(project.resolve("b/src/main/java/com/example/b/Report.java"), """
                package com.example.b;

                import com.example.a.A;

                public class Report {
                    private final A a = new A();
                    private final B b = new B();

                    public String render() { return title() + a.name() + b.name() + String.valueOf(b.name().length()) + new B().name(); }

                    public String title() { return b.name().trim() + a.name().strip(); }
                }
                """);
        return project;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content + "\n");
    }

    private static void deleteIndex(Path project) throws IOException {
        Path index = project.resolve(".jsrc");
        if (!Files.exists(index)) return;
        try (Stream<Path> paths = Files.walk(index)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static List<String> callees(String output) {
        Matcher matcher = CALLEES.matcher(output);
        assertTrue(matcher.find(), output);
        return Arrays.stream(matcher.group(1).split(","))
                .map(callee -> callee.substring(1, callee.length() - 1))
                .toList();
    }

    private static String run(Path project, int draws, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(java, "--enable-native-access=ALL-UNNAMED",
                "-Djava.library.path=" + System.getProperty("java.library.path", ""),
                "-cp", classpath, PerturbedApp.class.getName(), Integer.toString(draws),
                "--dir", project.toString()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "jsrc subprocess timed out");
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            process.destroyForcibly();
        }
    }
}
