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

import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.command.navigate.SummaryCommand;
import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class SummaryCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void indexedSummaryKeepsGenericMethodSignatures() throws Exception {
        Path file = tempDir.resolve("Repo.java");
        Files.writeString(file, """
                package app;
                public class Repo {
                    public <T> T find(Class<T> type, long id) { return null; }
                    public String name() { return "repo"; }
                }
                """);
        List<Path> files = List.of(file);
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        var graph = new CallGraphBuilder();
        graph.loadFromIndex(index.getEntries());
        index.saveWithGraph(tempDir, graph.toCallGraph());
        IndexedCodebase indexed = IndexedCodebase.tryLoad(tempDir, files);
        assertNotNull(indexed);

        List<String> expected = List.of(
                "public <T> T find(Class<T> type, long id)", "public String name()");
        assertEquals(expected, methodSignatures(files, null, parser));
        assertEquals(expected, methodSignatures(files, indexed, parser));
    }

    @SuppressWarnings("unchecked")
    private List<String> methodSignatures(
            List<Path> files, IndexedCodebase indexed, HybridJavaParser parser) {
        var out = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(out)), indexed, parser);
        new SummaryCommand("Repo").execute(ctx);
        var summary = (Map<String, Object>) JsonReader.parse(out.toString().trim());
        return ((List<Map<String, Object>>) summary.get("methods")).stream()
                .map(method -> (String) method.get("signature"))
                .toList();
    }
}
