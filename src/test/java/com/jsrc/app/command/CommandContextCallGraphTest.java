package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;

/**
 * Tests for lazy CallGraph provider in CommandContext.
 */
class CommandContextCallGraphTest {

    @TempDir
    Path tempDir;

    @Test
    void callGraph_returnsSameInstanceOnRepeatedCalls() throws Exception {
        Path file = tempDir.resolve("Svc.java");
        Files.writeString(file, """
                public class Svc {
                    public void doWork() {}
                }
                """);

        var ctx = new CommandContext(
                List.of(file), tempDir.toString(), null,
                new JsonFormatter(), null, new HybridJavaParser());

        CallGraph g1 = ctx.callGraph();
        CallGraph g2 = ctx.callGraph();
        assertNotNull(g1);
        assertSame(g1, g2, "callGraph() must return the same instance (lazy singleton)");
    }

    @Test
    void callGraph_usesIndexWhenAvailable() throws Exception {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, """
                public class A {
                    public void foo() { bar(); }
                    public void bar() {}
                }
                """);
        var files = List.of(file);
        var parser = new HybridJavaParser();

        // Build index with call edges
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);

        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(), indexed, parser);

        CallGraph graph = ctx.callGraph();
        assertNotNull(graph);
        // Should have methods loaded from index
        assertFalse(graph.getAllMethods().isEmpty(), "Graph should have methods from index");
    }

    @Test
    void callGraph_buildsFromFilesWhenNoIndex() throws Exception {
        Path file = tempDir.resolve("B.java");
        Files.writeString(file, """
                public class B {
                    public void x() { y(); }
                    public void y() {}
                }
                """);

        var ctx = new CommandContext(
                List.of(file), tempDir.toString(), null,
                new JsonFormatter(), null, new HybridJavaParser());

        CallGraph graph = ctx.callGraph();
        assertNotNull(graph);
        assertFalse(graph.getAllMethods().isEmpty(), "Graph should have methods from file parsing");
    }
}
