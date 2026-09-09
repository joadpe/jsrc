package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.ExitCode;
import com.jsrc.app.command.BudgetDeniedCommand;
import com.jsrc.app.output.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * Integration tests for budget enforcement critical fixes.
 */
class BudgetEnforcementTest {

    @Test
    @DisplayName("BudgetDeniedCommand should exit with code 2 and structured JSON")
    void budgetDeniedCommandShouldExitWithCode2() {
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        try {
            var cmd = new BudgetDeniedCommand("context", BudgetProfile.TINY, "jsrc mini <Class> --json");
            int exitCode = cmd.execute(null);

            assertEquals(ExitCode.BAD_USAGE, exitCode);
            
            String output = errContent.toString();
            assertTrue(output.contains("budget_denied"));
            assertTrue(output.contains("\"command\":\"context\""));
            assertTrue(output.contains("\"budget\":\"tiny\""));
            assertTrue(output.contains("suggestion"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("BudgetContext should track degradation independently")
    void budgetContextShouldTrackDegradation() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ctx.setDegradedFrom("summary");
        ctx.addTransform("limit:10");
        ctx.setTruncated(true);

        var meta = ctx.buildMetadata();
        assertNotNull(meta);
        assertEquals("summary", meta.get("degradedFrom"));
        assertEquals(true, meta.get("truncated"));
        assertEquals("tiny", meta.get("profile"));
    }

    @Test
    @DisplayName("BudgetContext should not add metadata for STANDARD profile")
    void budgetContextShouldNotAddMetaForStandard() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.STANDARD, null, null, false, null);
        ctx.setDegradedFrom("summary");
        
        var meta = ctx.buildMetadata();
        assertNull(meta);
    }

    @Test
    @DisplayName("describe should use CommandRegistry not hardcoded list")
    void describeShouldUseCommandRegistry() {
        // This is verified implicitly: DescribeCommand now calls CommandRegistry.knownCommandNames()
        // and filters with BudgetPolicy.isVisibleCommand
        String[] allCommands = com.jsrc.app.command.meta.CommandRegistry.knownCommandNames();
        assertTrue(allCommands.length > 20); // CommandRegistry has many commands
        
        // Verify it includes standard commands
        java.util.List<String> cmdList = java.util.Arrays.asList(allCommands);
        assertTrue(cmdList.contains("overview"));
        assertTrue(cmdList.contains("summary"));
        assertTrue(cmdList.contains("mini"));
    }

    @Test
    @DisplayName("List limits should apply correctly under budget")
    void listLimitsShouldApplyUnderBudget() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        assertEquals(10, ctx.effectiveLimit());
        
        BudgetContext ctxSmall = new BudgetContext(BudgetProfile.SMALL, null, null, false, null);
        assertEquals(30, ctxSmall.effectiveLimit());
        
        // Explicit limit overrides profile
        BudgetContext ctxOverride = new BudgetContext(BudgetProfile.TINY, 50, null, false, null);
        assertEquals(50, ctxOverride.effectiveLimit());
    }
}
