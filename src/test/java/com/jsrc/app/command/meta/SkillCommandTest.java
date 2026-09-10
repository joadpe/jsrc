package com.jsrc.app.command.meta;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;

/**
 * RED tests for SkillCommand exit code improvements (issue #30).
 * Tests that SkillCommand returns positive counts instead of ExitCode.OK (0).
 */
class SkillCommandTest {

    @Test
    void testSkillTinyJsonReturnsPositiveCount() {
        // A1: SkillCommand with TINY profile returns positive count (commands.size())
        var result = runSkillCommand(BudgetProfile.TINY, true);
        
        assertTrue(result > 0, 
            "SkillCommand TINY should return positive count (commands.size()), got: " + result);
    }

    @Test
    void testSkillSmallJsonReturnsPositiveCount() {
        // A1: SkillCommand with SMALL profile returns positive count
        var result = runSkillCommand(BudgetProfile.SMALL, true);
        
        assertTrue(result > 0, 
            "SkillCommand SMALL should return positive count, got: " + result);
    }

    @Test
    void testSkillStandardJsonReturnsPositiveCount() {
        // A1: SkillCommand with STANDARD profile returns positive count (all commands)
        var result = runSkillCommand(BudgetProfile.STANDARD, true);
        
        assertTrue(result > 0, 
            "SkillCommand STANDARD should return positive count (all commands), got: " + result);
    }

    @Test
    void testSkillTinyMarkdownReturnsPositiveCount() {
        // A7: Markdown mode also returns positive count
        var result = runSkillCommand(BudgetProfile.TINY, false);
        
        assertTrue(result > 0, 
            "SkillCommand TINY markdown should return positive count (surface.size()), got: " + result);
    }

    @Test
    void testSkillStandardMarkdownReturnsCommandCount() {
        // A1 + A7: STANDARD markdown returns full command count
        var result = runSkillCommand(BudgetProfile.STANDARD, false);
        
        assertTrue(result > 0, 
            "SkillCommand STANDARD markdown should return positive count, got: " + result);
    }

    @Test
    void testSkillJsonOutputStructureUnchanged() {
        // A7: Verify JSON output structure remains valid
        var output = captureSkillJsonOutput(BudgetProfile.TINY);
        
        assertNotNull(output.get("budget"), "JSON should contain 'budget' field");
        assertNotNull(output.get("commands"), "JSON should contain 'commands' field");
        assertNotNull(output.get("rules"), "JSON should contain 'rules' field");
        assertNotNull(output.get("playbook"), "JSON should contain 'playbook' field");
        
        assertTrue(output.get("commands") instanceof List, "'commands' should be a list");
        var commands = (List<?>) output.get("commands");
        assertTrue(commands.size() > 0, "commands list should not be empty");
    }

    @Test
    void testSkillTinyJsonCountMatchesCommandsArraySize() {
        // A1: Verify return value matches commands.size() in JSON
        var output = captureSkillJsonOutput(BudgetProfile.TINY);
        var returnValue = runSkillCommand(BudgetProfile.TINY, true);
        
        var commands = (List<?>) output.get("commands");
        assertEquals(commands.size(), returnValue, 
            "Return value should equal commands array size");
    }

    private int runSkillCommand(BudgetProfile profile, boolean json) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CommandContext ctx = buildContext(profile, out, json);
            var cmd = new SkillCommand(profile);
            return cmd.execute(ctx);
        } catch (Exception e) {
            fail("Command execution failed: " + e.getMessage());
            return -1;
        }
    }
    
    private CommandContext buildContext(BudgetProfile profile, ByteArrayOutputStream out, boolean json) {
        if (json) {
            PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
            var formatter = new JsonFormatter(false, null, ps);
            return new CommandContext(
                null, // javaFiles
                null, // rootPath
                null, // config
                formatter,
                null, // indexedCodebase
                null, // parser
                false, // mdOutput
                null, // outDir
                false, // fullOutput
                false, // noTest
                new com.jsrc.app.cli.BudgetContext(profile, null, null, false, false, null)
            );
        } else {
            // For markdown mode, capture to out stream
            PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
            System.setOut(ps);
            return new CommandContext(
                null, null, null, null, null, null
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureSkillJsonOutput(BudgetProfile profile) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CommandContext ctx = buildContext(profile, out, true);
            var cmd = new SkillCommand(profile);
            cmd.execute(ctx);
            
            String json = out.toString(StandardCharsets.UTF_8).trim();
            return (Map<String, Object>) JsonReader.parse(json);
        } catch (Exception e) {
            fail("Command execution failed: " + e.getMessage());
            return Map.of();
        }
    }
}
