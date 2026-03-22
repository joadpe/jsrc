package com.jsrc.app.command;

import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import com.jsrc.app.command.analysis.PerfCommand;

/**
 * Tests for PerfCommand — performance bottleneck detection.
 */
class PerfCommandTest {

    @TempDir
    static Path tempDir;
    static CommandContext ctx;

    @BeforeAll
    static void setup() throws Exception {
        // Copy perf fixtures
        Path fixtureDir = Path.of("src/test/resources/perf-fixture");
        for (File f : fixtureDir.toFile().listFiles()) {
            Files.copy(f.toPath(), tempDir.resolve(f.getName()));
        }

        var parser = new HybridJavaParser();
        var files = new ArrayList<Path>();
        try (var stream = Files.walk(tempDir)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        var captured = new ByteArrayOutputStream();
        var formatter = OutputFormatter.create(true, false, null, new PrintStream(captured, true));
        ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, parser);
    }

    private String capture(Command cmd) {
        var captured = new ByteArrayOutputStream();
        var ps = new PrintStream(captured, true);
        var formatter = OutputFormatter.create(true, false, null, ps);
        var captureCtx = new CommandContext(
                ctx.javaFiles(), ctx.rootPath(), ctx.config(),
                formatter, ctx.indexed(), ctx.parser());
        cmd.execute(captureCtx);
        ps.flush();
        return captured.toString().trim();
    }

    @Test
    void detectsAllocationInLoop() {
        String json = capture(new PerfCommand("SlowService.batchUpdate", 1));
        assertTrue(json.contains("ALLOCATION_IN_LOOP"),
                "Should detect HashMap allocation in loop. Got: " + json);
    }

    @Test
    void detectsIoInLoop() {
        String json = capture(new PerfCommand("SlowService.exportAll", 1));
        assertTrue(json.contains("LOOP_WITH_IO"),
                "Should detect File I/O in loop. Got: " + json);
    }

    @Test
    void detectsLoopWithLinearCallee() {
        String json = capture(new PerfCommand("SlowService.processAll", 1));
        assertTrue(json.contains("LOOP_WITH_LINEAR_CALLEE") || json.contains("LINEAR_SCAN"),
                "Should detect loop calling linear scan method. Got: " + json);
    }

    @Test
    void noFindingsForEfficientCode() {
        String json = capture(new PerfCommand("FastService", 1));
        assertTrue(json.contains("\"totalFindings\":0") || json.contains("\"perMethod\":[]"),
                "FastService should have 0 findings. Got: " + json);
    }

    @Test
    void wholeClassAnalysis() {
        String json = capture(new PerfCommand("SlowService", 1));
        assertTrue(json.contains("perMethod"), "Should have perMethod array. Got: " + json);
        assertTrue(json.contains("totalFindings"), "Should have totalFindings. Got: " + json);
        assertTrue(json.contains("worstMethod"), "Should identify worst method. Got: " + json);
    }

    @Test
    void indexOnlyAtDepth0() {
        // With depth 0, should still analyze source of target
        String json = capture(new PerfCommand("SlowService.batchUpdate", 0));
        assertTrue(json.contains("ALLOCATION_IN_LOOP") || json.contains("source"),
                "Depth 0 should still detect patterns in target method");
    }

    @Test
    void detectsDeepIOAtDepth2() {
        // processAll → processItem → writeResult → new File (3 levels deep)
        String json = capture(new PerfCommand("DeepIOService.processAll", 3));
        assertTrue(json.contains("LOOP_WITH_DEEP_IO") || json.contains("DEEP_IO"),
                "Should detect I/O 3 levels deep in call chain. Got: " + json);
    }

    @Test
    void noDeepIOAtDepth0() {
        // At depth 0, should NOT follow callees
        String json = capture(new PerfCommand("DeepIOService.processAll", 0));
        assertFalse(json.contains("DEEP_IO"),
                "Depth 0 should not find deep I/O. Got: " + json);
    }

    @Test
    void detectsSameClassLinearCallee() {
        String json = capture(new PerfCommand("NestedCallService.processAll", 1));
        assertTrue(json.contains("LOOP_WITH_LINEAR_CALLEE") || json.contains("LINEAR_SCAN"),
                "Should detect same-class method with loop. Got: " + json);
    }

    @Test
    void detectsStringConcatInLoop() {
        String json = capture(new PerfCommand("JavaAntiPatterns.joinAll", 1));
        assertTrue(json.contains("STRING_CONCAT"),
                "Should detect string concatenation in loop. Got: " + json);
    }

    @Test
    void detectsStringFormatInLoop() {
        String json = capture(new PerfCommand("JavaAntiPatterns.logAll", 1));
        assertTrue(json.contains("STRING_FORMAT"),
                "Should detect String.format in loop. Got: " + json);
    }

    @Test
    void detectsDateFormatInLoop() {
        String json = capture(new PerfCommand("JavaAntiPatterns.formatDates", 1));
        assertTrue(json.contains("DATE_FORMAT"),
                "Should detect SimpleDateFormat in loop. Got: " + json);
    }

    @Test
    void detectsRegexCompileInLoop() {
        String json = capture(new PerfCommand("JavaAntiPatterns.countMatches", 1));
        assertTrue(json.contains("REGEX_COMPILE"),
                "Should detect Pattern.compile in loop. Got: " + json);
    }

    @Test
    void detectsReflectionInLoop() {
        String json = capture(new PerfCommand("JavaAntiPatterns.processReflective", 1));
        assertTrue(json.contains("REFLECTION"),
                "Should detect reflection in loop. Got: " + json);
    }

    @Test
    void detectsStreamInLoop() {
        String json = capture(new PerfCommand("JavaAntiPatterns.filterEach", 1));
        assertTrue(json.contains("STREAM"),
                "Should detect stream creation in loop. Got: " + json);
    }

    @Test
    void detectsListRemoveInLoop() {
        String json = capture(new PerfCommand("JavaAntiPatterns.removeOdds", 1));
        assertTrue(json.contains("LIST_REMOVE"),
                "Should detect list.remove in loop. Got: " + json);
    }

    @Test
    void detectsDaoInheritanceInLoop() {
        String json = capture(new PerfCommand("OrderProcessor.processCustomers", 1));
        assertTrue(json.contains("DB_QUERY") || json.contains("DAO"),
                "Should detect DAO class call in loop via inheritance. Got: " + json);
    }

    @Test
    void detectsDbQueryInLoop() {
        String json = capture(new PerfCommand("JavaAntiPatterns.loadDetails", 1));
        assertTrue(json.contains("DB_QUERY"),
                "Should detect database query in loop. Got: " + json);
    }

    @Test
    void detectsConnectionInLoop() {
        String json = capture(new PerfCommand("JavaAntiPatterns.fetchAll", 1));
        assertTrue(json.contains("CONNECTION"),
                "Should detect connection/URL in loop. Got: " + json);
    }

    @Test
    void detectsEdtBlocking() {
        String json = capture(new PerfCommand("LegacySwingPanel", 1));
        assertTrue(json.contains("EDT_BLOCKING"),
                "Should detect DB query in Swing listener. Got: " + json);
    }

    @Test
    void detectsCursorLeak() {
        String json = capture(new PerfCommand("LegacySwingPanel", 1));
        assertTrue(json.contains("CURSOR_LEAK"),
                "Should detect PreparedStatement without close. Got: " + json);
    }

    @Test
    void detectsFinalizeMethod() {
        String json = capture(new PerfCommand("LegacySwingPanel", 1));
        assertTrue(json.contains("FINALIZE_METHOD"),
                "Should detect finalize() override. Got: " + json);
    }

    @Test
    void detectsThreadLocalLeak() {
        String json = capture(new PerfCommand("LegacySwingPanel", 1));
        assertTrue(json.contains("THREADLOCAL_LEAK"),
                "Should detect ThreadLocal without remove(). Got: " + json);
    }

    @Test
    void detectsStaticMutableCollection() {
        String json = capture(new PerfCommand("LegacySwingPanel", 1));
        assertTrue(json.contains("STATIC_MUTABLE") || json.contains("UNBOUNDED"),
                "Should detect static mutable collection. Got: " + json);
    }

    @Test
    void detectsSqlConcat() {
        String json = capture(new PerfCommand("LegacySwingPanel", 1));
        assertTrue(json.contains("SQL_CONCAT"),
                "Should detect SQL string concatenation. Got: " + json);
    }

    @Test
    void detectsTableFireInLoop() {
        String json = capture(new PerfCommand("LegacySwingPanel.updateTable", 1));
        assertTrue(json.contains("TABLE_FIRE"),
                "Should detect fireTableDataChanged in loop. Got: " + json);
    }

    @Test
    void outputHasCorrectStructure() {
        String json = capture(new PerfCommand("SlowService.processAll", 1));
        assertTrue(json.contains("\"method\""), "Should have method field");
        assertTrue(json.contains("\"findings\""), "Should have findings field");
        assertTrue(json.contains("\"line\""), "Should have line field");
    }
}
