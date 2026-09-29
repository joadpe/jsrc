package com.jsrc.app.index;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexGraphReuseTest {

    @Test
    void reusesGraphOnlyWhenBodyEditPreservesResolvedEdges(@TempDir Path root) throws Exception {
        Path service = root.resolve("Service.java");
        Path client = root.resolve("Client.java");
        Files.writeString(service, "class Service { static int one() { return 1; } "
                + "static int two() { return 2; } }");
        Files.writeString(client, "class Client { int value() { return Service.one(); } }");
        runIndex(root, root.resolve("initial-trace.json"));
        CallGraph original = readGraph(root);

        Files.writeString(client, "class Client { int value() { return Service.one() + 1; } }");
        Path bodyTrace = root.resolve("body-trace.json");
        runIndex(root, bodyTrace);
        assertTrue(Files.readString(bodyTrace).contains("\"index.call_graph.reused\":1"));
        assertGraphEquals(original, readGraph(root));

        Files.writeString(client, "class Client { int value() { return Service.two() + 1; } }");
        Path callTrace = root.resolve("call-trace.json");
        runIndex(root, callTrace);
        assertFalse(Files.readString(callTrace).contains("\"index.call_graph.reused\":1"));
        Set<MethodCall> updated = edges(readGraph(root));
        assertNotEquals(edges(original), updated);

        var clean = new CodebaseIndex();
        clean.build(new HybridJavaParser(), List.of(service, client), root, List.of());
        var builder = new CallGraphBuilder();
        builder.loadFromIndex(clean.getEntries());
        assertGraphEquals(builder.toCallGraph(), readGraph(root));
    }

    @Test
    void automaticRefreshAlsoReusesUnchangedGraph(@TempDir Path root) throws Exception {
        Path service = root.resolve("Service.java");
        Path client = root.resolve("Client.java");
        Files.writeString(service, "class Service { static int one() { return 1; } "
                + "static int two() { return 2; } }");
        Files.writeString(client, "class Client { int value() { return Service.one(); } }");
        runIndex(root, root.resolve("initial-trace.json"));
        CallGraph original = readGraph(root);

        Files.writeString(client, "class Client { int value() { return Service.one() + 1; } }");
        Path bodyTrace = root.resolve("body-trace.json");
        runCommand(root, bodyTrace, "overview");
        assertTrue(Files.readString(bodyTrace).contains("\"index.call_graph.reused\":1"));
        assertGraphEquals(original, readGraph(root));

        Files.writeString(client, "class Client { int value() { return Service.two() + 1; } }");
        Path callTrace = root.resolve("call-trace.json");
        runCommand(root, callTrace, "overview");
        assertFalse(Files.readString(callTrace).contains("\"index.call_graph.reused\":1"));
        assertNotEquals(edges(original), edges(readGraph(root)));
    }

    private static CallGraph readGraph(Path root) throws Exception {
        return BinaryIndexV2Reader.read(CodebaseIndex.currentBinary(root)).callGraph();
    }

    private static void assertGraphEquals(CallGraph expected, CallGraph actual) {
        assertEquals(expected.getAllMethods(), actual.getAllMethods());
        assertEquals(expected.getAllCallerIndexKeys(), actual.getAllCallerIndexKeys());
        Set<MethodReference> methods =
                new java.util.HashSet<>(expected.getAllMethods());
        methods.addAll(expected.getAllCallerIndexKeys());
        for (var method : methods) {
            assertEquals(expected.getCalleesOf(method), actual.getCalleesOf(method),
                    "Outgoing calls of " + method);
            assertEquals(expected.getCallersOf(method), actual.getCallersOf(method),
                    "Incoming calls of " + method);
            assertEquals(expected.findMethodsByName(method.methodName()),
                    actual.findMethodsByName(method.methodName()),
                    "Registered methods named " + method.methodName());
        }
    }

    private static Set<MethodCall> edges(CallGraph graph) {
        return graph.getAllMethods().stream()
                .flatMap(method -> graph.getCalleesOf(method).stream())
                .collect(Collectors.toSet());
    }

    private static void runIndex(Path root, Path trace) throws Exception {
        runCommand(root, trace, "index");
    }

    private static void runCommand(Path root, Path trace, String command) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-Djsrc.perf.trace=" + trace,
                "-cp", classpath, "com.jsrc.app.App", "--dir", root.toString(), command)
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Index subprocess timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }
}
