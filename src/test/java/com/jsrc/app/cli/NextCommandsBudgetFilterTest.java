package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.model.CommandHint;

class NextCommandsBudgetFilterTest {

    @Test
    @DisplayName("DENY commands are filtered under tiny")
    void denyCommandsFilteredTiny() {
        var ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        var hints = List.of(
            new CommandHint("overview", "Overview"),
            new CommandHint("map", "Visual map"),       // DENY
            new CommandHint("tour", "Guided tour"),     // DENY
            new CommandHint("context Foo", "Context")   // DENY
        );

        var filtered = NextCommandsBudgetFilter.apply(hints, ctx);

        assertNotNull(filtered);
        assertEquals(1, filtered.size());
        assertEquals("overview", filtered.get(0).command());
    }

    @Test
    @DisplayName("DENY commands are filtered under small")
    void denyCommandsFilteredSmall() {
        var ctx = new BudgetContext(BudgetProfile.SMALL, null, null, false, false, null);
        var hints = List.of(
            new CommandHint("overview", "Overview"),
            new CommandHint("mini Foo", "Mini"),
            new CommandHint("context Foo", "Context"),  // DENY
            new CommandHint("tour", "Tour")             // DENY
        );

        var filtered = NextCommandsBudgetFilter.apply(hints, ctx);

        assertNotNull(filtered);
        assertEquals(2, filtered.size());
        assertFalse(filtered.stream().anyMatch(h -> h.command().startsWith("context")));
        assertFalse(filtered.stream().anyMatch(h -> h.command().startsWith("tour")));
    }

    @Test
    @DisplayName("Tiny profile caps at 2 hints")
    void tinyProfileCapsAtTwo() {
        var ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        var hints = List.of(
            new CommandHint("overview", "Overview"),
            new CommandHint("mini Foo", "Mini"),
            new CommandHint("read Bar", "Read"),
            new CommandHint("classes", "Classes"),
            new CommandHint("find keyword", "Find")
        );

        var filtered = NextCommandsBudgetFilter.apply(hints, ctx);

        assertNotNull(filtered);
        assertEquals(2, filtered.size());
    }

    @Test
    @DisplayName("Small profile caps at 3 hints")
    void smallProfileCapsAtThree() {
        var ctx = new BudgetContext(BudgetProfile.SMALL, null, null, false, false, null);
        var hints = List.of(
            new CommandHint("overview", "Overview"),
            new CommandHint("mini Foo", "Mini"),
            new CommandHint("read Bar", "Read"),
            new CommandHint("classes", "Classes"),
            new CommandHint("find keyword", "Find")
        );

        var filtered = NextCommandsBudgetFilter.apply(hints, ctx);

        assertNotNull(filtered);
        assertEquals(3, filtered.size());
    }

    @Test
    @DisplayName("Standard profile returns null (strips hints)")
    void standardProfileStripsHints() {
        var ctx = new BudgetContext(BudgetProfile.STANDARD, null, null, false, false, null);
        var hints = List.of(
            new CommandHint("overview", "Overview"),
            new CommandHint("mini Foo", "Mini")
        );

        var filtered = NextCommandsBudgetFilter.apply(hints, ctx);

        assertNull(filtered, "Standard profile should strip all hints");
    }

    @Test
    @DisplayName("--no-next-commands suppresses all hints")
    void noNextCommandsFlag() {
        var ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, true, null);
        var hints = List.of(
            new CommandHint("overview", "Overview"),
            new CommandHint("mini Foo", "Mini")
        );

        var filtered = NextCommandsBudgetFilter.apply(hints, ctx);

        assertNull(filtered, "--no-next-commands should suppress hints");
    }

    @Test
    @DisplayName("Empty hints return null")
    void emptyHints() {
        var ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        var filtered = NextCommandsBudgetFilter.apply(List.of(), ctx);

        assertNull(filtered);
    }

    @Test
    @DisplayName("Null hints return null")
    void nullHints() {
        var ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        var filtered = NextCommandsBudgetFilter.apply(null, ctx);

        assertNull(filtered);
    }

    @Test
    @DisplayName("ALLOW and DEGRADE commands pass through")
    void allowAndDegradePass() {
        var ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        var hints = List.of(
            new CommandHint("overview", "Overview"),        // ALLOW
            new CommandHint("read Foo", "Read"),            // DEGRADE (allowed)
            new CommandHint("mini Bar", "Mini"),            // ALLOW
            new CommandHint("summary Baz", "Summary")       // DEGRADE in tiny (allowed)
        );

        var filtered = NextCommandsBudgetFilter.apply(hints, ctx);

        assertNotNull(filtered);
        // Should keep all ALLOW/DEGRADE, then cap at 2
        assertEquals(2, filtered.size());
    }

    @Test
    @DisplayName("Command token extraction handles arguments")
    void commandTokenExtraction() {
        var ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        var hints = List.of(
            new CommandHint("read Foo.bar", "Read method"),
            new CommandHint("call-chain method", "Trace"),  // DENY
            new CommandHint("find \"keyword\"", "Search")
        );

        var filtered = NextCommandsBudgetFilter.apply(hints, ctx);

        assertNotNull(filtered);
        // Should filter out call-chain (DENY), keep read and find, cap at 2
        assertEquals(2, filtered.size());
        assertFalse(filtered.stream().anyMatch(h -> h.command().startsWith("call-chain")));
    }

    @Test
    @DisplayName("Filter then cap: keeps best available hints")
    void filterThenCap() {
        var ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        var hints = List.of(
            new CommandHint("overview", "Overview"),    // ALLOW
            new CommandHint("map", "Map"),              // DENY - filtered
            new CommandHint("mini Foo", "Mini"),        // ALLOW
            new CommandHint("tour", "Tour"),            // DENY - filtered
            new CommandHint("read Bar", "Read")         // DEGRADE - allowed
        );

        var filtered = NextCommandsBudgetFilter.apply(hints, ctx);

        assertNotNull(filtered);
        // After filtering: overview, mini, read (3 hints)
        // After cap: first 2
        assertEquals(2, filtered.size());
        assertEquals("overview", filtered.get(0).command());
        assertEquals("mini Foo", filtered.get(1).command());
    }

    @Test
    @DisplayName("All hints filtered out returns null")
    void allHintsFiltered() {
        var ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, false, null);
        var hints = List.of(
            new CommandHint("map", "Map"),           // DENY
            new CommandHint("tour", "Tour"),         // DENY
            new CommandHint("context Foo", "Context"), // DENY
            new CommandHint("call-chain bar", "Chain") // DENY
        );

        var filtered = NextCommandsBudgetFilter.apply(hints, ctx);

        assertNull(filtered, "Should return null when all hints are filtered");
    }
}
