package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BudgetPolicyTest {

    @Test
    @DisplayName("Core commands should be allowed under all budgets")
    void coreCommandsShouldBeAllowed() {
        assertEquals(BudgetPolicy.Action.ALLOW, BudgetPolicy.getAction("index", BudgetProfile.TINY));
        assertEquals(BudgetPolicy.Action.ALLOW, BudgetPolicy.getAction("overview", BudgetProfile.TINY));
        assertEquals(BudgetPolicy.Action.ALLOW, BudgetPolicy.getAction("mini", BudgetProfile.TINY));
        assertEquals(BudgetPolicy.Action.ALLOW, BudgetPolicy.getAction("validate", BudgetProfile.TINY));
    }

    @Test
    @DisplayName("Heavy commands should be denied under tiny budget")
    void heavyCommandsShouldBeDenied() {
        assertEquals(BudgetPolicy.Action.DENY, BudgetPolicy.getAction("context", BudgetProfile.TINY));
        assertEquals(BudgetPolicy.Action.DENY, BudgetPolicy.getAction("call-chain", BudgetProfile.TINY));
        assertEquals(BudgetPolicy.Action.DENY, BudgetPolicy.getAction("dump", BudgetProfile.TINY));
        assertEquals(BudgetPolicy.Action.DENY, BudgetPolicy.getAction("tour", BudgetProfile.TINY));
        assertEquals(BudgetPolicy.Action.DENY, BudgetPolicy.getAction("map", BudgetProfile.TINY));
    }

    @Test
    @DisplayName("Summary should degrade under tiny budget")
    void summaryShouldDegradeUnderTiny() {
        assertEquals(BudgetPolicy.Action.DEGRADE, BudgetPolicy.getAction("summary", BudgetProfile.TINY));
        assertEquals(BudgetPolicy.Action.ALLOW, BudgetPolicy.getAction("summary", BudgetProfile.SMALL));
        assertEquals(BudgetPolicy.Action.ALLOW, BudgetPolicy.getAction("summary", BudgetProfile.STANDARD));
    }

    @Test
    @DisplayName("Read should degrade under tiny and small budgets")
    void readShouldDegrade() {
        assertEquals(BudgetPolicy.Action.DEGRADE, BudgetPolicy.getAction("read", BudgetProfile.TINY));
        assertEquals(BudgetPolicy.Action.DEGRADE, BudgetPolicy.getAction("read", BudgetProfile.SMALL));
        assertEquals(BudgetPolicy.Action.ALLOW, BudgetPolicy.getAction("read", BudgetProfile.STANDARD));
    }

    @Test
    @DisplayName("Standard budget should allow all commands")
    void standardShouldAllowAll() {
        assertEquals(BudgetPolicy.Action.ALLOW, BudgetPolicy.getAction("context", BudgetProfile.STANDARD));
        assertEquals(BudgetPolicy.Action.ALLOW, BudgetPolicy.getAction("call-chain", BudgetProfile.STANDARD));
        assertEquals(BudgetPolicy.Action.ALLOW, BudgetPolicy.getAction("any-command", BudgetProfile.STANDARD));
    }

    @Test
    @DisplayName("Should filter commands for tiny budget")
    void shouldFilterForTiny() {
        assertTrue(BudgetPolicy.isVisibleCommand("index", BudgetProfile.TINY));
        assertTrue(BudgetPolicy.isVisibleCommand("mini", BudgetProfile.TINY));
        assertTrue(BudgetPolicy.isVisibleCommand("scope", BudgetProfile.TINY));
        assertFalse(BudgetPolicy.isVisibleCommand("context", BudgetProfile.TINY));
        assertFalse(BudgetPolicy.isVisibleCommand("summary", BudgetProfile.TINY));
    }

    @Test
    @DisplayName("Should filter commands for small budget")
    void shouldFilterForSmall() {
        assertTrue(BudgetPolicy.isVisibleCommand("index", BudgetProfile.SMALL));
        assertTrue(BudgetPolicy.isVisibleCommand("summary", BudgetProfile.SMALL));
        assertTrue(BudgetPolicy.isVisibleCommand("callers", BudgetProfile.SMALL));
        assertFalse(BudgetPolicy.isVisibleCommand("context", BudgetProfile.SMALL));
        assertFalse(BudgetPolicy.isVisibleCommand("call-chain", BudgetProfile.SMALL));
    }

    @Test
    @DisplayName("Standard budget should show all commands")
    void standardShouldShowAll() {
        assertTrue(BudgetPolicy.isVisibleCommand("index", BudgetProfile.STANDARD));
        assertTrue(BudgetPolicy.isVisibleCommand("context", BudgetProfile.STANDARD));
        assertTrue(BudgetPolicy.isVisibleCommand("any-command", BudgetProfile.STANDARD));
    }

    @Test
    @DisplayName("Should return correct field sets for profiles")
    void shouldReturnFieldSets() {
        var tinyFields = BudgetPolicy.getFieldsForProfile("class", BudgetProfile.TINY);
        assertNotNull(tinyFields);
        assertTrue(tinyFields.contains("name"));
        assertTrue(tinyFields.contains("packageName"));
        assertTrue(tinyFields.contains("methodCount"));
        assertFalse(tinyFields.contains("file"));

        var smallFields = BudgetPolicy.getFieldsForProfile("class", BudgetProfile.SMALL);
        assertNotNull(smallFields);
        assertTrue(smallFields.contains("name"));
        assertTrue(smallFields.contains("file"));

        var standardFields = BudgetPolicy.getFieldsForProfile("class", BudgetProfile.STANDARD);
        assertNull(standardFields); // All fields
    }
}
