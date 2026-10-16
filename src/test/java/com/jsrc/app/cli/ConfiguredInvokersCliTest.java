package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexEntry;
import com.jsrc.app.model.InvocationKind;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CLI keeps reflective edges of configured invokers across every kind of command. */
class ConfiguredInvokersCliTest {

    @TempDir
    Path root;

    @Test
    void reflectiveCallerSurvivesUnchangedIndexMetaCommandsAndQueryRefresh() throws Exception {
        Files.writeString(root.resolve(".jsrc.yaml"), """
                architecture:
                  invokers:
                    - method: invoke
                      targetArg: 0
                      resolveClass: handler
                      callerSuffixes: [View]
                """);
        Files.writeString(root.resolve("OrderView.java"), """
                class OrderView {
                    void save() { invoke("store"); }
                    void invoke(String name) {}
                }
                """);
        Files.writeString(root.resolve("OrderHandler.java"),
                "class OrderHandler { void store() {} }");
        Path other = root.resolve("Other.java");
        Files.writeString(other, "class Other { void run() { System.gc(); } }");

        assertEquals(0, jsrc("index"));
        assertTrue(hasReflectiveStoreEdge(), "jsrc index extracts the reflective edge");
        String generation = currentGeneration();

        assertEquals(0, jsrc("index"));
        assertEquals(generation, currentGeneration(), "unchanged index is not republished");

        assertEquals(0, jsrc("describe"));
        assertEquals(0, jsrc("skill"));
        assertEquals(generation, currentGeneration(), "meta commands leave the index alone");
        assertTrue(hasReflectiveStoreEdge());

        Files.writeString(other, "class Other { void run() { System.runFinalization(); } }");
        assertEquals(0, jsrc("classes"));
        assertTrue(!generation.equals(currentGeneration()), "the query refreshed the index");
        assertTrue(hasReflectiveStoreEdge(), "the refresh keeps the reflective edge");
    }

    private int jsrc(String command) {
        var originalOut = System.out;
        var originalErr = System.err;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        System.setErr(new PrintStream(captured));
        try {
            return JsrcCliFactory.create().execute("--dir", root.toString(), command);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private String currentGeneration() throws Exception {
        return Files.readAllLines(root.resolve(".jsrc").resolve("current")).get(1);
    }

    private boolean hasReflectiveStoreEdge() throws Exception {
        List<IndexEntry> entries = CodebaseIndex.loadPublished(root);
        return entries.stream()
                .flatMap(entry -> entry.callEdges().stream())
                .anyMatch(edge -> edge.invocationKind() == InvocationKind.REFLECTIVE
                        && edge.calleeClass().equals("OrderHandler")
                        && edge.calleeMethod().equals("store"));
    }
}
