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

class MapCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void rankedByCallers() throws Exception {
        var result = run(2000,
                "public class Popular {\n    public void api() {}\n}",
                "public class Caller1 {\n    private Popular p;\n    public void go() { p.api(); }\n}",
                "public class Caller2 {\n    private Popular p;\n    public void run() { p.api(); }\n}",
                "public class Lonely {\n    public void alone() {}\n}");
        @SuppressWarnings("unchecked")
        var map = (List<Map<String, Object>>) result.get("map");
        assertFalse(map.isEmpty());
        // Popular should be first (most callers)
        assertEquals("Popular", map.getFirst().get("class"));
    }

    @Test
    void respectsBudget() throws Exception {
        // Create many classes with enough methods to exceed budget
        String[] sources = new String[30];
        for (int i = 0; i < 30; i++) {
            StringBuilder sb = new StringBuilder("public class BigClass" + i + " {\n");
            for (int j = 0; j < 10; j++) {
                sb.append("    public void method").append(j).append("LongNameForBudget(String param").append(j).append(") {}\n");
            }
            sb.append("}");
            sources[i] = sb.toString();
        }
        var result = run(100, sources); // very small budget
        @SuppressWarnings("unchecked")
        var map = (List<Map<String, Object>>) result.get("map");
        int included = ((Number) result.get("included")).intValue();
        int total = ((Number) result.get("totalClasses")).intValue();
        assertTrue(included < total, "Should truncate at budget. Included=" + included + " total=" + total);
    }

    @Test
    void atLeastOneClass() throws Exception {
        var result = run(1, // tiny budget
                "public class Only {\n    public void run() {}\n}");
        @SuppressWarnings("unchecked")
        var map = (List<Map<String, Object>>) result.get("map");
        assertTrue(map.size() >= 1, "Should include at least 1 class even with tiny budget");
    }

    @Test
    void singleClass_noTruncation() throws Exception {
        var result = run(5000,
                "public class Single {\n    public void run() {}\n}");
        @SuppressWarnings("unchecked")
        var map = (List<Map<String, Object>>) result.get("map");
        assertEquals(1, map.size());
        assertEquals(1, ((Number) result.get("included")).intValue());
        assertEquals(1, ((Number) result.get("totalClasses")).intValue());
    }

    @Test
    void includesMethodSignatures() throws Exception {
        var result = run(2000,
                "public class Svc {\n    public String getName() { return \"\"; }\n    public void process(String id) {}\n}");
        @SuppressWarnings("unchecked")
        var map = (List<Map<String, Object>>) result.get("map");
        @SuppressWarnings("unchecked")
        var methods = (List<String>) map.getFirst().get("methods");
        assertNotNull(methods);
        assertFalse(methods.isEmpty());
    }

    @Test
    void packageAbbreviated() throws Exception {
        var result = run(2000,
                "package org.springframework.boot.context;\npublic class Svc {\n    public void run() {}\n}");
        @SuppressWarnings("unchecked")
        var map = (List<Map<String, Object>>) result.get("map");
        String pkg = (String) map.getFirst().get("pkg");
        assertTrue(pkg.length() < "org.springframework.boot.context".length(),
                "Package should be abbreviated. Got: " + pkg);
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(int budget, String... sources) throws Exception {
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
        new MapCommand(budget).execute(ctx);
        return (Map<String, Object>) JsonReader.parse(baos.toString().trim());
    }
}
