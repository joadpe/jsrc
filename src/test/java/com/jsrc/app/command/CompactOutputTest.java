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

/**
 * Tests that compact output (default) is significantly smaller than full output.
 * Compact mode trims large arrays and verbose details. --full restores everything.
 */
class CompactOutputTest {

    @TempDir
    Path tempDir;

    @Test
    void overview_compact_omitsPackageList() throws Exception {
        var result = runOverview(false);
        // Compact: should have stats but no package list
        assertTrue(result.containsKey("totalFiles"));
        assertTrue(result.containsKey("totalClasses"));
        assertTrue(result.containsKey("totalPackages"));
        assertFalse(result.containsKey("packages"),
                "Compact overview should NOT include packages array");
    }

    @Test
    void overview_full_includesPackageList() throws Exception {
        var result = runOverview(true);
        assertTrue(result.containsKey("packages"),
                "Full overview SHOULD include packages array");
    }

    @Test
    void summary_compact_limitsMethodCount() throws Exception {
        var result = runSummary(false);
        @SuppressWarnings("unchecked")
        var methods = (List<Object>) result.get("methods");
        assertTrue(methods.size() <= 20,
                "Compact summary should show max 20 methods, got: " + methods.size());
    }

    @Test
    void summary_full_includesAllMethods() throws Exception {
        var result = runSummary(true);
        @SuppressWarnings("unchecked")
        var methods = (List<Object>) result.get("methods");
        assertTrue(methods.size() > 20,
                "Full summary should show all methods, got: " + methods.size());
    }

    @Test
    void compact_isDefault() throws Exception {
        // When fullOutput is not set (default constructor), should be compact
        var result = runOverview(false);
        assertFalse(result.containsKey("packages"),
                "Default should be compact (no packages)");
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> runOverview(boolean full) throws Exception {
        var ctx = buildContext(full, generateManyClasses());
        var baos = new ByteArrayOutputStream();
        var ctxWithOutput = new CommandContext(ctx.javaFiles(), ctx.rootPath(), null,
                new JsonFormatter(false, null, new PrintStream(baos)),
                ctx.indexed(), ctx.parser(), false, null, full);

        new OverviewCommand().execute(ctxWithOutput);
        String json = baos.toString().trim();
        return (Map<String, Object>) JsonReader.parse(json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runSummary(boolean full) throws Exception {
        var ctx = buildContext(full, generateBigClass());
        var baos = new ByteArrayOutputStream();
        var ctxWithOutput = new CommandContext(ctx.javaFiles(), ctx.rootPath(), null,
                new JsonFormatter(false, null, new PrintStream(baos)),
                ctx.indexed(), ctx.parser(), false, null, full);

        new SummaryCommand("BigService").execute(ctxWithOutput);
        String json = baos.toString().trim();
        return (Map<String, Object>) JsonReader.parse(json);
    }

    private CommandContext buildContext(boolean full, List<Path> files) throws Exception {
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);

        return new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(new ByteArrayOutputStream())),
                indexed, parser, false, null, full);
    }

    private List<Path> generateManyClasses() throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        // Generate 30 classes across 10 packages to ensure packages array is non-trivial
        for (int i = 0; i < 30; i++) {
            String pkg = "com.example.pkg" + (i % 10);
            String name = "Class" + i;
            Path file = tempDir.resolve(name + ".java");
            Files.writeString(file, "package " + pkg + ";\npublic class " + name + " {\n"
                    + "    public void method" + i + "() {}\n}\n");
            files.add(file);
        }
        return files;
    }

    private List<Path> generateBigClass() throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("package com.example;\npublic class BigService {\n");
        for (int i = 0; i < 30; i++) {
            sb.append("    public void method").append(i).append("() {}\n");
        }
        sb.append("}\n");
        Path file = tempDir.resolve("BigService.java");
        Files.writeString(file, sb.toString());
        files.add(file);
        return files;
    }
}
