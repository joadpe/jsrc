package com.jsrc.app.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.config.ArchitectureConfig;
import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.parser.TreeSitterLanguageFactory;
import com.jsrc.app.util.Hashing;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Parser;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceCompatibilityScannerTest {

    @TempDir
    Path root;

    @Test
    void taskThatOverflowsAWorkerStackRunsAgainOnTheCallingThread() {
        var attempts = new java.util.concurrent.ConcurrentHashMap<
                Integer, java.util.concurrent.atomic.AtomicInteger>();
        List<Integer> items = java.util.stream.IntStream.range(0, 64).boxed().toList();

        List<Integer> results = SourceCompatibilityScanner.parallelRetryingOnCaller(items, item -> {
            int attempt = attempts.computeIfAbsent(
                    item, ignored -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet();
            if (item % 10 == 3 && attempt == 1) {
                throw new StackOverflowError("simulated deeply nested source");
            }
            return item * 2;
        });

        assertEquals(items.stream().map(item -> item * 2).toList(), results);
        assertEquals(2, attempts.get(13).get());
        assertEquals(1, attempts.get(14).get());
    }

    @Test
    void acceptsWindows1252SourceAndHashesItsBytes() throws Exception {
        Path source = root.resolve("Facturacion.java");
        byte[] bytes = """
                /** Facturación del año. */
                class Facturacion { String estado = "señal"; }
                """.getBytes(Charset.forName("windows-1252"));
        Files.write(source, bytes);
        var config = new ProjectConfig(List.of(), List.of(), "8", ArchitectureConfig.empty());

        var result = new SourceCompatibilityScanner().scan(List.of(source), null, config);

        assertEquals(List.of(source), result.files());
        assertEquals(List.of(), result.diagnostics());
        assertEquals(Hashing.sha256(bytes), result.acceptedHashes().get(source));
    }

    @Test
    void rootErrorFlagCoversNestedErrorAndMissingNodes() {
        try (var parser = new Parser(TreeSitterLanguageFactory.getLanguage("java"))) {
            try (var valid = parser.parse("class A { void m() { int x = 1; } }").orElseThrow()) {
                assertFalse(SourceCompatibilityScanner.hasSyntaxErrors(valid));
            }
            try (var missing = parser.parse("class A { void m() { int x = 1 } }").orElseThrow()) {
                assertTrue(contains(missing.getRootNode(), Node::isMissing));
                assertTrue(SourceCompatibilityScanner.hasSyntaxErrors(missing));
            }
            try (var error = parser.parse("class A { void m() { int x = ; } }").orElseThrow()) {
                assertTrue(contains(error.getRootNode(), Node::isError));
                assertTrue(SourceCompatibilityScanner.hasSyntaxErrors(error));
            }
        }
    }

    private static boolean contains(Node node, java.util.function.Predicate<Node> predicate) {
        if (predicate.test(node)) {
            return true;
        }
        for (Node child : node.getChildren()) {
            if (contains(child, predicate)) {
                return true;
            }
        }
        return false;
    }
}
