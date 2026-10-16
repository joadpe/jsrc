package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.command.search.FindCommand;

class FindCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void expandsSynonyms() throws Exception {
        var result = run("database errors",
                "public class DatabaseExceptionHandler {\n    public void handle() {}\n}",
                "public class OrderService {\n    public void process() {}\n}");
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) result.get("matches");
        assertTrue(matches.stream().anyMatch(m -> m.get("name").toString().contains("DatabaseExceptionHandler")),
                "Should find via synonym expansion. Got: " + matches);
    }

    @Test
    void literalFallbackForUnknownWords() throws Exception {
        var result = run("OrderService",
                "public class OrderService {\n    public void process() {}\n}");
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) result.get("matches");
        assertFalse(matches.isEmpty(), "Literal search should work");
    }

    @Test
    void filtersStopWords() throws Exception {
        var result = run("get the database errors",
                "public class DbErrorHandler {\n    public void handle() {}\n}");
        @SuppressWarnings("unchecked")
        var expanded = (List<String>) result.get("expanded");
        assertTrue(expanded.stream().noneMatch(e -> e.startsWith("the→")),
                "Should filter 'the'");
    }

    @Test
    void singleWord_works() throws Exception {
        var result = run("cache",
                "public class CacheManager {\n    public void evict() {}\n}");
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) result.get("matches");
        assertFalse(matches.isEmpty());
    }

    @Test
    void noMatches_emptyList() throws Exception {
        var result = run("quantum computing",
                "public class OrderService {\n    public void process() {}\n}");
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) result.get("matches");
        assertTrue(matches.isEmpty());
    }

    @Test
    void expandsAConceptNameToItsConcept() throws Exception {
        // config is not in its own list, nor in any other.
        var result = run("config",
                "public class AppSettings {\n    public void load() {}\n}");
        assertEquals(List.of("config→[configuration, properties, settings]"),
                result.get("expanded"));
    }

    @Test
    void expandsWordsOfSeveralConceptsAlikeInEveryJvm() throws Exception {
        // Map.ofEntries iterates in an order salted once per JVM, so a single JVM may expand
        // these words as expected by chance. Search in a JVM where a Map.ofEntries dictionary,
        // declared in the order of 2.6.1 or in the current one, meets handle before error or
        // before message.
        Files.writeString(tempDir.resolve("FaultBarrier.java"),
                "class FaultBarrier { void fallback() {} }\n");
        Files.writeString(tempDir.resolve("InterceptChain.java"),
                "class InterceptChain { void process() {} }\n");
        Files.writeString(tempDir.resolve("TicketQueue.java"),
                "class TicketQueue { void publish() {} }\n");
        List<String> results = null;
        for (int attempt = 0; attempt < 30 && results == null; attempt++) {
            results = findInFreshJvm(attempt, "handler", "catch", "listener", "intercept",
                    "handlers", "catches", "listeners", "catching");
        }
        assertNotNull(results,
                "No fresh JVM iterated Map.ofEntries with handle before error or message");

        assertFound(results.get(0), "handler→[error, exception, failureanalyzer]", "FaultBarrier");
        assertFound(results.get(1), "catch→[error, exception, failureanalyzer]", "FaultBarrier");
        assertFound(results.get(2), "listener→[message, queue, kafka]", "TicketQueue");
        assertFound(results.get(3), "intercept→[handle, handler, process]", "InterceptChain");
        assertFound(results.get(4), "handlers→[error, exception, failureanalyzer]",
                "FaultBarrier");
        assertFound(results.get(5), "catches→[error, exception, failureanalyzer]", "FaultBarrier");
        assertFound(results.get(6), "listeners→[message, queue, kafka]", "TicketQueue");
        assertFound(results.get(7), "catching→[error, exception, failureanalyzer]", "FaultBarrier");
    }

    /** Prints one find result per query over a directory; see {@link #MAP_OF_AGREES}. */
    public static final class FindPrinter {
        /** The concepts of FindCommand in the order 2.6.1 declared them. */
        private static final List<String> CONCEPTS_261 = List.of("database", "error", "handle",
                "auth", "http", "config", "test", "cache", "message", "log", "serial", "async",
                "valid", "schedule", "file", "ssl");
        /** The concepts in the order FindCommand declares them now. */
        private static final List<String> CONCEPTS = List.of("database", "error", "auth", "http",
                "config", "test", "cache", "message", "log", "serial", "async", "valid",
                "schedule", "file", "ssl", "handle");

        private FindPrinter() {
        }

        public static void main(String[] args) throws Exception {
            if (!handleComesFirst(CONCEPTS_261) || !handleComesFirst(CONCEPTS)) {
                System.exit(MAP_OF_AGREES);
            }
            Path root = Path.of(args[0]);
            List<Path> files;
            try (var listing = Files.list(root)) {
                files = listing.filter(file -> file.toString().endsWith(".java")).sorted().toList();
            }
            for (String query : List.of(args).subList(1, args.length)) {
                var ctx = new CommandContext(files, root.toString(), null,
                        new JsonFormatter(false, null, System.out), null, new HybridJavaParser());
                new FindCommand(query).execute(ctx);
            }
            System.out.flush();
        }

        /**
         * Whether a Map.ofEntries of these concepts meets handle before error or before message
         * in this JVM. Map.ofEntries places keys by hash code and insertion order, so it
         * iterates like a Map.ofEntries dictionary declared in the same order.
         */
        @SuppressWarnings("unchecked")
        private static boolean handleComesFirst(List<String> concepts) {
            List<String> order = List.copyOf(Map.ofEntries(concepts.stream()
                    .map(concept -> Map.entry(concept, concept))
                    .toArray(Map.Entry[]::new)).keySet());
            int handle = order.indexOf("handle");
            return handle < order.indexOf("error") || handle < order.indexOf("message");
        }
    }

    /**
     * Exit code of {@link FindPrinter} in a JVM where a Map.ofEntries dictionary, in one of the
     * two orders, meets error and message before handle, and so would expand handler, catch
     * and listener as expected by chance.
     */
    private static final int MAP_OF_AGREES = 42;

    // --- helpers ---

    /** Returns one find JSON per query, or null if that JVM's Map.ofEntries agreed by chance. */
    private List<String> findInFreshJvm(int attempt, String... queries) throws Exception {
        Path output = tempDir.resolve("find-" + attempt + ".out");
        Path errors = tempDir.resolve("find-" + attempt + ".err");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(java, "--enable-native-access=ALL-UNNAMED",
                "-Djava.library.path=" + System.getProperty("java.library.path", ""),
                "-cp", classpath, FindPrinter.class.getName(), tempDir.toString()));
        command.addAll(List.of(queries));
        Process process = new ProcessBuilder(command)
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile())
                .start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Find subprocess timed out");
        } finally {
            process.destroyForcibly();
        }
        if (process.exitValue() == MAP_OF_AGREES) {
            return null;
        }
        assertEquals(0, process.exitValue(), Files.readString(errors));
        List<String> results = Files.readAllLines(output).stream()
                .filter(line -> !line.isBlank())
                .toList();
        assertEquals(queries.length, results.size(), String.join("\n", results));
        return results;
    }

    private static void assertFound(String json, String expansion, String... classes) {
        Map<?, ?> result = assertInstanceOf(Map.class, JsonReader.parse(json));
        assertEquals(List.of(expansion), result.get("expanded"), json);
        List<?> matches = assertInstanceOf(List.class, result.get("matches"));
        assertEquals(List.of(classes),
                matches.stream().map(match -> ((Map<?, ?>) match).get("name")).toList(), json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String query, String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i];
            String className = "Class" + i;
            int idx = src.indexOf("class ");
            if (idx >= 0) className = src.substring(idx + 6).trim().split("[\\s{<]")[0];
            Path file = tempDir.resolve(className + ".java");
            Files.writeString(file, src);
            files.add(file);
        }
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);
        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(baos)), indexed, parser);
        new FindCommand(query).execute(ctx);
        return (Map<String, Object>) JsonReader.parse(baos.toString().trim());
    }
}
