package com.jsrc.app.jfr;

import com.jsrc.app.jfr.JfrProfile.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JfrParser — parses jfr print --json output into JfrProfile.
 */
class JfrParserTest {

    private String loadFixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/jfr-fixture/" + name));
    }

    @Test
    void parsesExecutionSamplesCorrectly() throws IOException {
        String json = loadFixture("execution-sample.json");

        JfrProfile profile = JfrParser.parse(json, "test.jfr", 20,
                false, false, false, false, false);

        assertEquals("test.jfr", profile.file());
        assertEquals(5, profile.totalSamples());
        assertFalse(profile.hotMethods().isEmpty());

        // OrderService.processOrder should be the top method with 3 samples
        HotMethod top = profile.hotMethods().get(0);
        assertEquals("com.example.OrderService", top.className());
        assertEquals("processOrder", top.methodName());
        assertEquals(3, top.samples());
        assertEquals(60.0, top.pct(), 0.1);
    }

    @Test
    void respectsTopNLimit() throws IOException {
        String json = loadFixture("execution-sample.json");

        JfrProfile profile = JfrParser.parse(json, "test.jfr", 2,
                false, false, false, false, false);

        assertEquals(2, profile.hotMethods().size());
    }

    @Test
    void parsesGcEvents() throws IOException {
        String json = loadFixture("execution-sample.json");

        JfrProfile profile = JfrParser.parse(json, "test.jfr", 20,
                true, false, false, false, false);

        assertNotNull(profile.gc());
        assertEquals(3, profile.gc().pauses());
        assertTrue(profile.gc().maxPauseMs() > 0);
        assertTrue(profile.gc().totalPauseMs() > 0);
    }

    @Test
    void skipsGcWhenNotRequested() throws IOException {
        String json = loadFixture("execution-sample.json");

        JfrProfile profile = JfrParser.parse(json, "test.jfr", 20,
                false, false, false, false, false);

        assertNull(profile.gc());
    }

    @Test
    void parsesIoEvents() throws IOException {
        String json = loadFixture("execution-sample.json");

        JfrProfile profile = JfrParser.parse(json, "test.jfr", 20,
                false, true, false, false, false);

        assertNotNull(profile.io());
        assertEquals(2, profile.io().fileReads());
        assertEquals(1, profile.io().fileWrites());
        assertEquals(1, profile.io().socketReads());
        assertEquals(2, profile.io().socketWrites());
    }

    @Test
    void parsesAllocations() throws IOException {
        String json = loadFixture("execution-sample.json");

        JfrProfile profile = JfrParser.parse(json, "test.jfr", 20,
                false, false, true, false, false);

        assertFalse(profile.allocations().isEmpty());
        // String should be first (most bytes)
        Allocation top = profile.allocations().get(0);
        assertEquals("java.lang.String", top.className());
        assertTrue(top.bytes() > 0);
        assertEquals(2, top.count()); // two String alloc events
    }

    @Test
    void parsesContentions() throws IOException {
        String json = loadFixture("execution-sample.json");

        JfrProfile profile = JfrParser.parse(json, "test.jfr", 20,
                false, false, false, true, false);

        assertFalse(profile.contentions().isEmpty());
        Contention c = profile.contentions().get(0);
        assertEquals("com.example.CacheManager", c.className());
        assertEquals("get", c.methodName());
        assertTrue(c.waitMs() > 0);
        assertEquals(1, c.count());
    }

    @Test
    void parsesExceptions() throws IOException {
        String json = loadFixture("execution-sample.json");

        JfrProfile profile = JfrParser.parse(json, "test.jfr", 20,
                false, false, false, false, true);

        assertFalse(profile.exceptions().isEmpty());
        // NPE should be first (2 vs 1)
        ExceptionHotspot top = profile.exceptions().get(0);
        assertEquals("java.lang.NullPointerException", top.className());
        assertEquals(2, top.count());
    }

    @Test
    void parsesAllSectionsSimultaneously() throws IOException {
        String json = loadFixture("execution-sample.json");

        JfrProfile profile = JfrParser.parse(json, "test.jfr", 20,
                true, true, true, true, true);

        assertEquals(5, profile.totalSamples());
        assertNotNull(profile.gc());
        assertNotNull(profile.io());
        assertFalse(profile.allocations().isEmpty());
        assertFalse(profile.contentions().isEmpty());
        assertFalse(profile.exceptions().isEmpty());
    }

    @Test
    void handlesCorruptedJsonGracefully() {
        // Truncated/corrupted JSON should either throw or produce empty profile
        String corrupted = "{\"recording\":{\"events\":[{\"type\":\"jdk.ExecutionSample\",\"values\":{";

        try {
            JfrProfile profile = JfrParser.parse(corrupted, "corrupt.jfr", 20,
                    false, false, false, false, false);
            // If parser is tolerant, should return empty/partial profile
            assertNotNull(profile);
        } catch (Exception e) {
            // Throwing is also acceptable for corrupted input
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void throwsOnNullJson() {
        assertThrows(NullPointerException.class, () ->
                JfrParser.parse(null, "test.jfr", 20,
                        false, false, false, false, false));
    }

    @Test
    void handlesEmptyEventsArray() {
        String json = "{\"recording\":{\"events\":[]}}";

        JfrProfile profile = JfrParser.parse(json, "test.jfr", 20,
                true, true, true, true, true);

        assertEquals(0, profile.totalSamples());
        assertTrue(profile.hotMethods().isEmpty());
        assertEquals(0, profile.gc().pauses());
    }

    @Test
    void eventTypesForReturnsCorrectEvents() {
        var events = JfrParser.eventTypesFor(true, true, true, true, true);
        assertTrue(events.contains("jdk.ExecutionSample"));
        assertTrue(events.contains("jdk.GCPhasePause"));
        assertTrue(events.contains("jdk.FileRead"));
        assertTrue(events.contains("jdk.ObjectAllocationInNewTLAB"));
        assertTrue(events.contains("jdk.JavaMonitorWait"));
        assertTrue(events.contains("jdk.JavaExceptionThrow"));
    }

    @Test
    void eventTypesForMinimalReturnsOnlyExecutionSample() {
        var events = JfrParser.eventTypesFor(false, false, false, false, false);
        assertEquals(1, events.size());
        assertEquals("jdk.ExecutionSample", events.get(0));
    }
}
