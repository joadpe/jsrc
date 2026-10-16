package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.CodeSmell.Severity;

class CachedSmellTest {

    @Test
    void fromStoresOneLetterSeverityCode() {
        assertEquals(new CachedSmell("EMPTY_CATCH_BLOCK", "W", 7, "load", "Risky", "empty"),
                CachedSmell.from(new CodeSmell(
                        "EMPTY_CATCH_BLOCK", Severity.WARNING, "empty", 7, "load", "Risky")));
        assertEquals("I", cachedWith(Severity.INFO).severity());
        assertEquals("E", cachedWith(Severity.ERROR).severity());
    }

    @Test
    void everySeverityRoundTripsThroughCache() {
        for (Severity severity : Severity.values()) {
            assertEquals(severity, cachedWith(severity).severityLevel());
        }
    }

    @Test
    void severityLevelReadsLegacyFullNames() {
        assertEquals(Severity.INFO, storedAs("INFO").severityLevel());
        assertEquals(Severity.WARNING, storedAs("WARNING").severityLevel());
        assertEquals(Severity.ERROR, storedAs("ERROR").severityLevel());
    }

    @Test
    void severityLevelTreatsUnknownValuesAsInfo() {
        assertEquals(Severity.INFO, storedAs("").severityLevel());
        assertEquals(Severity.INFO, storedAs("?").severityLevel());
    }

    private static CachedSmell cachedWith(Severity severity) {
        return CachedSmell.from(new CodeSmell("RULE", severity, "message", 1, "m", "C"));
    }

    private static CachedSmell storedAs(String severity) {
        return new CachedSmell("RULE", severity, 1, "m", "C", "message");
    }
}
