package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.output.BudgetAwareJsonFormatter;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contract tests for budget enforcement at formatter level.
 * Tests A1-A8 acceptance criteria from PR #8 methodology.
 * 
 * These tests MUST fail on current HEAD to demonstrate the gaps,
 * then pass after implementation fixes.
 */
class BudgetContractTest {

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
                public void method3() {}
            }
            """);
    }

    @Test
    @DisplayName("A1: TINY profile field set actually filters JSON output for classes")
    void a1_tinyProfileFieldSetFiltersClassOutput() {
        // Given: BudgetContext with TINY profile
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create class with many fields
        ClassInfo testClass = new ClassInfo(
            "TestClass",
            "com.example",
            2,
            7,
            List.of("public"),
            List.of(),
            "Object",
            List.of(),
            List.of(),
            false
        );
        
        // When: printClasses is called
        formatter.printClasses(List.of(testClass), tempDir);
        
        // Then: Output should contain ONLY name, packageName, methodCount
        String json = out.toString().trim();
        assertFalse(json.isEmpty(), "Output should not be empty");
        
        // TINY field set: name, packageName, methodCount ONLY
        assertTrue(json.contains("\"name\""), "Should have 'name'");
        assertTrue(json.contains("\"packageName\""), "Should have 'packageName'");
        assertTrue(json.contains("\"methodCount\""), "Should have 'methodCount'");
        
        // These should NOT be present under TINY (field filtering issue)
        assertFalse(json.contains("\"startLine\""), "Should NOT have 'startLine' under TINY");
        assertFalse(json.contains("\"endLine\""), "Should NOT have 'endLine' under TINY");
        assertFalse(json.contains("\"modifiers\""), "Should NOT have 'modifiers' under TINY");
        assertFalse(json.contains("\"isInterface\""), "Should NOT have 'isInterface' under TINY");
        
        // _budget should NOT wrap array roots
        assertFalse(json.contains("\"_budget\""), "Array output should not contain _budget at root");
    }

    @Test
    @DisplayName("A1: TINY profile field filtering does not just add metadata - it actually removes fields")
    void a1_tinyFieldFilteringRemovesFieldsNotJustMetadata() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        ClassInfo testClass = new ClassInfo(
            "TestClass",
            "com.example",
            2,
            7,
            List.of("public"),
            List.of(),
            "Object",
            List.of(),
            List.of(),
            false
        );
        
        formatter.printClasses(List.of(testClass), tempDir);
        
        String json = out.toString().trim();
        
        // Should NOT contain startLine, endLine, modifiers, isInterface in the JSON string itself
        assertFalse(json.contains("\"startLine\""), 
            "JSON should not contain startLine field under TINY profile");
        assertFalse(json.contains("\"endLine\""), 
            "JSON should not contain endLine field under TINY profile");
        
        // Should contain the allowed fields
        assertTrue(json.contains("\"name\""), "JSON should contain name field");
        assertTrue(json.contains("\"packageName\""), "JSON should contain packageName field");
        assertTrue(json.contains("\"methodCount\""), "JSON should contain methodCount field");
    }

    @Test
    @DisplayName("A2: printClasses honors effectiveMaxBytes under TINY")
    void a2_printClassesHonorsMaxBytes() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 500, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create many classes to exceed 500 bytes
        List<ClassInfo> classes = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            classes.add(new ClassInfo(
                "TestClass" + i,
                "com.example.package" + i,
                1,
                100,
                List.of("public"),
                List.of(),
                "Object",
                List.of(),
                List.of(),
                false
            ));
        }
        
        formatter.printClasses(classes, tempDir);
        
        String json = out.toString().trim();
        
        // Should be truncated to approximately 500 bytes
        assertTrue(json.length() <= 520, 
            "Output should be truncated near max-bytes limit. Got " + json.length() + " bytes");
        
        // Should indicate truncation
        assertTrue(json.contains("truncated") || ctx.buildMetadata() != null,
            "Should indicate truncation in output or metadata");
    }

    @Test
    @DisplayName("A2: printMethods honors effectiveMaxBytes under TINY")
    void a2_printMethodsHonorsMaxBytes() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 300, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create many methods to exceed 300 bytes
        List<MethodInfo> methods = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            methods.add(MethodInfo.basic(
                "testMethod" + i,
                "TestClass",
                10 + i,
                12 + i,
                "void",
                List.of("public"),
                List.of(),
                "public void testMethod" + i + "() {}"
            ));
        }
        
        formatter.printMethods(methods, testFile, "test");
        
        String json = out.toString().trim();
        
        // Should be truncated
        assertTrue(json.length() <= 320,
            "Methods output should be truncated near max-bytes limit. Got " + json.length() + " bytes");
    }

    @Test
    @DisplayName("A2: printClassSummary honors effectiveMaxBytes under TINY")
    void a2_printClassSummaryHonorsMaxBytes() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, 400, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create class with many methods
        List<MethodInfo> methods = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            methods.add(MethodInfo.basic(
                "method" + i,
                "LargeClass",
                5 + i,
                7 + i,
                "void",
                List.of("public"),
                List.of(),
                "public void method" + i + "() {}"
            ));
        }
        
        ClassInfo classInfo = new ClassInfo(
            "LargeClass",
            "com.example",
            1,
            100,
            List.of("public"),
            methods,
            "Object",
            List.of(),
            List.of(),
            false
        );
        
        formatter.printClassSummary(classInfo, testFile);
        
        String json = out.toString().trim();
        
        // Should be truncated
        assertTrue(json.length() <= 420,
            "ClassSummary output should be truncated near max-bytes limit. Got " + json.length() + " bytes");
    }

    @Test
    @DisplayName("A3: Top-level JSON arrays remain arrays (not wrapped)")
    void a3_topLevelArraysRemainArrays() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        ClassInfo testClass = new ClassInfo(
            "TestClass",
            "com.example",
            1,
            10,
            List.of("public"),
            List.of(),
            "Object",
            List.of(),
            List.of(),
            false
        );
        
        formatter.printClasses(List.of(testClass), tempDir);
        
        String json = out.toString().trim();
        
        // Must start with [ and end with ]
        assertTrue(json.startsWith("["), "Top-level output should be an array starting with [");
        assertTrue(json.endsWith("]"), "Top-level output should be an array ending with ]");
        
        // Should NOT be wrapped in an object with _budget, items, count
        assertFalse(json.startsWith("{"), "Should NOT wrap array in object");
        assertFalse(json.contains("\"items\""), "Should NOT have 'items' wrapper");
        assertFalse(json.contains("\"count\""), "Should NOT have 'count' wrapper");
    }

    @Test
    @DisplayName("A4: Under TINY, summary command runs mini behavior")
    void a4_tinyProfileSummaryRunsMini() {
        // Verify that BudgetPolicy DEGRADE action is set for summary under TINY
        var action = BudgetPolicy.getAction("summary", BudgetProfile.TINY);
        
        assertEquals(BudgetPolicy.Action.DEGRADE, action,
            "summary under TINY should have DEGRADE action");
        
        // Verify ALLOW for SMALL
        var actionSmall = BudgetPolicy.getAction("summary", BudgetProfile.SMALL);
        assertEquals(BudgetPolicy.Action.ALLOW, actionSmall,
            "summary under SMALL should have ALLOW action");
    }

    @Test
    @DisplayName("A5: Under TINY, reading class without method should be denied")
    void a5_tinyProfileReadClassDenied() {
        // Verify that BudgetPolicy DEGRADE action is set for read under TINY
        var action = BudgetPolicy.getAction("read", BudgetProfile.TINY);
        
        assertEquals(BudgetPolicy.Action.DEGRADE, action,
            "read under TINY should have DEGRADE action for whole-class reads");
    }

    @Test
    @DisplayName("A6: Under TINY, denied heavy commands return DENY action")
    void a6_tinyProfileHeavyCommandDenied() {
        // Verify heavy commands are denied under TINY
        assertEquals(BudgetPolicy.Action.DENY, 
            BudgetPolicy.getAction("context", BudgetProfile.TINY),
            "context should be DENY under TINY");
        
        assertEquals(BudgetPolicy.Action.DENY,
            BudgetPolicy.getAction("call-chain", BudgetProfile.TINY),
            "call-chain should be DENY under TINY");
        
        assertEquals(BudgetPolicy.Action.DENY,
            BudgetPolicy.getAction("dump", BudgetProfile.TINY),
            "dump should be DENY under TINY");
    }

    @Test
    @DisplayName("A7: describe under tiny uses CommandRegistry filtered by BudgetPolicy")
    void a7_describeUsesBudgetPolicyFilter() {
        // Get all commands from registry
        String[] allCommands = com.jsrc.app.command.meta.CommandRegistry.knownCommandNames();
        
        // Filter by TINY visibility
        long tinyVisibleCount = java.util.Arrays.stream(allCommands)
            .filter(cmd -> BudgetPolicy.isVisibleCommand(cmd, BudgetProfile.TINY))
            .count();
        
        // TINY should have ~10 commands per spec
        assertTrue(tinyVisibleCount >= 8 && tinyVisibleCount <= 12,
            "TINY should have ~10 visible commands, got " + tinyVisibleCount);
        
        // Verify core commands are visible
        assertTrue(BudgetPolicy.isVisibleCommand("index", BudgetProfile.TINY));
        assertTrue(BudgetPolicy.isVisibleCommand("overview", BudgetProfile.TINY));
        assertTrue(BudgetPolicy.isVisibleCommand("mini", BudgetProfile.TINY));
        assertTrue(BudgetPolicy.isVisibleCommand("read", BudgetProfile.TINY));
        
        // Verify heavy commands are NOT visible
        assertFalse(BudgetPolicy.isVisibleCommand("context", BudgetProfile.TINY));
        assertFalse(BudgetPolicy.isVisibleCommand("call-chain", BudgetProfile.TINY));
        assertFalse(BudgetPolicy.isVisibleCommand("dump", BudgetProfile.TINY));
    }

    @Test
    @DisplayName("A8: deps command applies budget limits via formatter")
    void a8_depsCommandAppliesBudgetLimits() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create a dependency result with many imports
        List<String> manyImports = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            manyImports.add("com.example.Import" + i);
        }
        
        var deps = new com.jsrc.app.model.DependencyResult(
            "TestClass",
            manyImports,
            List.of(),
            List.of()
        );
        
        formatter.printDependencies(deps);
        
        String json = out.toString().trim();
        
        // The printDependencies should respect budget limits via max-bytes
        assertTrue(json.length() < 3000, 
            "deps output should be reasonable size under budget");
    }

    @Test
    @DisplayName("A8: hierarchy command applies budget limits via formatter")
    void a8_hierarchyCommandAppliesBudgetLimits() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create hierarchy with many subclasses
        List<String> manySubclasses = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            manySubclasses.add("SubClass" + i);
        }
        
        var hierarchy = new com.jsrc.app.model.HierarchyResult(
            "TestClass",
            "Object",
            List.of(),
            manySubclasses,
            List.of()
        );
        
        formatter.printHierarchy(hierarchy);
        
        String json = out.toString().trim();
        
        // Should apply limits
        assertTrue(json.length() < 3000,
            "hierarchy output should be reasonable size under budget");
    }

    @Test
    @DisplayName("A8: annotations command applies budget limits")
    void a8_annotationsCommandAppliesBudgetLimits() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create many annotation matches
        List<com.jsrc.app.model.AnnotationMatch> matches = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            matches.add(new com.jsrc.app.model.AnnotationMatch(
                "method",
                "method" + i,
                "TestClass",
                testFile,
                10 + i,
                com.jsrc.app.parser.model.AnnotationInfo.marker("Test")
            ));
        }
        
        formatter.printAnnotationMatches(matches);
        
        String json = out.toString().trim();
        
        // Count opening braces to estimate number of items
        // Under TINY (limit 10), we should have at most 10 objects
        long objectCount = json.chars().filter(ch -> ch == '{').count();
        
        // Each annotation match creates one object, so we should have ~10 or fewer
        assertTrue(objectCount <= 12,
            "annotations output should be limited under TINY, got ~" + objectCount + " objects");
    }

    @Test
    @DisplayName("A8: smells command output respects budget limits")
    void a8_smellsCommandAppliesBudgetLimits() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BudgetAwareJsonFormatter formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), ctx);
        
        // Create many smells
        List<com.jsrc.app.parser.model.CodeSmell> smells = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            smells.add(new com.jsrc.app.parser.model.CodeSmell(
                "TEST_SMELL_" + i,
                com.jsrc.app.parser.model.CodeSmell.Severity.WARNING,
                "Test smell " + i,
                10 + i,
                "method" + i,
                "TestClass"
            ));
        }
        
        formatter.printSmells(smells, testFile);
        
        String json = out.toString().trim();
        
        // Should apply max-bytes
        assertTrue(json.length() < 3000,
            "smells output should be reasonable size under budget");
    }
}
