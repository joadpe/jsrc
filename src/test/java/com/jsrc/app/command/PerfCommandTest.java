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
    void outputHasCorrectStructure() {
        String json = capture(new PerfCommand("SlowService.processAll", 1));
        assertTrue(json.contains("\"method\""), "Should have method field");
        assertTrue(json.contains("\"findings\""), "Should have findings field");
        assertTrue(json.contains("\"line\""), "Should have line field");
    }
}
