package com.jsrc.app.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.command.CommandContext;
import com.jsrc.app.command.navigate.ReadCommand;
import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

/**
 * Contract tests for issue #36: read must resolve FQCN via index/ClassResolver
 * with fast fail (no 15s full-tree scan).
 * <p>
 * Acceptance cases:
 * B1: read SimpleName still works (regression)
 * B2: read FQCN for indexed class succeeds
 * B3: FQCN resolution must use index/ClassResolver path, not full-tree scan
 * B4: missing FQCN fails fast with honest not-found
 */
class ReadCommandFqcnContractTest {

    @TempDir
    Path tempDir;

    private HybridJavaParser parser;

    @BeforeEach
    void setUp() {
        parser = new HybridJavaParser();
    }

    @Test
    @DisplayName("B1: read SimpleName still works (regression)")
    void readSimpleNameRegression() throws Exception {
        Path file = tempDir.resolve("MyClass.java");
        Files.writeString(file, """
                package com.example;
                public class MyClass {
                    public void myMethod() {}
                }
                """);

        var result = executeRead("MyClass", List.of(file), true);
        
        assertNotNull(result, "Should return a result for simple name");
        assertEquals("MyClass", result.get("class"), 
            "Should return class name (simple name per SourceReader contract)");
        assertNotNull(result.get("content"), "Should include content");
    }

    @Test
    @DisplayName("B2: read FQCN for indexed class succeeds")
    void readFqcnIndexedClassSucceeds() throws Exception {
        Path file = tempDir.resolve("MyClass.java");
        Files.writeString(file, """
                package com.example.service;
                public class MyClass {
                    public void myMethod() {}
                }
                """);

        var result = executeRead("com.example.service.MyClass", List.of(file), true);
        
        assertNotNull(result, "Should return a result for FQCN");
        assertEquals("MyClass", result.get("class"), 
            "Should return class name (simple name per SourceReader contract)");
        assertNotNull(result.get("content"), "Should include content");
    }
    
    @Test
    @DisplayName("B2b: FQCN class read must not be misparsed as Class.method")
    void readFqcnNotMisparsedAsMethod() throws Exception {
        Path file = tempDir.resolve("MyClass.java");
        Files.writeString(file, """
                package com.example.service;
                public class MyClass {
                    public void myMethod() {}
                }
                """);

        var result = executeRead("com.example.service.MyClass", List.of(file), true);
        
        assertNotNull(result, "FQCN class read must succeed");
        assertTrue(result.containsKey("content"), "Should be class read (has content)");
        assertFalse(result.containsKey("method"), "Should NOT be method read (no method field)");
        assertEquals("MyClass", result.get("class"), "Should resolve to class MyClass");
    }
    
    @Test
    @DisplayName("B2c: Class.method (lowercase method) still takes method path")
    void classMethodLowercaseStillMethodPath() throws Exception {
        Path file = tempDir.resolve("MyClass.java");
        Files.writeString(file, """
                package com.example;
                public class MyClass {
                    public void run() { System.out.println("running"); }
                }
                """);

        var result = executeRead("MyClass.run", List.of(file), true);
        
        assertNotNull(result, "Should find method");
        assertEquals("run", result.get("method"), "Should be method read");
        assertEquals("MyClass", result.get("class"), "Method's class is MyClass");
    }
    
    @Test
    @DisplayName("B2d: pkg.Class.method (lowercase method) still takes method path")
    void pkgClassMethodLowercaseStillMethodPath() throws Exception {
        Path file = tempDir.resolve("MyClass.java");
        Files.writeString(file, """
                package com.example;
                public class MyClass {
                    public void run() { System.out.println("running"); }
                }
                """);

        var result = executeRead("com.example.MyClass.run", List.of(file), true);
        
        assertNotNull(result, "Should find method");
        assertEquals("run", result.get("method"), "Should be method read");
        assertEquals("MyClass", result.get("class"), "Method's class is MyClass");
    }

    @Test
    @DisplayName("B3: FQCN warm path must use index, not full-tree scan on large file lists")
    void readFqcnUsesIndexNotFullScan() throws Exception {
        Path targetFile = tempDir.resolve("TargetClass.java");
        Files.writeString(targetFile, """
                package com.example;
                public class TargetClass {
                    public void targetMethod() {}
                }
                """);

        List<Path> manyFiles = new ArrayList<>();
        manyFiles.add(targetFile);
        
        for (int i = 0; i < 100; i++) {
            Path dummy = tempDir.resolve("Dummy" + i + ".java");
            Files.writeString(dummy, 
                    "package com.other;\n" +
                    "public class Dummy" + i + " {\n" +
                    "    public void method() {}\n" +
                    "}\n");
            manyFiles.add(dummy);
        }

        long start = System.currentTimeMillis();
        var result = executeRead("com.example.TargetClass", manyFiles, true);
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(result, "Should find the target class by FQCN");
        assertEquals("TargetClass", result.get("class"));
        
        assertTrue(elapsed < 2000, 
            "FQCN lookup with index should be fast (<2s), took: " + elapsed + "ms. " +
            "This likely means read is falling through to full javaFiles() scan instead of using index.");
    }

    @Test
    @DisplayName("B4: missing FQCN fails fast with honest not-found")
    void readMissingFqcnFailsFast() throws Exception {
        Path file = tempDir.resolve("ExistingClass.java");
        Files.writeString(file, """
                package com.example;
                public class ExistingClass {
                    public void method() {}
                }
                """);

        List<Path> manyFiles = new ArrayList<>();
        manyFiles.add(file);
        
        // Create 200 dummy files to ensure full scan would be slow
        for (int i = 0; i < 200; i++) {
            Path dummy = tempDir.resolve("Other" + i + ".java");
            Files.writeString(dummy, 
                    "package com.other;\n" +
                    "public class Other" + i + " {\n" +
                    "    public void m1() {}\n" +
                    "    public void m2() {}\n" +
                    "    public void m3() {}\n" +
                    "}\n");
            manyFiles.add(dummy);
        }

        long start = System.currentTimeMillis();
        var result = executeRead("com.missing.DefinitelyNotFound", manyFiles, true);
        long elapsed = System.currentTimeMillis() - start;

        assertNull(result, "Should return null for not-found FQCN");
        
        assertTrue(elapsed < 2000, 
            "Missing FQCN should fail fast (<2s), took: " + elapsed + "ms. " +
            "Should not scan all javaFiles when arg looks like FQCN. " +
            "If this fails, ReadCommand is still doing full-tree scan on FQCN miss.");
    }

    @Test
    @DisplayName("B5: read uses project JSON parser for result structure")
    void readUsesProjectJsonParser() throws Exception {
        Path file = tempDir.resolve("TestClass.java");
        Files.writeString(file, """
                package com.test;
                public class TestClass {
                    public void testMethod() {}
                }
                """);

        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, List.of(file), tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, List.of(file));

        var out = new ByteArrayOutputStream();
        var formatter = new JsonFormatter(false, null, new PrintStream(out));
        var ctx = new CommandContext(List.of(file), tempDir.toString(), null, 
            formatter, indexed, parser);

        int exitCode = new ReadCommand("TestClass").execute(ctx);
        assertEquals(1, exitCode, "Should succeed");

        String json = out.toString().trim();
        assertFalse(json.isEmpty(), "Should produce JSON output");

        Object parsed = JsonReader.parse(json);
        assertNotNull(parsed, "JsonReader should successfully parse the output");
        assertInstanceOf(Map.class, parsed, "Should parse to a Map structure");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) parsed;
        assertTrue(result.containsKey("class"), "Should have 'class' field");
        assertTrue(result.containsKey("content"), "Should have 'content' field");
    }

    private Map<String, Object> executeRead(String target, List<Path> files, boolean withIndex) 
            throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        
        var parser = new HybridJavaParser();
        var indexed = withIndex ? createIndexedCodebase(parser, files) : null;

        var formatter = new JsonFormatter(false, null, new PrintStream(out));
        var ctx = new CommandContext(files, tempDir.toString(), null, 
            formatter, indexed, parser);

        System.setErr(new PrintStream(err));
        try {
            int exitCode = new ReadCommand(target).execute(ctx);
            if (exitCode == 0) {
                return null;
            }

            String json = out.toString().trim();
            if (json.isEmpty()) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) JsonReader.parse(json);
            return result;
        } finally {
            System.setErr(System.err);
        }
    }

    private IndexedCodebase createIndexedCodebase(HybridJavaParser parser, List<Path> files) 
            throws Exception {
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        return IndexedCodebase.tryLoad(tempDir, files);
    }
}
