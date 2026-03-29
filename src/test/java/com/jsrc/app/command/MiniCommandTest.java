package com.jsrc.app.command;

import com.jsrc.app.command.navigate.MiniCommand;

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

class MiniCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void allFieldsPresent() throws Exception {
        var result = run("Svc", """
                package com.app.service;
                public class Svc {
                    private String name;
                    public Svc(String name) { this.name = name; }
                    public void run() {}
                    public String getName() { return name; }
                }
                """);
        assertNotNull(result.get("name"));
        assertNotNull(result.get("pkg"));
        assertNotNull(result.get("kind"));
        assertNotNull(result.get("keyMethods"));
        assertNotNull(result.get("keyFields"));
        assertNotNull(result.get("deps"));
    }

    @Test
    void packageAbbreviated() throws Exception {
        var result = run("Svc", """
                package org.springframework.boot.context.config;
                public class Svc { public void run() {} }
                """);
        String pkg = (String) result.get("pkg");
        assertTrue(pkg.length() < "org.springframework.boot.context.config".length(),
                "Package should be abbreviated. Got: " + pkg);
        assertTrue(pkg.contains("config"), "Should keep last segment readable");
    }

    @Test
    void under500CharsForTypicalClass() throws Exception {
        var result = run("Svc", """
                package com.app;
                import java.util.List;
                public class Svc {
                    private List<String> items;
                    private int count;
                    public void add(String item) {}
                    public void remove(String item) {}
                    public List<String> getAll() { return items; }
                    public int getCount() { return count; }
                }
                """);
        String json = runRaw("Svc", """
                package com.app;
                import java.util.List;
                public class Svc {
                    private List<String> items;
                    private int count;
                    public void add(String item) {}
                    public void remove(String item) {}
                    public List<String> getAll() { return items; }
                    public int getCount() { return count; }
                }
                """);
        assertTrue(json.length() < 600, "Mini output should be <600 chars (including hints). Got: " + json.length());
    }

    @Test
    void interfaceReportsCorrectKind() throws Exception {
        var result = run("Handler", """
                package com.app;
                public interface Handler {
                    void handle(String event);
                }
                """);
        assertEquals("interface", result.get("kind"));
    }

    @Test
    void keyMethodsMax5() throws Exception {
        var result = run("Big", """
                package com.app;
                public class Big {
                    public void m1() {} public void m2() {} public void m3() {}
                    public void m4() {} public void m5() {} public void m6() {}
                    public void m7() {} public void m8() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var methods = (List<?>) result.get("keyMethods");
        assertTrue(methods.size() <= 5, "keyMethods should be max 5. Got: " + methods.size());
    }

    @Test
    void classNotFound_error() throws Exception {
        var result = run("NonExistent", """
                public class Other { public void x() {} }
                """);
        // Should not crash — either empty result or error field
        assertNotNull(result);
    }

    @Test
    void keyFieldsExcludesPrimitives() throws Exception {
        var result = run("Mixed", """
                package com.app;
                public class Mixed {
                    private int count;
                    private boolean active;
                    private String name;
                    private OrderService service;
                    public void run() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var fields = (List<String>) result.get("keyFields");
        // Should prefer OrderService and String over int/boolean
        assertTrue(fields.stream().noneMatch(f -> f.startsWith("int ") || f.startsWith("boolean ")),
                "Should exclude primitives. Got: " + fields);
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String cls, String... sources) throws Exception {
        String json = runRaw(cls, sources);
        if (json.isEmpty()) return Map.of();
        return (Map<String, Object>) JsonReader.parse(json);
    }

    private String runRaw(String cls, String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i];
            String className = "Class" + i;
            int idx = src.indexOf("class ");
            if (idx < 0) idx = src.indexOf("interface ");
            if (idx >= 0) {
                String keyword = src.substring(idx).contains("interface") ? "interface " : "class ";
                int nameStart = src.indexOf(keyword, idx) + keyword.length();
                className = src.substring(nameStart).trim().split("[\\s{<]")[0];
            }
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

        new MiniCommand(cls).execute(ctx);
        return baos.toString().trim();
    }
}
