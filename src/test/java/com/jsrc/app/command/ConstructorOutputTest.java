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
import com.jsrc.app.command.navigate.AnnotationsCommand;
import com.jsrc.app.command.navigate.SummaryCommand;
import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

/** Commands show constructors as declared, with and without an index. */
class ConstructorOutputTest {

    @TempDir
    Path tempDir;

    @Test
    void summaryShowsDeclaredConstructorModifiersAndTypeParameters() throws Exception {
        List<Path> files = List.of(write("Box.java", """
                package app;
                import java.io.IOException;
                public class Box {
                    private Box(Object value) {}
                    public <T> Box(T value, Class<T> type) throws IOException {}
                    Box() {}
                }
                """));

        List<String> expected = List.of(
                "private Box(Object value)",
                "public <T> Box(T value, Class<T> type) throws IOException",
                "Box()");
        assertEquals(expected, signatures(run(new SummaryCommand("Box"), files, null)));
        assertEquals(expected, signatures(run(new SummaryCommand("Box"), files, index(files))));
    }

    @Test
    void annotationsFindConstructorsWithAndWithoutIndex() throws Exception {
        List<Path> files = List.of(write("Injected.java", """
                package app;
                public class Injected {
                    @Deprecated
                    Injected(String name) {}
                    @Deprecated
                    public void old() {}
                    enum Mode { ON; @Deprecated Mode() {} }
                    record Point(int x) { @Deprecated Point(int x) { this.x = x; } }
                }
                """));

        // Records' constructors are not reported as methods, so neither mode lists Point
        List<String> expected = List.of(
                "method Injected$Mode.Mode", "method Injected.Injected", "method Injected.old");
        assertEquals(expected, matches(run(new AnnotationsCommand("Deprecated"), files, null)));
        assertEquals(expected,
                matches(run(new AnnotationsCommand("Deprecated"), files, index(files))));
    }

    private Path write(String name, String source) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, source);
        return file;
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
    private static List<String> signatures(Object summary) {
        return ((List<Map<String, Object>>) ((Map<String, Object>) summary).get("methods"))
                .stream()
                .map(method -> (String) method.get("signature"))
                .toList();
    }

    /** "type Class.name" per match, sorted: the two modes list files and classes differently. */
    @SuppressWarnings("unchecked")
    private static List<String> matches(Object annotations) {
        return ((List<Map<String, Object>>) annotations).stream()
                .map(match -> match.get("type") + " " + match.get("class") + "." + match.get("name"))
                .sorted()
                .toList();
    }
}
