package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.command.navigate.MiniCommand;
import com.jsrc.app.command.navigate.SummaryCommand;
import com.jsrc.app.command.quality.ApiCommand;
import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

/** Commands report the same class modifiers with and without an index. */
class ClassModifiersOutputTest {

    @TempDir
    Path tempDir;

    @Test
    void summaryOfAbstractClassIsTheSameWithAndWithoutIndex() throws Exception {
        List<Path> files = writeRepo();

        Map<String, Object> parsed = withoutFile(run(new SummaryCommand("Repo"), files, null));
        Map<String, Object> indexed = withoutFile(
                run(new SummaryCommand("Repo"), files, index(files)));

        assertEquals(List.of("public", "abstract"), parsed.get("modifiers"));
        assertEquals(true, parsed.get("isAbstract"));
        assertEquals(parsed, indexed);
    }

    @Test
    void miniAndApiAreTheSameWithAndWithoutIndex() throws Exception {
        List<Path> files = writeRepo();
        IndexedCodebase indexed = index(files);

        assertEquals("abstract class", map(run(new MiniCommand("Repo"), files, null)).get("kind"));
        assertEquals("abstract class",
                map(run(new MiniCommand("Repo"), files, indexed)).get("kind"));
        Map<String, Object> api = map(run(new ApiCommand(null), files, null));
        assertEquals(1L, ((Number) api.get("totalClasses")).longValue(), "Got: " + api);
        assertEquals(api, map(run(new ApiCommand(null), files, indexed)));
    }

    private List<Path> writeRepo() throws Exception {
        Path file = tempDir.resolve("Repo.java");
        Files.writeString(file, """
                package app;
                public abstract class Repo<E> {
                    public <T> T find(Class<T> type, long id) { return null; }
                    protected abstract E load(long id);
                    public final String name() { return "repo"; }
                }
                """);
        return List.of(file);
    }

    private IndexedCodebase index(List<Path> files) throws Exception {
        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), files, tempDir, List.of());
        var graph = new CallGraphBuilder();
        graph.loadFromIndex(index.getEntries());
        index.saveWithGraph(tempDir, graph.toCallGraph());
        IndexedCodebase indexed = IndexedCodebase.tryLoad(tempDir, files);
        assertNotNull(indexed);
        return indexed;
    }

    private Object run(Command command, List<Path> files, IndexedCodebase indexed) {
        var out = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(out)), indexed,
                new HybridJavaParser());
        command.execute(ctx);
        return JsonReader.parse(out.toString().trim());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object json) {
        return (Map<String, Object>) json;
    }

    /** The index reports the file path; the parse-backed summary leaves it blank. */
    private static Map<String, Object> withoutFile(Object summary) {
        Map<String, Object> copy = new LinkedHashMap<>(map(summary));
        copy.remove("file");
        return copy;
    }
}
