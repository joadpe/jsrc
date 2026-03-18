package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.AnnotationInfo;

/**
 * Verifies that OutputFormatter writes to injected PrintStream, not System.out.
 */
class OutputStreamInjectionTest {

    @Test
    void jsonFormatter_writesToInjectedStream() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var formatter = new JsonFormatter(false, null, out);

        formatter.printResult(Map.of("key", "value"));

        String output = baos.toString().trim();
        assertTrue(output.contains("key"), "Output should contain 'key' but was: " + output);
        assertTrue(output.contains("value"), "Output should contain 'value' but was: " + output);
    }

    @Test
    void jsonFormatter_doesNotWriteToSystemOut() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var formatter = new JsonFormatter(false, null, out);

        var originalOut = System.out;
        var sysCapture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(sysCapture));
        try {
            formatter.printResult(Map.of("test", "data"));
        } finally {
            System.setOut(originalOut);
        }

        assertEquals("", sysCapture.toString().trim(),
                "Nothing should be written to System.out");
        assertTrue(baos.toString().contains("test"),
                "Output should go to injected stream");
    }

    @Test
    void textFormatter_writesToInjectedStream() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var formatter = new TextFormatter(false, out);

        formatter.printResult(Map.of("key", "value"));

        String output = baos.toString().trim();
        assertFalse(output.isEmpty(), "Output should not be empty");
    }

    @Test
    void factoryMethod_acceptsPrintStream() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var formatter = OutputFormatter.create(true, false, null, out);

        formatter.printResult(Map.of("hello", "world"));

        assertTrue(baos.toString().contains("hello"));
    }

    @Test
    void factoryMethod_defaultsToSystemOut() {
        // Existing factory methods should still work (backward compatible)
        var formatter = OutputFormatter.create(true);
        assertNotNull(formatter);
    }
}
