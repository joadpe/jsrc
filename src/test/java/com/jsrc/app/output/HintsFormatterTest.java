package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.model.CommandHint;

class HintsFormatterTest {

    @Test
    @DisplayName("printResultWithHints appends nextCommands to JSON map")
    void hintsAppendedToMap() {
        var out = new ByteArrayOutputStream();
        var formatter = new JsonFormatter(false, java.util.Set.of(), new PrintStream(out));

        var data = new LinkedHashMap<String, Object>();
        data.put("class", "Foo");
        data.put("method", "bar");

        var hints = List.of(
                new CommandHint("callers bar", "Who calls bar?"),
                new CommandHint("impact bar", "Change risk")
        );

        formatter.printResultWithHints(data, hints);
        String json = out.toString().trim();

        assertTrue(json.contains("\"nextCommands\""), "Should contain nextCommands");
        assertTrue(json.contains("\"callers bar\""), "Should contain resolved command");
        assertTrue(json.contains("\"Who calls bar?\""), "Should contain description");
        assertTrue(json.contains("\"class\":\"Foo\""), "Should preserve original data");
    }

    @Test
    @DisplayName("printResultWithHints with empty hints delegates to printResult")
    void emptyHintsDelegatesToPrintResult() {
        var out = new ByteArrayOutputStream();
        var formatter = new JsonFormatter(false, java.util.Set.of(), new PrintStream(out));

        var data = new LinkedHashMap<String, Object>();
        data.put("class", "Foo");

        formatter.printResultWithHints(data, List.of());
        String json = out.toString().trim();

        assertFalse(json.contains("nextCommands"), "Empty hints should not add nextCommands");
        assertTrue(json.contains("\"class\":\"Foo\""));
    }

    @Test
    @DisplayName("printResultWithHints with null hints delegates to printResult")
    void nullHintsDelegatesToPrintResult() {
        var out = new ByteArrayOutputStream();
        var formatter = new JsonFormatter(false, java.util.Set.of(), new PrintStream(out));

        var data = new LinkedHashMap<String, Object>();
        data.put("total", 5);

        formatter.printResultWithHints(data, null);
        String json = out.toString().trim();

        assertFalse(json.contains("nextCommands"));
    }
}
