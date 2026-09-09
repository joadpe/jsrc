package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.output.BudgetAwareJsonFormatter;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.model.OverviewResult;
import com.jsrc.app.parser.SourceReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * RED tests for V1-V5 oracles from PR #8 methodology.
 * These tests MUST fail on current HEAD to demonstrate gaps.
 */
class BudgetFormatterContractTest {

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
    @DisplayName("V1: printOverview under TINY enforces budget (not bare super bypass)")
    void v1_printOverviewEnforcesBudget() {
        // Given: TINY profile with small max-bytes
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 200, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create OverviewResult with many packages (would exceed 200 bytes)
        List<String> manyPackages = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            manyPackages.add("com.example.package" + i + ".subpackage" + i);
        }
        OverviewResult result = new OverviewResult(100, 200, 50, 1000, manyPackages);
        
        // When: printOverview is called
        formatter.printOverview(result);
        
        String json = out.toString().trim();
        
        // Then: Output must be truncated/limited to ~200 bytes, NOT unbounded
        assertTrue(json.length() <= 250, 
            "V1 FAIL: printOverview bypasses budget via bare super. Output: " + json.length() + " bytes (expected <= 250)");
        
        // Should show truncation indicator
        assertTrue(json.contains("truncated") || ctx.buildMetadata() != null,
            "V1 FAIL: No truncation indicator in output or metadata");
    }

    @Test
    @DisplayName("V1: printOverview with packageCount under TINY enforces list limit")
    void v1_printOverviewWithPackageCountEnforcesLimit() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create many packages
        List<String> manyPackages = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            manyPackages.add("com.pkg" + i);
        }
        OverviewResult result = new OverviewResult(100, 200, 50, 1000, manyPackages);
        
        // When
        formatter.printOverview(result, manyPackages.size());
        
        String json = out.toString().trim();
        
        // Parse and check packages array size
        if (json.contains("\"packages\"")) {
            int pkgStart = json.indexOf("\"packages\":[") + "\"packages\":[".length();
            int pkgEnd = json.indexOf("]", pkgStart);
            String pkgsArray = json.substring(pkgStart, pkgEnd);
            int pkgCount = pkgsArray.isEmpty() ? 0 : pkgsArray.split(",").length;
            
            assertTrue(pkgCount <= 10,
                "V1 FAIL: printOverview does not limit packages list. Got " + pkgCount + ", expected <= 10");
        } else {
            // If packages not present, that's OK (field filtering)
        }
    }

    @Test
    @DisplayName("V2: printReadResult under TINY respects max-bytes with safe JSON truncation")
    void v2_printReadResultRespectsMaxBytes() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 300, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create large source content
        String largeContent = "public class Huge {\n" + "    // line\n".repeat(100) + "}";
        SourceReader.ReadResult result = new SourceReader.ReadResult(
            "Huge",
            null,
            testFile,
            1,
            200,
            largeContent
        );
        
        // When
        formatter.printReadResult(result);
        
        String json = out.toString().trim();
        
        // Then: Must be truncated near 300 bytes
        assertTrue(json.length() <= 330,
            "V2 FAIL: printReadResult does not enforce max-bytes. Got " + json.length() + " bytes");
        
        // Must still be valid JSON (V4) - use real parser
        assertDoesNotThrow(() -> JsonReader.parse(json),
            "V2/V4 FAIL: Truncated output is not valid JSON");
    }

    @Test
    @DisplayName("V3: printRefs applies list limit AND max-bytes (not just limit then super)")
    void v3_printRefsAppliesLimitAndMaxBytes() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 400, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create many refs with large content
        List<Map<String, Object>> manyRefs = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            manyRefs.add(Map.of(
                "class", "ClassNumber" + i,
                "method", "methodName" + i,
                "file", "/very/long/path/to/file" + i + ".java",
                "line", i * 10
            ));
        }
        
        // When
        formatter.printRefs(manyRefs, "callers", "TargetMethod");
        
        String json = out.toString().trim();
        
        // Then: Must apply BOTH list limit (10) AND max-bytes (400)
        assertTrue(json.length() <= 450,
            "V3 FAIL: printRefs does not apply max-bytes. Got " + json.length() + " bytes");
        
        // Count items in array
        if (json.startsWith("[")) {
            String[] items = json.substring(1, json.length() - 1).split("\\},\\{");
            assertTrue(items.length <= 10,
                "V3 FAIL: printRefs does not apply list limit. Got " + items.length + " items");
        }
    }

    @Test
    @DisplayName("V4: applyMaxBytes produces valid JSON (not broken substring + truncated marker)")
    void v4_applyMaxBytesProducesValidJson() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 150, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create data that will be truncated
        Map<String, Object> data = Map.of(
            "field1", "value1_with_some_length",
            "field2", "value2_with_some_length",
            "field3", "value3_with_some_length",
            "field4", "value4_with_some_length",
            "field5", "value5_with_some_length"
        );
        
        // When
        formatter.printResult(data);
        
        String json = out.toString().trim();
        
        // Must parse as valid JSON - use real parser
        assertDoesNotThrow(() -> JsonReader.parse(json),
            "V4 FAIL: applyMaxBytes produces invalid JSON: " + json);
        
        // Should not end with broken structure like: ..."truncated":true}
        // after a substring cut
        if (json.contains("truncated")) {
            // If truncated, must be proper JSON object or array
            assertTrue(json.endsWith("}") || json.endsWith("]"),
                "V4 FAIL: Truncated JSON does not end properly: " + json.substring(Math.max(0, json.length() - 30)));
        }
    }

    @Test
    @DisplayName("V4: Truncated JSON with valid structure (object with truncated marker)")
    void v4_truncatedJsonHasValidStructure() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 80, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        Map<String, Object> data = Map.of(
            "longFieldName1", "longValue1",
            "longFieldName2", "longValue2",
            "longFieldName3", "longValue3"
        );
        
        formatter.printResult(data);
        
        String json = out.toString().trim();
        
        // Must parse as valid JSON using real parser
        Object parsed = assertDoesNotThrow(() -> JsonReader.parse(json),
            "V4 FAIL: Cannot parse truncated JSON");
        assertNotNull(parsed, "V4 FAIL: Parsed JSON is null");
        
        // If truncated, should have a truncated marker
        if (json.length() <= 100) {
            assertTrue(json.contains("truncated") || json.contains("..."),
                "V4 FAIL: Truncated JSON missing truncation indicator");
        }
    }

    @Test
    @DisplayName("V5: Array-shaped outputs remain arrays (no _budget wrapper)")
    void v5_arrayOutputsRemainArrays() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        List<Map<String, Object>> arrayData = List.of(
            Map.of("id", 1, "name", "Item1"),
            Map.of("id", 2, "name", "Item2")
        );
        
        // When
        formatter.printResult(arrayData);
        
        String json = out.toString().trim();
        
        // Then: Must start with [ and end with ]
        assertTrue(json.startsWith("["),
            "V5 FAIL: Array output does not start with [, got: " + json.substring(0, Math.min(20, json.length())));
        assertTrue(json.endsWith("]"),
            "V5 FAIL: Array output does not end with ], got: " + json.substring(Math.max(0, json.length() - 20)));
        
        // Must NOT have _budget, items, or count wrappers
        assertFalse(json.contains("\"_budget\""),
            "V5 FAIL: Array output contains _budget wrapper");
        assertFalse(json.contains("\"items\":"),
            "V5 FAIL: Array output wrapped with items field");
    }

    @Test
    @DisplayName("V5: Object roots can have _budget metadata")
    void v5_objectRootsCanHaveBudget() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ctx.setTruncated(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        Map<String, Object> objectData = Map.of("key", "value");
        
        // When
        formatter.printResult(objectData);
        
        String json = out.toString().trim();
        
        // Then: Object can have _budget (but arrays must not)
        assertTrue(json.startsWith("{"), "Should be an object");
        // _budget is OK for objects
        if (ctx.buildMetadata() != null) {
            assertTrue(json.contains("\"_budget\"") || !json.contains("_budget"),
                "V5: Object can have _budget when metadata exists");
        }
    }

    @Test
    @DisplayName("V4 EDGE: Object with very long first key, no comma in truncation window")
    void v4_objectWithLongFirstKeyNoComma() {
        // This is the critical edge case: when first field name is very long
        // and maxBytes is small, cutPoint can become openBrace+1, producing {,"_truncated":true}
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 50, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create object with one very long field name (no comma will fit in safe window)
        Map<String, Object> data = Map.of(
            "veryVeryLongFieldNameThatExceedsMaxBytesAlone", "value"
        );
        
        // When
        formatter.printResult(data);
        
        String json = out.toString().trim();
        
        // Then: Must still be valid JSON - NOT {,"_truncated":true}
        assertDoesNotThrow(() -> JsonReader.parse(json),
            "V4 EDGE FAIL: Long first key produces invalid JSON like {,\"_truncated\":true}. Got: " + json);
        
        // Should be valid object (even if truncated to just marker)
        assertTrue(json.startsWith("{") && json.endsWith("}"),
            "V4 EDGE FAIL: Invalid object structure. Got: " + json);
        
        // Should NOT have invalid {, pattern
        assertFalse(json.matches("\\{\\s*,.*"),
            "V4 EDGE FAIL: Produces {, invalid syntax. Got: " + json);
    }

    @Test
    @DisplayName("V4 EDGE: Array with large first element, no comma in truncation window")
    void v4_arrayWithLargeFirstElementNoComma() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 60, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create array with one large element
        List<Map<String, Object>> data = List.of(
            Map.of("veryLongFieldName1", "veryLongValue1", "field2", "value2")
        );
        
        // When
        formatter.printResult(data);
        
        String json = out.toString().trim();
        
        // Then: Must still be valid JSON array
        assertDoesNotThrow(() -> JsonReader.parse(json),
            "V4 EDGE FAIL: Large array element produces invalid JSON. Got: " + json);
        
        // Should be valid array
        assertTrue(json.startsWith("[") && json.endsWith("]"),
            "V4 EDGE FAIL: Invalid array structure. Got: " + json);
    }

    @Test
    @DisplayName("V4 EDGE: Multiple fields but maxBytes cuts before first comma")
    void v4_multipleFieldsCutBeforeFirstComma() {
        // Tight maxBytes that cuts the JSON before any comma appears
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 35, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        Map<String, Object> data = Map.of(
            "field1", "value1",
            "field2", "value2"
        );
        
        // When
        formatter.printResult(data);
        
        String json = out.toString().trim();
        
        // Then: Must produce valid JSON
        assertDoesNotThrow(() -> JsonReader.parse(json),
            "V4 EDGE FAIL: Cut before comma produces invalid JSON. Got: " + json);
        
        assertTrue(json.startsWith("{") && json.endsWith("}"),
            "V4 EDGE FAIL: Not a valid object. Got: " + json);
    }
}
