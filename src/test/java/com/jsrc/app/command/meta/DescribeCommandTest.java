package com.jsrc.app.command.meta;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jsrc.app.ExitCode;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;

/**
 * RED tests for DescribeCommand exit code improvements (issue #30).
 * Tests that DescribeCommand list mode returns positive counts (visibleCommands.size()).
 */
class DescribeCommandTest {

    @Test
    void testDescribeListTinyReturnsCommandCount() {
        // A3: DescribeCommand list mode returns positive count (visible commands)
        var result = runDescribeList(BudgetProfile.TINY);
        
        assertTrue(result > 0, 
            "DescribeCommand TINY list should return positive count (visibleCommands.size()), got: " + result);
    }

    @Test
    void testDescribeListSmallReturnsCommandCount() {
        // A3: DescribeCommand SMALL returns visible command count
        var result = runDescribeList(BudgetProfile.SMALL);
        
        assertTrue(result > 0, 
            "DescribeCommand SMALL list should return positive count, got: " + result);
    }

    @Test
    void testDescribeListStandardReturnsCommandCount() {
        // A3: DescribeCommand STANDARD returns all commands count
        var result = runDescribeList(BudgetProfile.STANDARD);
        
        assertTrue(result > 0, 
            "DescribeCommand STANDARD list should return positive count (all commands), got: " + result);
    }

    @Test
    void testDescribeUnknownCommandReturnsNotFound() {
        // A4: DescribeCommand with unknown specific command returns NOT_FOUND (regression check)
        var result = runDescribeSpecific("nonexistent_command_xyz", BudgetProfile.STANDARD);
        
        assertEquals(ExitCode.NOT_FOUND, result, 
            "DescribeCommand for unknown command should return NOT_FOUND (1), got: " + result);
    }

    @Test
    void testDescribeKnownCommandReturnsOk() {
        // A4: DescribeCommand with known specific command returns OK (regression check)
        var result = runDescribeSpecific("overview", BudgetProfile.STANDARD);
        
        assertEquals(ExitCode.OK, result, 
            "DescribeCommand for known command should return OK (0), got: " + result);
    }

    @Test
    void testDescribeListCountMatchesOutputArraySize() {
        // A3: Verify return value matches commands array size in output
        var output = captureDescribeListOutput(BudgetProfile.TINY);
        var returnValue = runDescribeList(BudgetProfile.TINY);
        
        var commands = (List<?>) output.get("commands");
        assertEquals(commands.size(), returnValue, 
            "Return value should equal commands array size");
    }

    @Test
    void testDescribeListOutputStructureUnchanged() {
        // A7: Verify output structure remains valid after changes
        var output = captureDescribeListOutput(BudgetProfile.SMALL);
        
        assertNotNull(output.get("budget"), "Output should contain 'budget' field");
        assertNotNull(output.get("commands"), "Output should contain 'commands' field");
        assertNotNull(output.get("totalCommands"), "Output should contain 'totalCommands' field");
        
        assertTrue(output.get("commands") instanceof List, "'commands' should be a list");
        var commands = (List<?>) output.get("commands");
        assertTrue(commands.size() > 0, "commands list should not be empty");
    }

    @Test
    void testDescribeListTinyFiltersCommands() {
        // A7: Verify TINY profile filters commands correctly
        var output = captureDescribeListOutput(BudgetProfile.TINY);
        var commands = (List<?>) output.get("commands");
        
        // TINY should have fewer commands than STANDARD
        var standardOutput = captureDescribeListOutput(BudgetProfile.STANDARD);
        var standardCommands = (List<?>) standardOutput.get("commands");
        
        assertTrue(commands.size() < standardCommands.size(), 
            "TINY should have fewer commands than STANDARD");
    }

    private int runDescribeList(BudgetProfile profile) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CommandContext ctx = buildContext(profile, out);
            var cmd = new DescribeCommand(profile);
            return cmd.execute(ctx);
        } catch (Exception e) {
            fail("Command execution failed: " + e.getMessage());
            return -1;
        }
    }
    
    private CommandContext buildContext(BudgetProfile profile, ByteArrayOutputStream out) {
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
    }

    private int runDescribeSpecific(String commandName, BudgetProfile profile) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CommandContext ctx = buildContext(profile, out);
            var cmd = new DescribeCommand(profile, commandName);
            return cmd.execute(ctx);
        } catch (Exception e) {
            fail("Command execution failed: " + e.getMessage());
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureDescribeListOutput(BudgetProfile profile) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CommandContext ctx = buildContext(profile, out);
            var cmd = new DescribeCommand(profile);
            cmd.execute(ctx);
            
            String json = out.toString(StandardCharsets.UTF_8).trim();
            return (Map<String, Object>) JsonReader.parse(json);
        } catch (Exception e) {
            fail("Command execution failed: " + e.getMessage());
            return Map.of();
        }
    }
}
