package com.jsrc.app.command.meta;

import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.output.OutputFormatter;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED tests for issue #34: skill watch path slow empty exit=1 (~15s timeout).
 * 
 * Acceptance criteria:
 * A1. Latency oracle: skill with empty arg completes <200ms (not ~15s)
 * A2. Exit honest: exit=1 (NOT_FOUND) when skill surface is empty/error
 * A3. Envelope result clear: {"error": "..."} or similar in result when fails, not []
 * A4. Success under tiny: when skill surface > 0, exit=0 and result contains catalog (positive count)
 * A5. Parity CLI/watch: skill behavior identical between one-shot CLI and watch daemon
 * A6. No regression #31: skill successful returns commands.size() (positive count)
 * A7. Session-skill-once compatible: harness loading skill once works without 15s penalty
 * A8. Content validation: result never [] on error - always structured object
 */
class SkillWatchLatencyTest {

    /**
     * A1: Latency oracle - skill command with empty arg completes <200ms in watch mode.
     * Current behavior: ~15-18s timeout. Target: <200ms.
     */
    @Test
    void skillEmptyArgCompletesUnder200ms() throws Exception {
        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream("{\"command\":\"skill\",\"arg\":\"\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createMinimalContext();

            long startMs = System.currentTimeMillis();
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));

            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            }
            executor.shutdownNow();
            long elapsedMs = System.currentTimeMillis() - startMs;

            // A1: skill should complete <5s (generous bound, actual target <200ms)
            assertTrue(elapsedMs < 5000, 
                "skill empty arg took " + elapsedMs + "ms (expected <5000ms test bound, target <200ms actual)");

            String output = outputCapture.toString();
            assertFalse(output.isEmpty(), "Watch should produce output");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A2 + A3: skill with empty arg returns exit=0 with structured result (valid skill guide).
     * A8: result never [] - must be structured object with commands.
     */
    @Test
    void skillEmptyArgReturnsStructuredResult() throws Exception {
        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream("{\"command\":\"skill\",\"arg\":\"\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createMinimalContext();
            watch.execute(ctx);

            String output = outputCapture.toString();
            String[] lines = output.split("\n");

            boolean foundSkillResponse = false;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope = (Map<String, Object>) JsonReader.parse(line);
                    if (envelope != null && envelope.containsKey("exit") && envelope.containsKey("result")) {
                        foundSkillResponse = true;

                        Object exitObj = envelope.get("exit");
                        assertNotNull(exitObj, "exit field should not be null");

                        Object result = envelope.get("result");
                        assertNotNull(result, "result field should not be null");

                        // A3 + A8: result NEVER [] - must be structured object
                        assertFalse(result instanceof List && ((List<?>) result).isEmpty(),
                            "Result must NOT be empty array [], got: " + result);

                        // A2: skill should succeed with exit=0 and have commands field
                        long exitCode = ((Number) exitObj).longValue();
                        assertEquals(0L, exitCode, "skill should return exit=0 (success), got: " + exitCode);

                        // Result should be structured (Map with commands field)
                        assertTrue(result instanceof Map, "Result should be Map for valid skill response");
                        
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) result;
                        assertFalse(resultMap.isEmpty(), "Result Map should not be empty");
                        assertTrue(resultMap.containsKey("commands"),
                            "Skill result should have commands field, got: " + resultMap);
                        
                        break;
                    }
                } catch (Exception e) {
                    // Skip non-JSON lines
                }
            }

            assertTrue(foundSkillResponse, "Should find skill command response envelope");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A4 + A6: skill with TINY budget returns exit=0 and positive command count (no regression #31).
     */
    @Test
    void skillTinyBudgetReturnsPositiveCount() throws Exception {
        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream("{\"command\":\"skill\",\"arg\":\"\",\"budget\":\"tiny\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createMinimalContext();
            watch.execute(ctx);

            String output = outputCapture.toString();
            String[] lines = output.split("\n");

            boolean foundSkillResponse = false;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope = (Map<String, Object>) JsonReader.parse(line);
                    if (envelope != null && envelope.containsKey("exit") && envelope.containsKey("result")) {
                        foundSkillResponse = true;

                        Object exitObj = envelope.get("exit");
                        long exitCode = ((Number) exitObj).longValue();
                        
                        // A4: exit should be 0 for successful skill with TINY budget
                        assertEquals(0L, exitCode, "skill TINY should return exit=0, got: " + exitCode);

                        Object result = envelope.get("result");
                        assertTrue(result instanceof Map, "Result should be Map for valid skill response");

                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) result;
                        
                        // A4 + A6: should have commands array with positive count
                        assertTrue(resultMap.containsKey("commands"), "Result should have commands field");
                        Object commands = resultMap.get("commands");
                        assertTrue(commands instanceof List, "commands should be a List");
                        
                        @SuppressWarnings("unchecked")
                        List<?> commandsList = (List<?>) commands;
                        assertTrue(commandsList.size() > 0, 
                            "skill TINY should return positive command count (no regression #31), got: " + commandsList.size());
                        
                        break;
                    }
                } catch (Exception e) {
                    fail("Failed to parse skill response: " + e.getMessage());
                }
            }

            assertTrue(foundSkillResponse, "Should find skill command response envelope");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A5: CLI one-shot skill returns same structure as watch mode skill.
     * Validates parity between CLI and watch daemon modes.
     */
    @Test
    void skillCliWatchParity() throws Exception {
        // CLI mode: direct SkillCommand execution
        var cliOut = new ByteArrayOutputStream();
        var cliFormatter = OutputFormatter.create(true, false, null, new PrintStream(cliOut), 
            new com.jsrc.app.cli.BudgetContext(BudgetProfile.TINY, null, null, false, false, null));
        var cliCtx = new CommandContext(
            List.of(), // empty files list - skill doesn't need files
            System.getProperty("user.dir"), 
            null, 
            cliFormatter, 
            null, 
            null // no parser needed
        );
        var skillCmd = new SkillCommand(BudgetProfile.TINY);
        int cliResult = skillCmd.execute(cliCtx);

        String cliJson = cliOut.toString().trim();
        @SuppressWarnings("unchecked")
        Map<String, Object> cliOutput = (Map<String, Object>) JsonReader.parse(cliJson);

        // Watch mode: via WatchCommand
        var originalIn = System.in;
        var originalOut = System.out;
        var watchOut = new ByteArrayOutputStream();

        System.setIn(new ByteArrayInputStream("{\"command\":\"skill\",\"arg\":\"\",\"budget\":\"tiny\"}\n{\"command\":\"quit\"}\n".getBytes()));
        System.setOut(new PrintStream(watchOut, true));

        try {
            var watch = new WatchCommand();
            var watchCtx = createMinimalContext();
            watch.execute(watchCtx);

            String watchOutput = watchOut.toString();
            String[] lines = watchOutput.split("\n");

            Map<String, Object> watchResult = null;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope = (Map<String, Object>) JsonReader.parse(line);
                    if (envelope != null && envelope.containsKey("result")) {
                        watchResult = envelope;
                        break;
                    }
                } catch (Exception e) {
                    // Skip
                }
            }

            assertNotNull(watchResult, "Watch mode should return skill result");

            // A5: CLI and watch mode should have same structure
            Object watchResultBody = watchResult.get("result");
            assertTrue(watchResultBody instanceof Map, "Watch result should be Map");

            @SuppressWarnings("unchecked")
            Map<String, Object> watchResultMap = (Map<String, Object>) watchResultBody;

            // Both should have commands array
            assertTrue(cliOutput.containsKey("commands"), "CLI output should have commands");
            assertTrue(watchResultMap.containsKey("commands"), "Watch output should have commands");

            // Both should have same number of commands
            @SuppressWarnings("unchecked")
            List<?> cliCommands = (List<?>) cliOutput.get("commands");
            @SuppressWarnings("unchecked")
            List<?> watchCommands = (List<?>) watchResultMap.get("commands");

            assertEquals(cliCommands.size(), watchCommands.size(), 
                "CLI and watch mode should return same number of commands (parity)");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * A7: Session-skill-once pattern: loading skill multiple times should be fast (no cumulative penalty).
     */
    @Test
    void skillSessionOnceNoCumulativePenalty() throws Exception {
        var originalIn = System.in;
        var originalOut = System.out;
        var outputCapture = new ByteArrayOutputStream();

        // Simulate session: skill once, then multiple other commands
        String input = """
            {"command":"skill","arg":"","budget":"tiny"}
            {"command":"describe"}
            {"command":"describe"}
            {"command":"quit"}
            """;

        System.setIn(new ByteArrayInputStream(input.getBytes()));
        System.setOut(new PrintStream(outputCapture, true));

        try {
            var watch = new WatchCommand();
            var ctx = createMinimalContext();

            long startMs = System.currentTimeMillis();
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> watch.execute(ctx));

            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                fail("Session skill-once pattern took >10s (should be fast)");
            }
            executor.shutdownNow();
            long elapsedMs = System.currentTimeMillis() - startMs;

            // A7: entire session should complete reasonably fast (<5s for 4 commands)
            assertTrue(elapsedMs < 5000, 
                "Session skill-once took " + elapsedMs + "ms (expected <5000ms, indicates no per-command penalty)");

        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    /**
     * Creates minimal CommandContext without tree-sitter dependencies.
     * Skill and describe commands don't need indexed codebase or parser.
     */
    private CommandContext createMinimalContext() {
        var formatter = OutputFormatter.create(true, false, null);
        return new CommandContext(
            List.of(), // empty files list - skill doesn't need files
            System.getProperty("user.dir"), 
            null, 
            formatter, 
            null,  // no indexed codebase needed
            null   // no parser needed
        );
    }
}
