package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.command.search.MethodSearchCommand;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class MethodSearchCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void compactSummaryCountsOverloadsPerClass() throws Exception {
        Path file = tempDir.resolve("Overloads.java");
        Files.writeString(file, overloads("Busy", 11) + overloads("Quiet", 20));

        var output = new ByteArrayOutputStream();
        var ctx = new CommandContext(List.of(file), tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(output)), null,
                new HybridJavaParser());
        assertEquals(31, new MethodSearchCommand("run").execute(ctx));

        Map<?, ?> compact = assertInstanceOf(Map.class, JsonReader.parse(output.toString().trim()));
        assertEquals(2L, compact.get("classesWithMethod"));
        assertEquals(List.of(Map.of("class", "Quiet", "overloads", 20L),
                Map.of("class", "Busy", "overloads", 11L)), compact.get("topClasses"));
    }

    @Test
    void compactSummaryListsClassBeforeOverloadsInEveryJvm() throws Exception {
        // Map.of iterates in an order salted once per JVM, so a single JVM may print
        // class first by chance. Search in a JVM where Map.of puts overloads first.
        Path file = tempDir.resolve("Overloads.java");
        Files.writeString(file, overloads("Busy", 31));
        String json = null;
        for (int attempt = 0; attempt < 20 && json == null; attempt++) {
            json = searchInFreshJvm(attempt, file);
        }
        assertNotNull(json, "No fresh JVM iterated Map.of with overloads first");

        Map<?, ?> compact = assertInstanceOf(Map.class, JsonReader.parse(json));
        List<?> topClasses = assertInstanceOf(List.class, compact.get("topClasses"));
        assertEquals(1, topClasses.size(), json);
        Map<?, ?> top = assertInstanceOf(Map.class, topClasses.getFirst());
        assertEquals(List.of("class", "overloads"), List.copyOf(top.keySet()));
        assertEquals(Map.of("class", "Busy", "overloads", 31L), top);
    }

    /** Prints the method search for run in one file; see {@link #MAP_OF_IN_ORDER}. */
    public static final class MethodSearchPrinter {
        private MethodSearchPrinter() {
        }

        public static void main(String[] args) {
            if (Map.of("class", "", "overloads", "").keySet().iterator().next().equals("class")) {
                System.exit(MAP_OF_IN_ORDER);
            }
            Path file = Path.of(args[0]);
            var ctx = new CommandContext(List.of(file), file.getParent().toString(), null,
                    new JsonFormatter(false, null, System.out), null, new HybridJavaParser());
            new MethodSearchCommand("run").execute(ctx);
            System.out.flush();
        }
    }

    /** Exit code of {@link MethodSearchPrinter} in a JVM where Map.of puts class first. */
    private static final int MAP_OF_IN_ORDER = 42;

    /** A class declaring this many overloads of run, one per line. */
    private static String overloads(String className, int count) {
        var source = new StringBuilder("class ").append(className).append(" {\n");
        for (int i = 0; i < count; i++) {
            source.append("    void run(")
                    .append(IntStream.range(0, i).mapToObj(n -> "int p" + n)
                            .collect(Collectors.joining(", ")))
                    .append(") {}\n");
        }
        return source.append("}\n").toString();
    }

    private String searchInFreshJvm(int attempt, Path file) throws Exception {
        Path output = tempDir.resolve("method-search-" + attempt + ".out");
        Path errors = tempDir.resolve("method-search-" + attempt + ".err");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED",
                "-Djava.library.path=" + System.getProperty("java.library.path", ""),
                "-cp", classpath, MethodSearchPrinter.class.getName(), file.toString())
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile())
                .start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Method search subprocess timed out");
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
