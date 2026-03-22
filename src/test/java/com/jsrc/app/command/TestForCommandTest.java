package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.command.callgraph.TestForCommand;

class TestForCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void depth0_onlyDirectCallAndNaming() throws Exception {
        var result = run("Calculator.add", 0,
                """
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                }
                """,
                """
                import org.junit.jupiter.api.Test;
                public class CalculatorTest {
                    @Test
                    public void testAdd() {
                        new Calculator().add(1, 2);
                    }
                }
                """,
                """
                public class Service {
                    private Calculator calc;
                    public int compute() { return calc.add(1, 2); }
                }
                """,
                """
                import org.junit.jupiter.api.Test;
                public class ServiceTest {
                    @Test
                    public void testCompute() {
                        new Service().compute();
                    }
                }
                """);

        @SuppressWarnings("unchecked")
        var tests = (List<Map<String, Object>>) result.get("tests");
        // Depth 0: only CalculatorTest (naming + direct call), NOT ServiceTest
        var classNames = tests.stream().map(t -> (String) t.get("class")).toList();
        assertTrue(classNames.stream().anyMatch(c -> c.contains("CalculatorTest")),
                "Should find CalculatorTest. Got: " + classNames);
        assertFalse(classNames.stream().anyMatch(c -> c.contains("ServiceTest")),
                "Should NOT find ServiceTest at depth 0. Got: " + classNames);
    }

    @Test
    void depth1_findsTransitiveCaller() throws Exception {
        var result = run("Calculator.add", 1,
                """
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                }
                """,
                """
                import org.junit.jupiter.api.Test;
                public class CalculatorTest {
                    @Test
                    public void testAdd() {
                        new Calculator().add(1, 2);
                    }
                }
                """,
                """
                public class Service {
                    private Calculator calc;
                    public int compute() { return calc.add(1, 2); }
                }
                """,
                """
                import org.junit.jupiter.api.Test;
                public class ServiceTest {
                    @Test
                    public void testCompute() {
                        new Service().compute();
                    }
                }
                """);

        @SuppressWarnings("unchecked")
        var tests = (List<Map<String, Object>>) result.get("tests");
        var classNames = tests.stream().map(t -> (String) t.get("class")).toList();
        assertTrue(classNames.stream().anyMatch(c -> c.contains("CalculatorTest")),
                "Should find CalculatorTest. Got: " + classNames);
        assertTrue(classNames.stream().anyMatch(c -> c.contains("ServiceTest")),
                "Should find ServiceTest at depth 1. Got: " + classNames);

        // ServiceTest should have lower confidence than CalculatorTest
        var serviceTest = tests.stream()
                .filter(t -> ((String) t.get("class")).contains("ServiceTest"))
                .findFirst().orElseThrow();
        assertEquals("medium", serviceTest.get("confidence"),
                "Transitive depth 1 should be medium confidence");
        assertEquals(1, ((Number) serviceTest.get("depth")).intValue(),
                "Should report depth=1");
    }

    @Test
    void depthFull_findsDeepTransitive() throws Exception {
        // Use field injection so call graph resolves type references
        var result = run("Repo.save", Integer.MAX_VALUE,
                """
                public class Repo {
                    public void save() {}
                }
                """,
                """
                public class Service {
                    private Repo repo;
                    public void process() { repo.save(); }
                }
                """,
                """
                public class Controller {
                    private Service svc;
                    public void handle() { svc.process(); }
                }
                """,
                """
                import org.junit.jupiter.api.Test;
                public class ControllerTest {
                    private Controller ctrl;
                    @Test
                    public void testHandle() {
                        ctrl.handle();
                    }
                }
                """);

        @SuppressWarnings("unchecked")
        var tests = (List<Map<String, Object>>) result.get("tests");
        var classNames = tests.stream().map(t -> (String) t.get("class")).toList();
        assertTrue(classNames.stream().anyMatch(c -> c.contains("ControllerTest")),
                "Full depth should find ControllerTest (2 hops). Got: " + classNames);

        var controllerTest = tests.stream()
                .filter(t -> ((String) t.get("class")).contains("ControllerTest"))
                .findFirst().orElseThrow();
        assertEquals("low", controllerTest.get("confidence"),
                "Depth 2+ should be low confidence");
        assertEquals(2, ((Number) controllerTest.get("depth")).intValue(),
                "Should report depth=2");
    }

    @Test
    void circularCalls_noInfiniteLoop() throws Exception {
        var result = run("A.ping", Integer.MAX_VALUE,
                """
                public class A {
                    private B b;
                    public void ping() { b.pong(); }
                }
                """,
                """
                public class B {
                    private A a;
                    public void pong() { a.ping(); }
                }
                """,
                """
                import org.junit.jupiter.api.Test;
                public class ATest {
                    @Test
                    public void testPing() { new A().ping(); }
                }
                """);

        @SuppressWarnings("unchecked")
        var tests = (List<Map<String, Object>>) result.get("tests");
        assertNotNull(tests, "Should handle circular calls without hanging");
    }

    @Test
    void defaultDepth_isOne() throws Exception {
        // When depth is not specified (use default constructor), should behave like depth=1
        var result = runDefault("Calculator.add",
                """
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                }
                """,
                """
                public class Service {
                    private Calculator calc;
                    public int compute() { return calc.add(1, 2); }
                }
                """,
                """
                import org.junit.jupiter.api.Test;
                public class ServiceTest {
                    @Test
                    public void testCompute() {
                        new Service().compute();
                    }
                }
                """);

        @SuppressWarnings("unchecked")
        var tests = (List<Map<String, Object>>) result.get("tests");
        var classNames = tests.stream().map(t -> (String) t.get("class")).toList();
        assertTrue(classNames.stream().anyMatch(c -> c.contains("ServiceTest")),
                "Default depth (1) should find ServiceTest. Got: " + classNames);
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String methodRef, int depth, String... sources) throws Exception {
        return executeCommand(methodRef, depth, sources);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runDefault(String methodRef, String... sources) throws Exception {
        return executeCommand(methodRef, -1, sources);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeCommand(String methodRef, int depth, String... sources) throws Exception {
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

        Command cmd = depth >= 0
                ? new TestForCommand(methodRef, depth)
                : new TestForCommand(methodRef);
        cmd.execute(ctx);

        String json = baos.toString().trim();
        if (json.isEmpty()) return Map.of();
        return (Map<String, Object>) JsonReader.parse(json);
    }
}
