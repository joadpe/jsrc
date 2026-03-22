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
import com.jsrc.app.command.architecture.ChecklistCommand;

class ChecklistCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void nullCheckTask_includesImportStep() throws Exception {
        var result = run("Svc.process", "add null check", """
                public class Svc {
                    public void process(String name) {}
                }
                """);
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) result.get("steps");
        assertTrue(steps.stream().anyMatch(s -> s.get("action").equals("add-import")),
                "Should include import step for null check");
    }

    @Test
    void renameTask_updatesCallers() throws Exception {
        var result = run("Svc.oldName", "rename method", """
                public class Svc {
                    public void oldName() {}
                }
                """, """
                public class Caller {
                    private Svc svc;
                    public void go() { svc.oldName(); }
                }
                """);
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) result.get("steps");
        assertTrue(steps.stream().anyMatch(s -> s.get("action").equals("update-caller")),
                "Rename should include update-caller step");
    }

    @Test
    void alwaysEndsWithTestStep() throws Exception {
        var result = run("Svc.run", "modify", """
                public class Svc {
                    public void run() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) result.get("steps");
        var last = steps.getLast();
        assertEquals("test", last.get("action"));
    }

    @Test
    void noCallers_minimalChecklist() throws Exception {
        var result = run("Svc.run", "modify", """
                public class Svc {
                    public void run() {}
                }
                """);
        assertEquals(0, ((Number) result.get("callerCount")).intValue());
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) result.get("steps");
        // At minimum: edit + test
        assertTrue(steps.size() >= 2);
    }

    @Test
    void methodNotFound_error() throws Exception {
        var result = run("Ghost.method", "modify", """
                public class Real {
                    public void other() {}
                }
                """);
        assertNotNull(result.get("error"));
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String method, String task, String... sources) throws Exception {
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

        new ChecklistCommand(method, task).execute(ctx);
        String json = baos.toString().trim();
        return (Map<String, Object>) JsonReader.parse(json);
    }
}
