package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BudgetProfileTest {

    @Test
    @DisplayName("Should parse budget profile from string")
    void shouldParseFromString() {
        assertEquals(BudgetProfile.TINY, BudgetProfile.fromString("tiny"));
        assertEquals(BudgetProfile.SMALL, BudgetProfile.fromString("small"));
        assertEquals(BudgetProfile.STANDARD, BudgetProfile.fromString("standard"));
        assertEquals(BudgetProfile.STANDARD, BudgetProfile.fromString(null));
        assertEquals(BudgetProfile.STANDARD, BudgetProfile.fromString(""));
    }

    @Test
    @DisplayName("Should be case-insensitive")
    void shouldBeCaseInsensitive() {
        assertEquals(BudgetProfile.TINY, BudgetProfile.fromString("TINY"));
        assertEquals(BudgetProfile.SMALL, BudgetProfile.fromString("Small"));
        assertEquals(BudgetProfile.STANDARD, BudgetProfile.fromString("STANDARD"));
    }

    @Test
    @DisplayName("Should throw on invalid budget name")
    void shouldThrowOnInvalid() {
        assertThrows(IllegalArgumentException.class, () -> BudgetProfile.fromString("invalid"));
        assertThrows(IllegalArgumentException.class, () -> BudgetProfile.fromString("medium"));
    }

    @Test
    @DisplayName("Should have correct default limits")
    void shouldHaveCorrectLimits() {
        assertEquals(10, BudgetProfile.TINY.defaultLimit());
        assertEquals(30, BudgetProfile.SMALL.defaultLimit());
        assertEquals(Integer.MAX_VALUE, BudgetProfile.STANDARD.defaultLimit());
    }

    @Test
    @DisplayName("Should force JSON under tiny and small budgets")
    void shouldForceJson() {
        assertTrue(BudgetProfile.TINY.forceJson());
        assertTrue(BudgetProfile.SMALL.forceJson());
        assertFalse(BudgetProfile.STANDARD.forceJson());
    }

    @Test
    @DisplayName("Should have correct field sets")
    void shouldHaveCorrectFieldSets() {
        assertEquals(BudgetProfile.FieldSet.TINY, BudgetProfile.TINY.fieldSet());
        assertEquals(BudgetProfile.FieldSet.SMALL, BudgetProfile.SMALL.fieldSet());
        assertEquals(BudgetProfile.FieldSet.ALL, BudgetProfile.STANDARD.fieldSet());
    }
}
