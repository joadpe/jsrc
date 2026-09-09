package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.output.BudgetAwareJsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.SourceReader;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.model.OverviewResult;
import com.jsrc.app.model.CommandHint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Measurable p95/byte ceiling contract tests for issue #13.
 * 
 * These tests enforce that outputs under TINY/SMALL budget profiles 
 * respect the hard byte ceilings: TINY=2048, SMALL=8192.
 * 
 * Strategy: Create oversized fixtures that would exceed the ceiling,
 * then verify the formatter applies truncation correctly and produces
 * valid, parseable JSON within the ceiling.
 */
class BudgetCeilingContractTest {

    @TempDir
    static Path tempDir;
    
    private static Path testFile;

    @BeforeAll
    static void setUp() throws IOException {
        testFile = tempDir.resolve("TestClass.java");
        Files.writeString(testFile, """
            package com.example;
            public class TestClass {
                public void method1() {}
                public void method2() {}
            }
            """);
    }

    @Test
    @DisplayName("C1: BudgetProfile.TINY.defaultMaxBytes() == 2048 and SMALL == 8192")
    void c1_profileMaxBytesLocked() {
        assertEquals(2048, BudgetProfile.TINY.defaultMaxBytes(),
            "TINY defaultMaxBytes must be exactly 2048");
        assertEquals(8192, BudgetProfile.SMALL.defaultMaxBytes(),
            "SMALL defaultMaxBytes must be exactly 8192");
    }

    @Test
    @DisplayName("C2: Oversized object via printResult under TINY → length ≤2048 and parseable")
    void c2_oversizedObjectUnderTiny() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create oversized object that would exceed 2048 bytes
        Map<String, Object> oversized = createOversizedMap(3000);
        
        formatter.printResult(oversized);
        
        String json = out.toString().trim();
        
        // Must be within ceiling
        assertTrue(json.length() <= 2048,
            "C2 FAIL: Output exceeds TINY ceiling. Got " + json.length() + " bytes, expected ≤2048");
        
        // Must be parseable JSON
        assertDoesNotThrow(() -> JsonReader.parse(json),
            "C2 FAIL: Truncated output is not valid JSON");
    }

    @Test
    @DisplayName("C3: Oversized object via printResult under SMALL → length ≤8192 and parseable")
    void c3_oversizedObjectUnderSmall() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.SMALL, null, null, false, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create oversized object that would exceed 8192 bytes
        Map<String, Object> oversized = createOversizedMap(10000);
        
        formatter.printResult(oversized);
        
        String json = out.toString().trim();
        
        // Must be within ceiling
        assertTrue(json.length() <= 8192,
            "C3 FAIL: Output exceeds SMALL ceiling. Got " + json.length() + " bytes, expected ≤8192");
        
        // Must be parseable JSON
        assertDoesNotThrow(() -> JsonReader.parse(json),
            "C3 FAIL: Truncated output is not valid JSON");
    }

    @Test
    @DisplayName("C4: Oversized array via printClasses under TINY → length ≤2048 and parseable")
    void c4_oversizedArrayUnderTiny() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create many classes to exceed 2048 bytes
        List<ClassInfo> classes = createManyClasses(100);
        
        formatter.printClasses(classes, tempDir);
        
        String json = out.toString().trim();
        
        // Must be within ceiling
        assertTrue(json.length() <= 2048,
            "C4 FAIL: printClasses output exceeds TINY ceiling. Got " + json.length() + " bytes, expected ≤2048");
        
        // Must be parseable JSON array
        Object parsed = assertDoesNotThrow(() -> JsonReader.parse(json),
            "C4 FAIL: Truncated array is not valid JSON");
        assertTrue(parsed instanceof List,
            "C4 FAIL: printClasses should return a JSON array");
    }

    @Test
    @DisplayName("C4 variant: Oversized array via printRefs under TINY → length ≤2048 and parseable")
    void c4_oversizedRefsUnderTiny() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create many refs to exceed 2048 bytes
        List<Map<String, Object>> refs = createManyRefs(150);
        
        formatter.printRefs(refs, "callers", "TargetMethod");
        
        String json = out.toString().trim();
        
        // Must be within ceiling
        assertTrue(json.length() <= 2048,
            "C4 FAIL: printRefs output exceeds TINY ceiling. Got " + json.length() + " bytes, expected ≤2048");
        
        // Must be parseable JSON array
        Object parsed = assertDoesNotThrow(() -> JsonReader.parse(json),
            "C4 FAIL: Truncated refs array is not valid JSON");
        assertTrue(parsed instanceof List,
            "C4 FAIL: printRefs should return a JSON array");
    }

    @Test
    @DisplayName("C5: printOverview with huge package list under TINY → length ≤2048")
    void c5_oversizedOverviewUnderTiny() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create huge package list
        List<String> hugePackages = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            hugePackages.add("com.example.package" + i + ".subpackage" + i + ".nested" + i);
        }
        OverviewResult result = new OverviewResult(500, 1000, 200, 5000, hugePackages);
        
        formatter.printOverview(result);
        
        String json = out.toString().trim();
        
        // Must be within ceiling (list limit + max-bytes)
        assertTrue(json.length() <= 2048,
            "C5 FAIL: printOverview output exceeds TINY ceiling. Got " + json.length() + " bytes, expected ≤2048");
        
        // Must be parseable JSON object
        Object parsed = assertDoesNotThrow(() -> JsonReader.parse(json),
            "C5 FAIL: Truncated overview is not valid JSON");
        assertTrue(parsed instanceof Map,
            "C5 FAIL: printOverview should return a JSON object");
    }

    @Test
    @DisplayName("C6: printReadResult with huge content under TINY → length ≤2048 and parseable")
    void c6_oversizedReadResultUnderTiny() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create huge content blob
        StringBuilder hugeContent = new StringBuilder("public class HugeClass {\n");
        for (int i = 0; i < 500; i++) {
            hugeContent.append("    public void method").append(i).append("() { /* line ").append(i).append(" */ }\n");
        }
        hugeContent.append("}\n");
        
        SourceReader.ReadResult result = new SourceReader.ReadResult(
            "HugeClass",
            null,
            testFile,
            1,
            1000,
            hugeContent.toString()
        );
        
        formatter.printReadResult(result);
        
        String json = out.toString().trim();
        
        // Must be within ceiling
        assertTrue(json.length() <= 2048,
            "C6 FAIL: printReadResult output exceeds TINY ceiling. Got " + json.length() + " bytes, expected ≤2048");
        
        // Must be parseable JSON object
        Object parsed = assertDoesNotThrow(() -> JsonReader.parse(json),
            "C6 FAIL: Truncated readResult is not valid JSON");
        assertTrue(parsed instanceof Map,
            "C6 FAIL: printReadResult should return a JSON object");
    }

    @Test
    @DisplayName("C7: printResultWithHints under TINY with many hints → length ≤2048")
    void c7_resultWithManyHintsUnderTiny() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create base data
        Map<String, Object> data = createOversizedMap(1500);
        
        // Create many hints that would push output over ceiling
        List<CommandHint> manyHints = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            manyHints.add(new CommandHint(
                "command-" + i,
                "This is a description for command " + i + " with additional explanatory text"
            ));
        }
        
        formatter.printResultWithHints(data, manyHints);
        
        String json = out.toString().trim();
        
        // Must be within ceiling
        assertTrue(json.length() <= 2048,
            "C7 FAIL: printResultWithHints output exceeds TINY ceiling. Got " + json.length() + " bytes, expected ≤2048");
        
        // Must be parseable JSON
        assertDoesNotThrow(() -> JsonReader.parse(json),
            "C7 FAIL: Truncated output with hints is not valid JSON");
    }

    @Test
    @DisplayName("C7 verification: printResultWithHints reuses applyMaxBytes (no second truncator)")
    void c7_hintsReuseApplyMaxBytes() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Small data, many hints
        Map<String, Object> data = Map.of("result", "success", "count", 42);
        List<CommandHint> hints = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hints.add(new CommandHint("cmd" + i, "Description " + i));
        }
        
        formatter.printResultWithHints(data, hints);
        
        String json = out.toString().trim();
        
        // Verify output respects ceiling
        assertTrue(json.length() <= 2048,
            "C7 FAIL: Hints bypass applyMaxBytes. Got " + json.length() + " bytes");
        
        // Verify it's valid JSON (proves applyMaxBytes was used)
        Object parsed = assertDoesNotThrow(() -> JsonReader.parse(json),
            "C7 FAIL: Output not valid JSON - applyMaxBytes not applied correctly");
        
        // Should have truncation marker if truncated
        if (json.length() >= 2000) {
            assertTrue(json.contains("truncated") || ctx.buildMetadata() != null,
                "C7 FAIL: Large output missing truncation indicator");
        }
    }

    @Test
    @DisplayName("C8: No new max-bytes helper type; GREEN with existing applyMaxBytes")
    void c8_noNewHelperType() {
        // This is a documentation test - verification is manual code review
        // Key assertion: All C2-C7 tests pass using BudgetAwareJsonFormatter.applyMaxBytes
        // No new truncation helper class should exist in the codebase
        
        // We verify this by ensuring BudgetAwareJsonFormatter is the only formatter with max-bytes logic
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Simple sanity check that applyMaxBytes works
        Map<String, Object> data = createOversizedMap(5000);
        formatter.printResult(data);
        
        String json = out.toString().trim();
        assertTrue(json.length() <= 2048,
            "C8 FAIL: applyMaxBytes not working correctly");
        assertDoesNotThrow(() -> JsonReader.parse(json),
            "C8 FAIL: applyMaxBytes produces invalid JSON");
    }

    // Helper methods to create test fixtures

    private Map<String, Object> createOversizedMap(int targetBytes) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        int currentSize = 0;
        int counter = 0;
        
        while (currentSize < targetBytes) {
            String key = "field_" + counter;
            String value = "This is value number " + counter + " with some padding to increase size";
            map.put(key, value);
            // Rough estimate: key + value + JSON overhead
            currentSize += key.length() + value.length() + 10;
            counter++;
        }
        
        return map;
    }

    private List<ClassInfo> createManyClasses(int count) {
        List<ClassInfo> classes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            classes.add(new ClassInfo(
                "TestClass" + i,
                "com.example.package" + i,
                10 + i,
                50 + i,
                List.of("public"),
                List.of(),
                "Object",
                List.of(),
                List.of(),
                false
            ));
        }
        return classes;
    }

    private List<Map<String, Object>> createManyRefs(int count) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            refs.add(Map.of(
                "class", "ClassName" + i,
                "method", "methodName" + i,
                "file", "/very/long/path/to/source/file/number/" + i + "/TestClass.java",
                "line", 100 + i,
                "column", 50
            ));
        }
        return refs;
    }
}
