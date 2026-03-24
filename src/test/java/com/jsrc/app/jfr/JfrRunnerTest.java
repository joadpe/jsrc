package com.jsrc.app.jfr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JfrRunner — validates tool detection and command generation.
 * Note: actual jcmd/jfr execution depends on JDK availability.
 */
class JfrRunnerTest {

    @Test
    void findToolThrowsOnNull() {
        assertThrows(NullPointerException.class, () -> JfrRunner.findTool(null));
    }

    @Test
    void findToolThrowsForNonexistentTool() {
        assertThrows(JfrToolNotFoundException.class, () ->
                JfrRunner.findTool("nonexistent-tool-xyz-999"));
    }

    @Test
    void findToolReturnsPathForJava() {
        // java should always be available in test environment
        String path = JfrRunner.findTool("java");
        assertNotNull(path);
        assertTrue(path.contains("java"));
    }

    @Test
    void readJfrThrowsForNonexistentFile(@TempDir Path tempDir) {
        Path nonexistent = tempDir.resolve("nonexistent.jfr");
        assertThrows(IllegalArgumentException.class, () ->
                JfrRunner.readJfr(nonexistent, null));
    }

    @Test
    void readJfrThrowsOnNullFile() {
        assertThrows(NullPointerException.class, () ->
                JfrRunner.readJfr(null, null));
    }

    @Test
    void jfrToolNotFoundExceptionContainsToolName() {
        var ex = new JfrToolNotFoundException("jcmd");
        assertEquals("jcmd", ex.tool());
        assertTrue(ex.getMessage().contains("jcmd"));
        assertTrue(ex.getMessage().contains("JDK 14+"));
    }

    @Test
    void jfrExecutionExceptionContainsContext() {
        var cause = new IOException("connection refused");
        var ex = new JfrExecutionException("starting recording", cause);
        assertTrue(ex.getMessage().contains("starting recording"));
        assertTrue(ex.getMessage().contains("connection refused"));
        assertEquals(cause, ex.getCause());
    }
}
