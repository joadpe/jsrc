package com.jsrc.app.contract;

import com.jsrc.app.command.navigate.AnnotationsCommand;

import com.jsrc.app.command.navigate.DepsCommand;

import com.jsrc.app.command.navigate.HierarchyCommand;

import com.jsrc.app.command.navigate.ReadCommand;

import com.jsrc.app.command.navigate.MiniCommand;

import com.jsrc.app.command.navigate.SummaryCommand;

import com.jsrc.app.command.navigate.ClassesCommand;

import com.jsrc.app.command.navigate.OverviewCommand;

import com.jsrc.app.command.*;
import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.output.JsonReader;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import com.jsrc.app.command.search.SearchCommand;
import com.jsrc.app.command.analysis.SmellsCommand;
import com.jsrc.app.command.analysis.ComplexityCommand;
import com.jsrc.app.command.analysis.LintCommand;
import com.jsrc.app.command.analysis.StyleCommand;
import com.jsrc.app.command.architecture.ValidateCommand;

/**
 * Contract tests: verify JSON output structure (keys + types) is stable.
 * Uses a fixture codebase to ensure deterministic results.
 *
 * <p>These tests detect breaking changes in jsrc's JSON API:
 * renamed fields, removed fields, changed types (string→array, etc.).</p>
 *
 * <p>When a test fails because of an intentional change, update the
 * expected keys/types in the test — this forces explicit acknowledgment
 * of API changes.</p>
 */
class JsonOutputContractTest {

    @TempDir
    static Path tempDir;
    static CommandContext ctx;

    @BeforeAll
    static void setup() throws Exception {
        // Copy fixture files to temp dir
        Path fixtureDir = Path.of("src/test/resources/contract-fixture");
        for (File f : fixtureDir.toFile().listFiles()) {
            Files.copy(f.toPath(), tempDir.resolve(f.getName()));
        }

        var parser = new HybridJavaParser();
        var files = new ArrayList<Path>();
        try (var stream = Files.walk(tempDir)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        var formatter = OutputFormatter.create(true, false, null);
        ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
    }

    /** Capture stdout from a command execution. */
    private String capture(Command cmd) {
        var captured = new ByteArrayOutputStream();
        var ps = new PrintStream(captured, true);
        // Create a fresh context with formatter pointing to our capture stream
        var formatter = OutputFormatter.create(true, false, null, ps);
        var captureCtx = new CommandContext(
                ctx.javaFiles(), ctx.rootPath(), ctx.config(),
                formatter, ctx.indexed(), ctx.parser());
        cmd.execute(captureCtx);
        ps.flush();
        return captured.toString().trim();
    }

    /** Parse JSON and return top-level keys with their types. */
    @SuppressWarnings("unchecked")
    private Map<String, String> keysAndTypes(String json) {
        Object parsed = JsonReader.parse(json);
        var result = new LinkedHashMap<String, String>();
        if (parsed instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                result.put(entry.getKey().toString(), typeOf(entry.getValue()));
            }
        } else if (parsed instanceof List<?>) {
            result.put("_root", "array");
        }
        return result;
    }

    private String typeOf(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "string";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof List<?>) return "array";
        if (value instanceof Map<?, ?>) return "object";
        return "unknown";
    }

    // ─── Contract tests ───

    @Test
    void overviewContract() {
        String json = capture(new OverviewCommand());
        var keys = keysAndTypes(json);
        assertContainsKey(keys, "totalFiles", "number");
        assertContainsKey(keys, "totalClasses", "number");
        assertContainsKey(keys, "totalInterfaces", "number");
        assertContainsKey(keys, "totalMethods", "number");
        assertContainsKey(keys, "totalPackages", "number");
    }

    @Test
    void summaryContract() {
        String json = capture(new SummaryCommand("OrderService"));
        var keys = keysAndTypes(json);
        assertContainsKey(keys, "name", "string");
        assertContainsKey(keys, "packageName", "string");
        assertContainsKey(keys, "file", "string");
        assertContainsKey(keys, "methods", "array");
        assertContainsKey(keys, "isInterface", "boolean");
    }

    @Test
    void miniContract() {
        String json = capture(new MiniCommand("OrderService"));
        var keys = keysAndTypes(json);
        assertContainsKey(keys, "name", "string");
        assertContainsKey(keys, "methods", "number");
        assertContainsKey(keys, "keyMethods", "array");
    }

    @Test
    void hierarchyContract() {
        String json = capture(new HierarchyCommand("OrderRepository"));
        var keys = keysAndTypes(json);
        assertContainsKey(keys, "target", "string");
    }

    @Test
    void depsContract() {
        String json = capture(new DepsCommand("OrderService"));
        var keys = keysAndTypes(json);
        assertContainsKey(keys, "class", "string");
    }

    @Test
    void readContract() {
        String json = capture(new ReadCommand("OrderService"));
        var keys = keysAndTypes(json);
        assertContainsKey(keys, "class", "string");
        assertContainsKey(keys, "content", "string");
        assertContainsKey(keys, "file", "string");
    }

    @Test
    void classesContract() {
        String json = capture(new ClassesCommand(null));
        var keys = keysAndTypes(json);
        // classes returns an object with total + classes array
        assertTrue(keys.containsKey("total") || keys.containsKey("classes") || keys.containsKey("_root"),
                "classes output should have 'total', 'classes', or be an array. Got: " + keys);
    }

    @Test
    void smellsContract() {
        String json = capture(new SmellsCommand("OrderService"));
        var keys = keysAndTypes(json);
        assertContainsKey(keys, "file", "string");
        assertContainsKey(keys, "findings", "array");
    }

    @Test
    void annotationsContract() {
        // Use an annotation that exists in fixtures
        String json = capture(new AnnotationsCommand("Override"));
        assertNotNull(json);
        assertFalse(json.isEmpty(), "annotations output should not be empty");
        // Output can be object {annotation, total, matches} or array — verify it's valid JSON
        Object parsed = JsonReader.parse(json);
        assertNotNull(parsed, "annotations output should be valid JSON");
    }

    @Test
    void styleContract() {
        String json = capture(new StyleCommand());
        var keys = keysAndTypes(json);
        assertContainsKey(keys, "java", "string");
    }

    @Test
    void searchContract() {
        String json = capture(new SearchCommand("TODO"));
        // search returns an array or object with matches
        assertNotNull(json);
        assertFalse(json.isEmpty());
    }

    @Test
    void lintContract() {
        String json = capture(new LintCommand("OrderService"));
        var keys = keysAndTypes(json);
        assertContainsKey(keys, "class", "string");
        assertContainsKey(keys, "diagnostics", "array");
    }

    @Test
    void complexityContract() {
        String json = capture(new ComplexityCommand("OrderService"));
        var keys = keysAndTypes(json);
        assertContainsKey(keys, "target", "string");
    }

    @Test
    void validateContract() {
        String json = capture(new ValidateCommand("OrderService.createOrder"));
        var keys = keysAndTypes(json);
        assertTrue(keys.containsKey("valid") || keys.containsKey("class") || keys.containsKey("error"),
                "validate should have 'valid', 'class', or 'error'. Got: " + keys);
    }

    // ─── Helper ───

    private void assertContainsKey(Map<String, String> keys, String key, String expectedType) {
        assertTrue(keys.containsKey(key),
                "Missing key '" + key + "'. Available keys: " + keys.keySet());
        assertEquals(expectedType, keys.get(key),
                "Key '" + key + "' has wrong type. Expected " + expectedType + " but got " + keys.get(key));
    }
}
