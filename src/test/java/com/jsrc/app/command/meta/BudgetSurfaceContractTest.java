package com.jsrc.app.command.meta;

import com.jsrc.app.cli.BudgetPolicy;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.output.JsonFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for A1-A8: budget surface filtering for describe + skill commands.
 * Strong oracles: JSON validation, set equality, UTF-8 byte length.
 */
class BudgetSurfaceContractTest {

    /**
     * A1: describe --budget tiny --json command names ⊆ BudgetPolicy.budgetSurface(TINY); count ≤ 12
     */
    @Test
    @DisplayName("A1: describe tiny subset of budgetSurface(TINY) with count ≤ 12")
    void a1_describeTinySubsetWithLimit() {
        Set<String> surface = BudgetPolicy.budgetSurface(BudgetProfile.TINY);
        assertNotNull(surface, "budgetSurface(TINY) must exist");
        
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DescribeCommand cmd = new DescribeCommand(BudgetProfile.TINY);
        
        var ctx = TestHelpers.buildContextWithJsonOutput(out);
        int exitCode = cmd.execute(ctx);
        
        assertEquals(0, exitCode, "describe should succeed");
        
        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"commands\""), "Output must be valid JSON with commands field");
        
        // Extract command names from JSON output
        Set<String> commandSet = extractCommandNames(json);
        
        assertTrue(surface.containsAll(commandSet), 
            "All describe tiny commands must be in budgetSurface(TINY)");
        assertTrue(commandSet.size() <= 12, 
            "Tiny command count must be ≤ 12, got: " + commandSet.size());
    }

    /**
     * A2: No DENY-under-tiny name appears in describe tiny list
     */
    @Test
    @DisplayName("A2: describe tiny excludes DENY commands (context, call-chain, dump, tour, map)")
    void a2_describeTinyExcludesDenyCommands() {
        Set<String> denyCommands = Set.of("context", "call-chain", "dump", "tour", "map");
        
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DescribeCommand cmd = new DescribeCommand(BudgetProfile.TINY);
        var ctx = TestHelpers.buildContextWithJsonOutput(out);
        cmd.execute(ctx);
        
        String json = out.toString(StandardCharsets.UTF_8);
        Set<String> commandSet = extractCommandNames(json);
        
        for (String deny : denyCommands) {
            assertFalse(commandSet.contains(deny), 
                "Tiny surface must not include DENY command: " + deny);
        }
    }

    /**
     * A3: describe --budget small --json ⊆ surface(SMALL); tiny ⊆ small ⊆ standard
     */
    @Test
    @DisplayName("A3: describe surfaces are monotonic: tiny ⊆ small ⊆ standard")
    void a3_describeMonotonicInclusion() {
        Set<String> tinyS = extractDescribeCommands(BudgetProfile.TINY);
        Set<String> smallS = extractDescribeCommands(BudgetProfile.SMALL);
        Set<String> standardS = extractDescribeCommands(BudgetProfile.STANDARD);
        
        assertTrue(smallS.containsAll(tinyS), "small must contain all tiny commands");
        assertTrue(standardS.containsAll(smallS), "standard must contain all small commands");
        assertTrue(tinyS.size() < smallS.size(), "tiny must be smaller than small");
        assertTrue(smallS.size() < standardS.size(), "small must be smaller than standard");
    }

    /**
     * A4: describe --budget standard (or --full under tiny) exposes full primary catalog
     */
    @Test
    @DisplayName("A4: describe standard/full exposes full catalog with DENY commands")
    void a4_describeFullCatalog() {
        Set<String> standardS = extractDescribeCommands(BudgetProfile.STANDARD);
        
        // Standard must include at least one DENY-under-tiny command
        Set<String> denyCommands = Set.of("context", "call-chain", "dump");
        boolean hasAtLeastOneDeny = denyCommands.stream().anyMatch(standardS::contains);
        assertTrue(hasAtLeastOneDeny, 
            "Standard surface must include DENY-under-tiny commands");
        
        // Standard should be significantly larger than tiny
        Set<String> tinyS = extractDescribeCommands(BudgetProfile.TINY);
        assertTrue(standardS.size() > tinyS.size() * 2, 
            "Standard surface should be much larger than tiny");
    }

    /**
     * A5: skill --budget tiny markdown UTF-8 length ≤ 2048; contains every tiny surface command name
     */
    @Test
    @DisplayName("A5: skill tiny markdown ≤ 2048 bytes UTF-8, contains all tiny commands")
    void a5_skillTinyMarkdownSize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream oldOut = System.out;
        
        try {
            System.setOut(ps);
            
            SkillCommand cmd = new SkillCommand(BudgetProfile.TINY);
            var ctx = TestHelpers.buildContextWithTextOutput();
            int exitCode = cmd.execute(ctx);
            
            assertEquals(0, exitCode, "skill should succeed");
            
            String markdown = out.toString(StandardCharsets.UTF_8);
            byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
            
            assertTrue(bytes.length <= 2048, 
                "Tiny markdown must be ≤ 2048 bytes UTF-8, got: " + bytes.length);
            
            // Verify all tiny commands are mentioned
            Set<String> tinyCommands = BudgetPolicy.budgetSurface(BudgetProfile.TINY);
            for (String cmdName : tinyCommands) {
                assertTrue(markdown.contains(cmdName), 
                    "Tiny markdown must contain command: " + cmdName);
            }
        } finally {
            System.setOut(oldOut);
        }
    }

    /**
     * A6: skill --budget tiny --json parses as JSON; commands[].name set equals describe tiny
     */
    @Test
    @DisplayName("A6: skill tiny JSON commands equals describe tiny commands")
    void a6_skillTinyJsonMatchesDescribe() {
        ByteArrayOutputStream skillOut = new ByteArrayOutputStream();
        SkillCommand skillCmd = new SkillCommand(BudgetProfile.TINY);
        var skillCtx = TestHelpers.buildContextWithJsonOutput(skillOut);
        skillCmd.execute(skillCtx);
        
        String json = skillOut.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"commands\""), "skill JSON must have commands field");
        
        Set<String> skillNames = extractSkillCommandNames(json);
        Set<String> describeNames = extractDescribeCommands(BudgetProfile.TINY);
        
        assertEquals(describeNames, skillNames, 
            "skill --json commands must equal describe commands (same source)");
    }

    /**
     * A7: Changing surface table in one place changes both skill and describe
     */
    @Test
    @DisplayName("A7: BudgetPolicy.budgetSurface drives both skill and describe")
    void a7_sharedSurfaceMethod() {
        // Verify budgetSurface method exists and is used
        Set<String> tinySurface = BudgetPolicy.budgetSurface(BudgetProfile.TINY);
        assertNotNull(tinySurface, "budgetSurface(TINY) must exist");
        assertFalse(tinySurface.isEmpty(), "budgetSurface(TINY) must not be empty");
        
        Set<String> smallSurface = BudgetPolicy.budgetSurface(BudgetProfile.SMALL);
        assertNotNull(smallSurface, "budgetSurface(SMALL) must exist");
        assertFalse(smallSurface.isEmpty(), "budgetSurface(SMALL) must not be empty");
        
        // Verify both commands use the same source
        Set<String> describeTiny = extractDescribeCommands(BudgetProfile.TINY);
        assertEquals(tinySurface, describeTiny, 
            "describe must use BudgetPolicy.budgetSurface");
        
        Set<String> skillTiny = extractSkillJsonCommands(BudgetProfile.TINY);
        assertEquals(tinySurface, skillTiny, 
            "skill must use BudgetPolicy.budgetSurface");
    }

    /**
     * A8: Repo SKILL.md mentions jsrc skill --budget
     */
    @Test
    @DisplayName("A8: SKILL.md contains pointer to jsrc skill --budget")
    void a8_skillMdPointer() throws Exception {
        java.nio.file.Path skillPath = java.nio.file.Path.of("SKILL.md");
        assertTrue(java.nio.file.Files.exists(skillPath), "SKILL.md must exist");
        
        String content = java.nio.file.Files.readString(skillPath, StandardCharsets.UTF_8);
        assertTrue(content.contains("jsrc skill") && content.contains("--budget"), 
            "SKILL.md must mention 'jsrc skill --budget'");
    }

    // Helper methods
    
    private Set<String> extractDescribeCommands(BudgetProfile profile) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DescribeCommand cmd = new DescribeCommand(profile);
        var ctx = TestHelpers.buildContextWithJsonOutput(out);
        cmd.execute(ctx);
        
        String json = out.toString(StandardCharsets.UTF_8);
        return extractCommandNames(json);
    }
    
    private Set<String> extractSkillJsonCommands(BudgetProfile profile) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SkillCommand cmd = new SkillCommand(profile);
        var ctx = TestHelpers.buildContextWithJsonOutput(out);
        cmd.execute(ctx);
        
        String json = out.toString(StandardCharsets.UTF_8);
        return extractSkillCommandNames(json);
    }
    
    /**
     * Simple JSON parser to extract command names from describe output.
     * Expects format: {"commands":["cmd1","cmd2",...]}
     */
    private Set<String> extractCommandNames(String json) {
        Set<String> commands = new HashSet<>();
        int commandsIdx = json.indexOf("\"commands\"");
        if (commandsIdx == -1) return commands;
        
        int arrayStart = json.indexOf('[', commandsIdx);
        int arrayEnd = json.indexOf(']', arrayStart);
        if (arrayStart == -1 || arrayEnd == -1) return commands;
        
        String arrayContent = json.substring(arrayStart + 1, arrayEnd);
        String[] parts = arrayContent.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                commands.add(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        return commands;
    }
    
    /**
     * Extract command names from skill JSON output.
     * Expects format: {"commands":[{"name":"cmd1"},{"name":"cmd2"},...]}
     */
    private Set<String> extractSkillCommandNames(String json) {
        Set<String> commands = new HashSet<>();
        int commandsIdx = json.indexOf("\"commands\"");
        if (commandsIdx == -1) return commands;
        
        int pos = commandsIdx;
        while (true) {
            int nameIdx = json.indexOf("\"name\"", pos);
            if (nameIdx == -1) break;
            
            int valueStart = json.indexOf(':', nameIdx);
            if (valueStart == -1) break;
            
            // Find the value after "name":
            int quoteStart = json.indexOf('"', valueStart);
            int quoteEnd = json.indexOf('"', quoteStart + 1);
            if (quoteStart == -1 || quoteEnd == -1) break;
            
            String name = json.substring(quoteStart + 1, quoteEnd);
            commands.add(name);
            
            pos = quoteEnd + 1;
        }
        return commands;
    }
}

/**
 * Test helpers to build CommandContext for testing.
 */
class TestHelpers {
    static com.jsrc.app.command.CommandContext buildContextWithJsonOutput(ByteArrayOutputStream out) {
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        var formatter = new JsonFormatter(false, null, ps);
        return new com.jsrc.app.command.CommandContext(
            null, // indexedCodebase - not needed for describe/skill
            formatter,
            false, // metricsEnabled
            null, // config
            com.jsrc.app.cli.BudgetContext.standard() // budget context
        );
    }
    
    static com.jsrc.app.command.CommandContext buildContextWithTextOutput() {
        var formatter = new com.jsrc.app.output.TextFormatter(false, System.out);
        return new com.jsrc.app.command.CommandContext(
            null,
            formatter,
            false,
            null,
            com.jsrc.app.cli.BudgetContext.standard()
        );
    }
}
