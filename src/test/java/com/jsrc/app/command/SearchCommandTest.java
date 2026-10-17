package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.command.search.SearchCommand;

class SearchCommandTest {

    @TempDir
    Path tempDir;

    private HybridJavaParser parser;

    @BeforeEach
    void setUp() {
        parser = new HybridJavaParser();
    }

    private List<Object> executeSearch(String pattern, String sourceCode) throws Exception {
        Path file = tempDir.resolve("Test.java");
        Files.writeString(file, sourceCode);

        var out = new ByteArrayOutputStream();
        var formatter = new JsonFormatter(false, null, new PrintStream(out));
        var ctx = new CommandContext(
                List.of(file), tempDir.toString(), null,
                formatter, null, parser);

        new SearchCommand(pattern).execute(ctx);

        String json = out.toString().trim();
        if (json.isEmpty()) return List.of();
        @SuppressWarnings("unchecked")
        List<Object> parsed = (List<Object>) JsonReader.parse(json);
        return parsed;
    }

    @Test
    void compactAndFullModesPreserveTheirResultShapes() throws Exception {
        Path file = tempDir.resolve("Test.java");
        Files.writeString(file, "class Test {\n" + " // TODO\n".repeat(31) + "}\n");

        var compactOutput = new ByteArrayOutputStream();
        var compactContext = new CommandContext(List.of(file), tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(compactOutput)), null, parser);
        assertEquals(31, new SearchCommand("TODO").execute(compactContext));
        @SuppressWarnings("unchecked")
        var compact = (Map<String, Object>) JsonReader.parse(compactOutput.toString().trim());
        assertEquals(31, ((Number) compact.get("total")).intValue());
        assertEquals(30, ((List<?>) compact.get("matches")).size());
        assertEquals(true, compact.get("truncated"));

        var fullOutput = new ByteArrayOutputStream();
        var fullContext = new CommandContext(List.of(file), tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(fullOutput)), null, parser,
                false, null, true);
        assertEquals(31, new SearchCommand("TODO").execute(fullContext));
        @SuppressWarnings("unchecked")
        var full = (List<Object>) JsonReader.parse(fullOutput.toString().trim());
        assertEquals(31, full.size());
    }

    @Test
    @DisplayName("Match in code is not marked as comment")
    void matchInCode() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    public void doWork() {
                        System.out.println("hello");
                    }
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(false, match.get("inComment"));
    }

    @Test
    @DisplayName("Match in // comment is marked as comment")
    void matchInLineComment() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    // doWork is deprecated
                    public void other() {}
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(true, match.get("inComment"));
    }

    @Test
    @DisplayName("Match in /* */ block comment is marked as comment")
    void matchInBlockComment() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    /*
                     * doWork was removed
                     */
                    public void other() {}
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(true, match.get("inComment"));
    }

    @Test
    @DisplayName("Match in /** */ javadoc is marked as comment")
    void matchInJavadoc() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    /**
                     * Calls doWork internally.
                     */
                    public void other() {}
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(true, match.get("inComment"));
    }

    @Test
    @DisplayName("Match in string literal is not marked as comment")
    void matchInStringLiteral() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    public void other() {
                        String s = "doWork";
                    }
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(false, match.get("inComment"));
    }

    @Test
    @DisplayName("Match between */ and /* on same line is not comment")
    void matchBetweenBlockComments() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    /* first */ void doWork() {} /* second
                     */
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(false, match.get("inComment"));
    }

    @Test
    @DisplayName("Match after */ followed by /* on same line is in comment")
    void matchInSecondBlockComment() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    /* first */ int x; /* doWork
                     */
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(true, match.get("inComment"));
    }

    @Test
    @DisplayName("Match after block comment ends is not marked as comment")
    void matchAfterBlockComment() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    /* comment */
                    public void doWork() {}
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(false, match.get("inComment"));
    }

    @Test
    void compactSummaryCountsMatchesPerClass() throws Exception {
        Path file = tempDir.resolve("Test.java");
        Files.writeString(file, "// TODO outside any class\n"
                + "class Busy {\n" + " // TODO\n".repeat(11) + "}\n"
                + "class Quiet {\n" + " // TODO\n".repeat(20) + "}\n");

        var output = new ByteArrayOutputStream();
        var ctx = new CommandContext(List.of(file), tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(output)), null, parser);
        assertEquals(32, new SearchCommand("TODO").execute(ctx));

        Map<?, ?> compact = assertInstanceOf(Map.class, JsonReader.parse(output.toString().trim()));
        assertEquals(2L, compact.get("classesMentioned"));
        assertEquals(List.of(Map.of("class", "Quiet", "count", 20L),
                Map.of("class", "Busy", "count", 11L)), compact.get("topClasses"));
    }

    @Test
    void compactSummaryListsClassBeforeCountInEveryJvm() throws Exception {
        // Map.of iterates in an order salted once per JVM, so a single JVM may print
        // class first by chance. Search in a JVM where Map.of puts count first.
        Path file = tempDir.resolve("Test.java");
        Files.writeString(file, "class Busy {\n" + " // TODO\n".repeat(31) + "}\n");
        String json = null;
        for (int attempt = 0; attempt < 20 && json == null; attempt++) {
            json = searchInFreshJvm(attempt, file);
        }
        assertNotNull(json, "No fresh JVM iterated Map.of with count first");

        Map<?, ?> compact = assertInstanceOf(Map.class, JsonReader.parse(json));
        List<?> topClasses = assertInstanceOf(List.class, compact.get("topClasses"));
        assertEquals(1, topClasses.size(), json);
        Map<?, ?> top = assertInstanceOf(Map.class, topClasses.getFirst());
        assertEquals(List.of("class", "count"), List.copyOf(top.keySet()));
        assertEquals(Map.of("class", "Busy", "count", 31L), top);
    }

    /** Prints the search for TODO in one file; see {@link #MAP_OF_IN_ORDER}. */
    public static final class SearchPrinter {
        private SearchPrinter() {
        }

        public static void main(String[] args) {
            if (Map.of("class", "", "count", "").keySet().iterator().next().equals("class")) {
                System.exit(MAP_OF_IN_ORDER);
            }
            Path file = Path.of(args[0]);
            var ctx = new CommandContext(List.of(file), file.getParent().toString(), null,
                    new JsonFormatter(false, null, System.out), null, new HybridJavaParser());
            new SearchCommand("TODO").execute(ctx);
            System.out.flush();
        }
    }

    /** Exit code of {@link SearchPrinter} in a JVM where Map.of puts class first. */
    private static final int MAP_OF_IN_ORDER = 42;

    private String searchInFreshJvm(int attempt, Path file) throws Exception {
        Path output = tempDir.resolve("search-" + attempt + ".out");
        Path errors = tempDir.resolve("search-" + attempt + ".err");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED",
                "-Djava.library.path=" + System.getProperty("java.library.path", ""),
                "-cp", classpath, SearchPrinter.class.getName(), file.toString())
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile())
                .start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Search subprocess timed out");
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
