package com.jsrc.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.MethodReference;

class MethodTargetResolverTest {

    @TempDir
    Path tempDir;

    private CallGraphBuilder graph;

    @BeforeEach
    void setUp() throws Exception {
        // Build a codebase with overloads and multiple classes
        Path svc = writeFile("Service.java", """
                package com.app;
                public class Service {
                    public void process(String s) {}
                    public void process(Integer n) {}
                    public void process(String s, int n) {}
                    public void handle() {}
                }
                """);
        Path ctrl = writeFile("Controller.java", """
                package com.app;
                public class Controller {
                    private Service svc = new Service();
                    public void process(String s) {}
                    public void doA() { svc.process("x"); }
                    public void doB() { svc.process("x", 1); }
                }
                """);

        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, List.of(svc, ctrl), tempDir, List.of());

        graph = new CallGraphBuilder();
        graph.loadFromIndex(index.getEntries());
    }

    @Test
    @DisplayName("methodName only — returns all matches across classes")
    void methodNameOnly() {
        var ref = MethodResolver.parse("process");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        // Service.process(String), Service.process(Integer), Service.process(String,int),
        // Controller.process(String)
        assertTrue(result.targets().size() >= 4, "Should find all process methods: " + result.targets());
    }

    @Test
    @DisplayName("Class.method — filters by class")
    void classMethod() {
        var ref = MethodResolver.parse("Service.process");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        for (MethodReference t : result.targets()) {
            assertEquals("com.app.Service", t.className(), "All targets should be in Service");
        }
        assertEquals(3, result.targets().size(), "Should find 3 overloads in Service");
        assertTrue(result.isAmbiguous(), "Incomplete overload query must be explicit ambiguity");
    }

    @Test
    @DisplayName("Class.method(params) — filters by class and param count")
    void classMethodParams() {
        var ref = MethodResolver.parse("Service.process(String,int)");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        assertEquals(1, result.targets().size(), "Should find exactly 1 overload");
        assertEquals(2, result.targets().iterator().next().parameterCount());
    }

    @Test
    @DisplayName("Class.method(params) distinguishes overloads with equal arity")
    void classMethodParamsWithEqualArity() {
        var ref = MethodResolver.parse("Service.process(String)");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        assertEquals(1, result.targets().size(), "Should resolve the String overload only");
        assertEquals(List.of("String"), result.targets().iterator().next().parameterTypes());
    }

    @Test
    @DisplayName("Qualified class name distinguishes homonymous classes")
    void qualifiedClassNameDistinguishesHomonyms() throws Exception {
        Path sales = writeFile("sales/Service.java", """
                package sales;
                public class Service {
                    public void process(String value) {}
                }
                """);
        Path support = writeFile("support/Service.java", """
                package support;
                public class Service {
                    public void process(String value) {}
                }
                """);
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, List.of(sales, support), tempDir, List.of());
        var homonymGraph = new CallGraphBuilder();
        homonymGraph.loadFromIndex(index.getEntries());

        var result = MethodTargetResolver.resolve(
                MethodResolver.parse("sales.Service.process(String)"), homonymGraph);

        assertTrue(result.isResolved());
        assertEquals(1, result.targets().size());
        assertEquals("sales.Service", result.targets().iterator().next().className());

        var incomplete = MethodTargetResolver.resolve(
                MethodResolver.parse("Service.process(String)"), homonymGraph);
        assertEquals(2, incomplete.targets().size());
        assertTrue(incomplete.isAmbiguous(),
                "Simple class name must be ambiguous across homonymous classes");
    }

    @Test
    @DisplayName("Qualified name resolves exactly")
    void qualifiedName() {
        var ref = MethodResolver.parse("com.app.Service.process");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        for (MethodReference t : result.targets()) {
            assertEquals("com.app.Service", t.className());
        }
    }

    @Test
    @DisplayName("Ambiguous — multiple classes, no class specified")
    void ambiguous() {
        var ref = MethodResolver.parse("process");
        var result = MethodTargetResolver.resolve(ref, graph);

        // process exists in both Service and Controller — ambiguous
        assertTrue(result.isAmbiguous(), "Should be ambiguous: " + result.targets());
    }

    @Test
    @DisplayName("Not found — unknown method")
    void notFound() {
        var ref = MethodResolver.parse("nonExistent");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.targets().isEmpty());
        assertFalse(result.isAmbiguous());
    }

    @Test
    @DisplayName("Unique method — not ambiguous even without class")
    void uniqueMethod() {
        var ref = MethodResolver.parse("handle");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        assertFalse(result.isAmbiguous());
        assertEquals(1, result.targets().size());
    }

    private Path writeFile(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
