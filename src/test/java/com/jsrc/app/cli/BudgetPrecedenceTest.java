package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BudgetPrecedenceTest {

    private String originalEnv;

    @BeforeEach
    void setUp() {
        originalEnv = System.getenv("JSRC_BUDGET");
    }

    @AfterEach
    void tearDown() {
        // Note: Can't actually modify env vars in Java easily, so this test
        // documents expected behavior rather than fully testing it
    }

    @Test
    @DisplayName("Should parse budget profile from CLI flag")
    void shouldParseFromCliFlag() {
        // Simulated test - in real integration test would use picocli parsing
        String budgetFlag = "tiny";
        BudgetProfile profile = BudgetProfile.fromString(budgetFlag);
        assertEquals(BudgetProfile.TINY, profile);
    }

    @Test
    @DisplayName("Should default to STANDARD when nothing is set")
    void shouldDefaultToStandard() {
        BudgetProfile profile = BudgetProfile.fromString(null);
        assertEquals(BudgetProfile.STANDARD, profile);
    }

    @Test
    @DisplayName("BudgetProfile enum should have correct profile names")
    void shouldHaveCorrectProfileNames() {
        assertEquals("tiny", BudgetProfile.TINY.profileName());
        assertEquals("small", BudgetProfile.SMALL.profileName());
        assertEquals("standard", BudgetProfile.STANDARD.profileName());
    }

    @Test
    @DisplayName("Should handle whitespace in budget string")
    void shouldHandleWhitespace() {
        assertEquals(BudgetProfile.TINY, BudgetProfile.fromString(" tiny "));
        assertEquals(BudgetProfile.SMALL, BudgetProfile.fromString(" small "));
    }
}
