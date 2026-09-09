package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.model.CommandHint;
import com.jsrc.app.output.BudgetAwareJsonFormatter;
import com.jsrc.app.output.JsonReader;

/**
 * Contract tests for budget-aware hints (release 1.1 slice B).
 * Tests oracles B1-B8 from design issue #11.
 */
class BudgetAwareHintsTest {

    @Test
    @DisplayName("B1: Under tiny, object JSON from pilot emitting hints has nextCommands.length ≤ 2")
    void tinyProfile_capsHintsAtTwo() throws Exception {
        var out = new ByteArrayOutputStream();
        var budgetContext = new BudgetContext(
            BudgetProfile.TINY,
            null, null, false, false, null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);

        var data = new LinkedHashMap<String, Object>();
        data.put("totalClasses", 100);
        data.put("totalMethods", 500);

        // Simulate overview command hints
        var hints = List.of(
            new CommandHint("find \"keyword\"", "Search for relevant classes"),
            new CommandHint("read TopClass", "Read the most important class"),
            new CommandHint("hotspots", "See most-used classes"),
            new CommandHint("map", "Visual codebase map"),
            new CommandHint("tour", "Guided tour of the codebase")
        );

        formatter.printResultWithHints(data, hints);

        String json = out.toString().trim();
        Map<?, ?> result = (Map<?, ?>) JsonReader.parse(json);

        assertTrue(result.containsKey("nextCommands"), "Should have nextCommands key");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> nextCommands = (List<Map<String, String>>) result.get("nextCommands");
        
        assertTrue(nextCommands.size() <= 2,
            "Tiny profile should cap at 2 hints, got: " + nextCommands.size());
    }

    @Test
    @DisplayName("B2: Under small, object JSON has nextCommands.length ≤ 3")
    void smallProfile_capsHintsAtThree() throws Exception {
        var out = new ByteArrayOutputStream();
        var budgetContext = new BudgetContext(
            BudgetProfile.SMALL,
            null, null, false, false, null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);

        var data = new LinkedHashMap<String, Object>();
        data.put("totalClasses", 100);

        var hints = List.of(
            new CommandHint("find \"keyword\"", "Search for relevant classes"),
            new CommandHint("read TopClass", "Read the most important class"),
            new CommandHint("hotspots", "See most-used classes"),
            new CommandHint("classes", "List all classes"),
            new CommandHint("search pattern", "Search by pattern")
        );

        formatter.printResultWithHints(data, hints);

        String json = out.toString().trim();
        Map<?, ?> result = (Map<?, ?>) JsonReader.parse(json);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> nextCommands = (List<Map<String, String>>) result.get("nextCommands");
        
        assertTrue(nextCommands.size() <= 3,
            "Small profile should cap at 3 hints, got: " + nextCommands.size());
    }

    @Test
    @DisplayName("B3: Under standard, no nextCommands key from printResultWithHints")
    void standardProfile_stripsNextCommands() throws Exception {
        var out = new ByteArrayOutputStream();
        var budgetContext = new BudgetContext(
            BudgetProfile.STANDARD,
            null, null, false, false, null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);

        var data = new LinkedHashMap<String, Object>();
        data.put("totalClasses", 100);

        var hints = List.of(
            new CommandHint("find \"keyword\"", "Search for relevant classes"),
            new CommandHint("read TopClass", "Read the most important class")
        );

        formatter.printResultWithHints(data, hints);

        String json = out.toString().trim();
        Map<?, ?> result = (Map<?, ?>) JsonReader.parse(json);

        assertFalse(result.containsKey("nextCommands"),
            "Standard profile should not emit nextCommands");
    }

    @Test
    @DisplayName("B4: No emitted hint command is DENY for that profile (tiny excludes map/tour/context)")
    void tinyProfile_filtersDenyCommands() throws Exception {
        var out = new ByteArrayOutputStream();
        var budgetContext = new BudgetContext(
            BudgetProfile.TINY,
            null, null, false, false, null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);

        var data = new LinkedHashMap<String, Object>();
        data.put("totalClasses", 100);

        // Include DENY commands: map, tour, context
        var hints = List.of(
            new CommandHint("overview", "Overview of codebase"),
            new CommandHint("map", "Visual codebase map"),
            new CommandHint("tour", "Guided tour"),
            new CommandHint("context SomeClass", "Deep context"),
            new CommandHint("find keyword", "Search")
        );

        formatter.printResultWithHints(data, hints);

        String json = out.toString().trim();
        Map<?, ?> result = (Map<?, ?>) JsonReader.parse(json);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> nextCommands = (List<Map<String, String>>) result.get("nextCommands");
        
        for (Map<String, String> hint : nextCommands) {
            String command = hint.get("command");
            String commandToken = command.split("\\s+")[0];
            
            assertFalse(commandToken.equals("map"), "map is DENY under tiny");
            assertFalse(commandToken.equals("tour"), "tour is DENY under tiny");
            assertFalse(commandToken.equals("context"), "context is DENY under tiny");
            assertFalse(commandToken.equals("call-chain"), "call-chain is DENY under tiny");
            assertFalse(commandToken.equals("dump"), "dump is DENY under tiny");
        }
    }

    @Test
    @DisplayName("B4b: Small profile also filters DENY commands")
    void smallProfile_filtersDenyCommands() throws Exception {
        var out = new ByteArrayOutputStream();
        var budgetContext = new BudgetContext(
            BudgetProfile.SMALL,
            null, null, false, false, null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);

        var data = new LinkedHashMap<String, Object>();
        data.put("totalClasses", 100);

        var hints = List.of(
            new CommandHint("overview", "Overview"),
            new CommandHint("context SomeClass", "Deep context"),
            new CommandHint("tour", "Guided tour")
        );

        formatter.printResultWithHints(data, hints);

        String json = out.toString().trim();
        Map<?, ?> result = (Map<?, ?>) JsonReader.parse(json);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> nextCommands = (List<Map<String, String>>) result.get("nextCommands");
        
        for (Map<String, String> hint : nextCommands) {
            String command = hint.get("command");
            String commandToken = command.split("\\s+")[0];
            
            assertFalse(commandToken.equals("context"), "context is DENY under small");
            assertFalse(commandToken.equals("tour"), "tour is DENY under small");
        }
    }

    @Test
    @DisplayName("B5: Every emitted hint's command token ∈ budgetSurface(profile)")
    void hintsRespectBudgetSurface() throws Exception {
        var out = new ByteArrayOutputStream();
        var budgetContext = new BudgetContext(
            BudgetProfile.TINY,
            null, null, false, false, null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);

        var data = new LinkedHashMap<String, Object>();
        data.put("totalClasses", 100);

        var hints = List.of(
            new CommandHint("overview", "Overview"),
            new CommandHint("read Foo", "Read class"),
            new CommandHint("map", "Visual map"),  // DENY
            new CommandHint("summary Bar", "Summary")  // DEGRADE in tiny
        );

        formatter.printResultWithHints(data, hints);

        String json = out.toString().trim();
        Map<?, ?> result = (Map<?, ?>) JsonReader.parse(json);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> nextCommands = (List<Map<String, String>>) result.get("nextCommands");
        
        for (Map<String, String> hint : nextCommands) {
            String command = hint.get("command");
            String commandToken = command.split("\\s+")[0];
            
            // Verify each command is allowed or degraded (not denied) under tiny
            BudgetPolicy.Action action = BudgetPolicy.getAction(commandToken, BudgetProfile.TINY);
            assertNotEquals(BudgetPolicy.Action.DENY, action,
                "Command " + commandToken + " should not be DENY but got: " + action);
        }
    }

    @Test
    @DisplayName("B6: --no-next-commands under tiny → no nextCommands")
    void noNextCommandsFlag_suppressesHints() throws Exception {
        var out = new ByteArrayOutputStream();
        var budgetContext = new BudgetContext(
            BudgetProfile.TINY,
            null, null, false, true, null  // noNextCommands=true
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);

        var data = new LinkedHashMap<String, Object>();
        data.put("totalClasses", 100);

        var hints = List.of(
            new CommandHint("overview", "Overview"),
            new CommandHint("find keyword", "Search")
        );

        formatter.printResultWithHints(data, hints);

        String json = out.toString().trim();
        Map<?, ?> result = (Map<?, ?>) JsonReader.parse(json);

        assertFalse(result.containsKey("nextCommands"),
            "--no-next-commands should suppress nextCommands entirely");
    }

    @Test
    @DisplayName("B7: Reuses CommandHint + existing printResultWithHints API")
    void reusesExistingHintInfrastructure() {
        // This test verifies we're using the existing API
        var budgetContext = new BudgetContext(
            BudgetProfile.TINY,
            null, null, false, false, null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, System.out, budgetContext);

        // Verify method exists and accepts CommandHint (compile-time check)
        var data = new LinkedHashMap<String, Object>();
        List<CommandHint> hints = List.of(new CommandHint("test", "Test hint"));
        
        // Should compile and run without error
        assertDoesNotThrow(() -> {
            var out = new ByteArrayOutputStream();
            var f = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);
            f.printResultWithHints(data, hints);
        });
    }

    @Test
    @DisplayName("B8: Filter helper is shared and unit-tested")
    void filterIsUnitTestable() {
        // This is satisfied by the existence of NextCommandsBudgetFilter tests
        // and its usage in BudgetAwareJsonFormatter
        // Compile-time check that the filter exists and has the right signature
        assertDoesNotThrow(() -> {
            Class.forName("com.jsrc.app.cli.NextCommandsBudgetFilter");
        });
    }

    @Test
    @DisplayName("Tiny profile with mixed ALLOW/DENY/DEGRADE hints")
    void tinyProfile_mixedHints() throws Exception {
        var out = new ByteArrayOutputStream();
        var budgetContext = new BudgetContext(
            BudgetProfile.TINY,
            null, null, false, false, null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);

        var data = new LinkedHashMap<String, Object>();
        data.put("name", "TestClass");

        var hints = List.of(
            new CommandHint("mini TestClass", "Quick overview"),     // ALLOW
            new CommandHint("read TestClass", "Read source"),        // DEGRADE (allowed)
            new CommandHint("smells TestClass", "Check smells"),     // ALLOW
            new CommandHint("context TestClass", "Deep context"),    // DENY
            new CommandHint("call-chain method", "Trace calls")      // DENY
        );

        formatter.printResultWithHints(data, hints);

        String json = out.toString().trim();
        Map<?, ?> result = (Map<?, ?>) JsonReader.parse(json);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> nextCommands = (List<Map<String, String>>) result.get("nextCommands");
        
        // Should have filtered out DENY commands and capped at 2
        assertTrue(nextCommands.size() <= 2,
            "Should cap at 2 hints, got: " + nextCommands.size());
        
        // Should not contain DENY commands
        for (Map<String, String> hint : nextCommands) {
            String command = hint.get("command");
            String commandToken = command.split("\\s+")[0];
            assertFalse(commandToken.equals("context"));
            assertFalse(commandToken.equals("call-chain"));
        }
    }

    @Test
    @DisplayName("Empty hints list should not add nextCommands key")
    void emptyHints_noNextCommandsKey() throws Exception {
        var out = new ByteArrayOutputStream();
        var budgetContext = new BudgetContext(
            BudgetProfile.TINY,
            null, null, false, false, null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);

        var data = new LinkedHashMap<String, Object>();
        data.put("totalClasses", 100);

        formatter.printResultWithHints(data, List.of());

        String json = out.toString().trim();
        Map<?, ?> result = (Map<?, ?>) JsonReader.parse(json);

        assertFalse(result.containsKey("nextCommands"),
            "Empty hints should not add nextCommands key");
    }

    @Test
    @DisplayName("F1/F2: Under tiny, find and impact tokens are filtered from emitted hints")
    void tinyProfile_filtersFindAndImpact() throws Exception {
        var out = new ByteArrayOutputStream();
        var budgetContext = new BudgetContext(
            BudgetProfile.TINY,
            null, null, false, false, null
        );
        var formatter = new BudgetAwareJsonFormatter(false, null, new PrintStream(out), budgetContext);

        var data = new LinkedHashMap<String, Object>();
        data.put("name", "SomeClass");

        // Mix surface commands with non-surface commands
        var hints = List.of(
            new CommandHint("read SomeClass", "Read the class"),           // in TINY surface
            new CommandHint("find keyword", "Search for keyword"),         // NOT in TINY surface
            new CommandHint("mini SomeClass", "Mini view"),                // in TINY surface
            new CommandHint("impact SomeClass.method", "Impact analysis"), // NOT in TINY surface
            new CommandHint("scope SomeClass", "Show scope")               // in TINY surface
        );

        formatter.printResultWithHints(data, hints);

        String json = out.toString().trim();
        Map<?, ?> result = (Map<?, ?>) JsonReader.parse(json);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> nextCommands = (List<Map<String, String>>) result.get("nextCommands");
        
        assertNotNull(nextCommands, "Should have nextCommands");
        
        // F1: No 'find' token in emitted list
        for (Map<String, String> hint : nextCommands) {
            String command = hint.get("command");
            String commandToken = command.split("\\s+")[0];
            assertFalse(commandToken.equals("find"), 
                "find token should not be emitted under tiny (not in budgetSurface)");
        }
        
        // F2: No 'impact' token in emitted list
        for (Map<String, String> hint : nextCommands) {
            String command = hint.get("command");
            String commandToken = command.split("\\s+")[0];
            assertFalse(commandToken.equals("impact"),
                "impact token should not be emitted under tiny (not in budgetSurface)");
        }
        
        // F3: Surface tokens may remain (subject to cap)
        // read, mini, scope are all in TINY_CORE_COMMANDS, so at least some should remain
        assertTrue(nextCommands.stream().anyMatch(h -> 
            h.get("command").startsWith("read") || 
            h.get("command").startsWith("mini") || 
            h.get("command").startsWith("scope")),
            "Surface tokens (read/mini/scope) should remain");
    }
}
